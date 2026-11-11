import Foundation

/// One row of D1's, as `GET /v2/rows` hands it out.
public struct CopyRow: Sendable, Hashable {
    public let table: String
    public let key: String
    public let rev: Int64
    public let deletedAt: String?
    public let record: String

    public init(table: String, key: String, rev: Int64, deletedAt: String?, record: String) {
        self.table = table
        self.key = key
        self.rev = rev
        self.deletedAt = deletedAt
        self.record = record
    }
}

/// This device's copy of the API's records, row for row in the tables D1 keeps them in
/// (`CopyTables`), with the revision it was last brought up to. Only the rows feed writes it;
/// the device's own edits are kept in `ChangeStore` until they are published. A copy made for
/// other tables than these is dropped and read again.
public final class CopyStore: @unchecked Sendable {
    private let database: SQLiteDatabase
    private let lock = NSLock()

    public init(file: URL) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        database = try SQLiteDatabase(path: file.path)
        let version = Int64(bitPattern: UInt64(Self.fingerprint(CopyTables.statements.joined(separator: "\n"))) & 0x7FFF_FFFF)
        if try database.scalar("PRAGMA user_version").int != version {
            let tables = try database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { $0.text(0) ?? "" }
            try database.transaction {
                for table in tables { try database.execute("DROP TABLE \"\(table)\"") }
                for statement in CopyTables.statements { try database.execute(statement) }
                try database.execute("CREATE TABLE copy_state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                try database.execute("PRAGMA user_version = \(version)")
            }
        }
    }

    /// A stable hash of the tables' statements, so a change of them makes the copy again.
    private static func fingerprint(_ text: String) -> UInt32 {
        text.utf8.reduce(UInt32(2_166_136_261)) { ($0 ^ UInt32($1)) &* 16_777_619 }
    }

    private func locked<T>(_ block: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try block()
    }

    /// The API's revision the copy was last brought up to; 0 before the first sync.
    public func revision() -> Int64 {
        locked { (try? database.scalar("SELECT value FROM copy_state WHERE key = 'revision'").int) ?? 0 }
    }

    /// Which of the API's databases the copy is of, nil before an API said.
    public func databaseID() -> Int64? {
        locked { try? database.scalar("SELECT value FROM copy_state WHERE key = 'database'").int }
    }

    /// Writes [rows] over the copy's and keeps [revision]; after a sync from the start ([fresh]),
    /// the copy holds those rows and no others.
    public func apply(_ rows: [CopyRow], revision: Int64, fresh: Bool = false, databaseID: Int64? = nil) throws {
        try locked {
            try database.transaction {
                if fresh {
                    for table in CopyTables.names { try database.execute("DELETE FROM \(table)") }
                    try database.execute("DELETE FROM copy_state WHERE key = 'database'")
                }
                for (table, tableRows) in Dictionary(grouping: rows, by: \.table) {
                    guard CopyTables.names.contains(table) else { continue }
                    let statement = try database.prepare(
                        "INSERT INTO \(table) (key, rev, deleted_at, record) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (key) DO UPDATE SET rev = excluded.rev, deleted_at = excluded.deleted_at, record = excluded.record"
                    )
                    for row in tableRows {
                        try statement.bind([.text(row.key), .integer(row.rev), SQLiteValue(row.deletedAt), .text(row.record)])
                        _ = try statement.step()
                        statement.reset()
                    }
                }
                try database.run("INSERT INTO copy_state (key, value) VALUES ('revision', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", [.integer(revision)])
                if let databaseID {
                    try database.run("INSERT INTO copy_state (key, value) VALUES ('database', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", [.integer(databaseID)])
                }
            }
        }
    }

    /// A table's records still there, by key.
    public func live(_ table: String) -> [(key: String, record: String)] {
        guard CopyTables.names.contains(table) else { return [] }
        return locked {
            (try? database.query("SELECT key, record FROM \(table) WHERE deleted_at IS NULL") { ($0.text(0) ?? "", $0.text(1) ?? "") }) ?? []
        }
    }

    /// How many rows a table keeps, deleted ones left out.
    public func count(_ table: String) -> Int {
        guard CopyTables.names.contains(table) else { return 0 }
        return locked { Int((try? database.scalar("SELECT count(*) FROM \(table) WHERE deleted_at IS NULL").int) ?? 0) }
    }

    /// Empties the copy, as when the records are to come from another API.
    public func clear() throws { try apply([], revision: 0, fresh: true) }

    /// Brings the copy up to the API's latest revision, page by page; a copy of another of the
    /// API's databases is read again from the start. Returns how many rows came.
    @discardableResult
    public func sync(with api: HereAPI) async throws -> Int {
        var since = revision()
        var known = databaseID()
        var cursor: String?
        var fresh = since == 0
        var count = 0
        repeat {
            let page = try await api.rows(since: since, cursor: cursor)
            if let database = page.database, let had = known, had != database, !fresh {
                // Another database: start again from nothing.
                since = 0
                cursor = nil
                fresh = true
                known = database
                continue
            }
            try apply(page.rows, revision: page.next == nil ? page.revision : since, fresh: fresh && cursor == nil, databaseID: page.database)
            count += page.rows.count
            fresh = false
            known = page.database ?? known
            cursor = page.next
            if cursor == nil { since = page.revision }
        } while cursor != nil
        return count
    }
}
