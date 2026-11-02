import Foundation
import SQLite3

// The phone's copy of the API's records, row for row in the tables D1 keeps them in
// (CopyTables, generated from `@hyoban/data-spec/tables`), with the revision it was last brought
// up to. Only the rows feed writes it; the phone's own edits are kept elsewhere until they are
// published. A copy made for other tables than these is dropped and read again.

/// One row of D1's, as `GET /v2/rows` hands it out.
nonisolated struct CopyRow: Sendable {
    var table: String
    var key: String
    var rev: Int64
    var deletedAt: String?
    var record: String
}

/// A SQLite database, through the system's library.
nonisolated final class SQLite {
    nonisolated(unsafe) private var handle: OpaquePointer?

    init(path: String) throws {
        if sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            throw SQLiteError(message: String(cString: sqlite3_errmsg(handle)))
        }
        try execute("PRAGMA journal_mode = WAL")
    }

    deinit { sqlite3_close(handle) }

    struct SQLiteError: Error, LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    enum Value {
        case text(String), int(Int64), double(Double), null
    }

    func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(handle, sql, nil, nil, &error) != SQLITE_OK {
            let message = error.map { String(cString: $0) } ?? "SQLite failed"
            sqlite3_free(error)
            throw SQLiteError(message: message)
        }
    }

    /// Runs [sql] once for each list of [bindings].
    func run(_ sql: String, _ rows: [[Value]]) throws {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        for values in rows {
            bind(statement, values)
            if sqlite3_step(statement) != SQLITE_DONE {
                throw SQLiteError(message: String(cString: sqlite3_errmsg(handle)))
            }
            sqlite3_reset(statement)
            sqlite3_clear_bindings(statement)
        }
    }

    func run(_ sql: String, _ values: [Value] = []) throws {
        try run(sql, [values])
    }

    /// The rows [sql] gives, each read by [read].
    func query<T>(_ sql: String, _ values: [Value] = [], _ read: (Row) -> T) throws -> [T] {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        bind(statement, values)
        var out: [T] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            out.append(read(Row(statement: statement)))
        }
        return out
    }

    struct Row {
        fileprivate let statement: OpaquePointer?
        func text(_ index: Int32) -> String? {
            sqlite3_column_text(statement, index).map { String(cString: $0) }
        }
        func int(_ index: Int32) -> Int64? {
            sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : sqlite3_column_int64(statement, index)
        }
        func double(_ index: Int32) -> Double? {
            sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : sqlite3_column_double(statement, index)
        }
    }

    /// Runs [block] in one transaction, rolled back if it throws.
    func transaction<T>(_ block: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let value = try block()
            try execute("COMMIT")
            return value
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        if sqlite3_prepare_v2(handle, sql, -1, &statement, nil) != SQLITE_OK {
            throw SQLiteError(message: String(cString: sqlite3_errmsg(handle)))
        }
        return statement
    }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private func bind(_ statement: OpaquePointer?, _ values: [Value]) {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .text(let text): sqlite3_bind_text(statement, index, text, -1, Self.transient)
            case .int(let int): sqlite3_bind_int64(statement, index, int)
            case .double(let double): sqlite3_bind_double(statement, index, double)
            case .null: sqlite3_bind_null(statement, index)
            }
        }
    }
}

extension SQLite.Value {
    static func optional(_ text: String?) -> SQLite.Value { text.map { .text($0) } ?? .null }
}

/// Where the app keeps what is its own and not for the system's backup: the copy, the ledger, the footprints.
enum Storage {
    static let root: URL = {
        let url = URL.applicationSupportDirectory.appending(path: "Here", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var excluded = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
        return url
    }()

    static func file(_ name: String) -> URL { root.appending(path: name) }

    static func directory(_ name: String) -> URL {
        let url = root.appending(path: name, directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

final class CopyStore {
    private let db: SQLite

    init(path: URL = Storage.file("copy.db")) throws {
        db = try SQLite(path: path.path)
        let version = Int64(Self.version)
        let current = try db.query("PRAGMA user_version") { $0.int(0) ?? 0 }.first ?? 0
        if current != version {
            let tables = try db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { $0.text(0) ?? "" }
            try db.transaction {
                for table in tables { try db.execute("DROP TABLE \"\(table)\"") }
                for statement in CopyTables.statements { try db.execute(statement) }
                try db.execute("CREATE TABLE copy_state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                try db.execute("PRAGMA user_version = \(version)")
            }
        }
    }

    /// A number for the tables' statements, so a change to them makes the copy again.
    private static var version: Int32 {
        var hash: UInt32 = 0x811c_9dc5
        for byte in CopyTables.statements.joined(separator: "\n").utf8 {
            hash = (hash ^ UInt32(byte)) &* 0x0100_0193
        }
        return Int32(bitPattern: hash & 0x7fff_ffff)
    }

    /// The API's revision the copy was last brought up to; 0 before the first sync.
    func revision() -> Int64 { state("revision") ?? 0 }

    /// Which of the API's databases the copy is of, nil before an API said.
    func database() -> Int64? { state("database") }

    /// Writes [rows] over the copy's and keeps [revision] and the [database] they are of, all at once:
    /// after a sync from the start ([fresh]), the copy holds those rows and no others.
    func apply(_ rows: [CopyRow], revision: Int64, fresh: Bool = false, database: Int64? = nil) throws {
        try db.transaction {
            if fresh {
                for table in CopyTables.names { try db.execute("DELETE FROM \(table)") }
                try db.execute("DELETE FROM copy_state WHERE key = 'database'")
            }
            for (table, tableRows) in Dictionary(grouping: rows, by: \.table) {
                guard CopyTables.names.contains(table) else { continue }
                try db.run(
                    "INSERT INTO \(table) (key, rev, deleted_at, record) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (key) DO UPDATE SET rev = excluded.rev, deleted_at = excluded.deleted_at, record = excluded.record",
                    tableRows.map { [.text($0.key), .int($0.rev), .optional($0.deletedAt), .text($0.record)] }
                )
            }
            try keep("revision", revision)
            if let database { try keep("database", database) }
        }
    }

    /// A table's records still there, by key.
    func live(_ table: String) -> [String: String] {
        guard CopyTables.names.contains(table) else { return [:] }
        let rows = (try? db.query("SELECT key, record FROM \(table) WHERE deleted_at IS NULL") { ($0.text(0) ?? "", $0.text(1) ?? "") }) ?? []
        return Dictionary(rows, uniquingKeysWith: { _, last in last })
    }

    /// Empties the copy, revision and all, as when the records are to come from another API.
    func clear() throws { try apply([], revision: 0, fresh: true) }

    private func state(_ key: String) -> Int64? {
        (try? db.query("SELECT value FROM copy_state WHERE key = ?", [.text(key)]) { $0.int(0) })?.first ?? nil
    }

    private func keep(_ key: String, _ value: Int64) throws {
        try db.run("INSERT INTO copy_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", [.text(key), .int(value)])
    }
}
