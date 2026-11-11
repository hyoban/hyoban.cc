import Foundation
import SQLite3

/// A SQLite database, opened from a file, used from one thread or actor at a time.
public final class SQLiteDatabase: @unchecked Sendable {
    public struct Failure: Error, CustomStringConvertible {
        public let description: String
    }

    private var handle: OpaquePointer?

    /// Opens [path], made when it isn't there unless [readOnly].
    public init(path: String, readOnly: Bool = false) throws {
        let flags = readOnly ? SQLITE_OPEN_READONLY : (SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE)
        if sqlite3_open_v2(path, &handle, flags | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "can't open \(path)"
            sqlite3_close(handle)
            throw Failure(description: message)
        }
        if !readOnly { try? execute("PRAGMA journal_mode = WAL") }
    }

    deinit { sqlite3_close(handle) }

    private var lastError: Failure { Failure(description: String(cString: sqlite3_errmsg(handle))) }

    /// Runs statements that read nothing back.
    public func execute(_ sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(handle, sql, nil, nil, &message) != SQLITE_OK {
            let text = message.map { String(cString: $0) } ?? "failed: \(sql)"
            sqlite3_free(message)
            throw Failure(description: text)
        }
    }

    /// Runs [sql] once with [values] bound in order.
    public func run(_ sql: String, _ values: [SQLiteValue] = []) throws {
        let statement = try prepare(sql)
        try statement.bind(values)
        _ = try statement.step()
    }

    /// Every row [sql] reads, each handed to [row].
    public func query<T>(_ sql: String, _ values: [SQLiteValue] = [], row: (SQLiteStatement) throws -> T) throws -> [T] {
        let statement = try prepare(sql)
        try statement.bind(values)
        var rows: [T] = []
        while try statement.step() { rows.append(try row(statement)) }
        return rows
    }

    /// The first column of the first row [sql] reads.
    public func scalar(_ sql: String, _ values: [SQLiteValue] = []) throws -> SQLiteValue {
        let statement = try prepare(sql)
        try statement.bind(values)
        return try statement.step() ? statement.value(0) : .null
    }

    public func prepare(_ sql: String) throws -> SQLiteStatement {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw lastError }
        return SQLiteStatement(statement: statement, database: self)
    }

    /// Runs [block] in one transaction, rolled back if it throws.
    public func transaction<T>(_ block: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try block()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    fileprivate func failure() -> Failure { lastError }
}

public enum SQLiteValue: Sendable, Hashable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)

    public var string: String? {
        switch self {
        case let .text(value): value
        case let .integer(value): String(value)
        case let .real(value): String(value)
        case .null: nil
        }
    }

    public var int: Int64? {
        switch self {
        case let .integer(value): value
        case let .real(value): Int64(value)
        case let .text(value): Int64(value)
        case .null: nil
        }
    }

    public var double: Double? {
        switch self {
        case let .integer(value): Double(value)
        case let .real(value): value
        case let .text(value): Double(value)
        case .null: nil
        }
    }
}

extension SQLiteValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .text(value) }
    public init(integerLiteral value: Int64) { self = .integer(value) }
    public init(nilLiteral: ()) { self = .null }
    public init(_ value: String?) { self = value.map(SQLiteValue.text) ?? .null }
    public init(_ value: Int64?) { self = value.map(SQLiteValue.integer) ?? .null }
    public init(_ value: Double?) { self = value.map(SQLiteValue.real) ?? .null }
}

public final class SQLiteStatement {
    private let statement: OpaquePointer
    private let database: SQLiteDatabase

    fileprivate init(statement: OpaquePointer, database: SQLiteDatabase) {
        self.statement = statement
        self.database = database
    }

    deinit { sqlite3_finalize(statement) }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    public func bind(_ values: [SQLiteValue]) throws {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32 = switch value {
            case .null: sqlite3_bind_null(statement, index)
            case let .integer(value): sqlite3_bind_int64(statement, index, value)
            case let .real(value): sqlite3_bind_double(statement, index, value)
            case let .text(value): sqlite3_bind_text(statement, index, value, -1, Self.transient)
            }
            if result != SQLITE_OK { throw database.failure() }
        }
    }

    /// Steps to the next row: true while there is one.
    public func step() throws -> Bool {
        switch sqlite3_step(statement) {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        default: throw database.failure()
        }
    }

    public func reset() {
        sqlite3_reset(statement)
        sqlite3_clear_bindings(statement)
    }

    public func value(_ column: Int32) -> SQLiteValue {
        switch sqlite3_column_type(statement, column) {
        case SQLITE_INTEGER: .integer(sqlite3_column_int64(statement, column))
        case SQLITE_FLOAT: .real(sqlite3_column_double(statement, column))
        case SQLITE_NULL: .null
        default: .text(String(cString: sqlite3_column_text(statement, column)))
        }
    }

    public func text(_ column: Int32) -> String? { value(column).string }
    public func int(_ column: Int32) -> Int64? { value(column).int }
    public func double(_ column: Int32) -> Double? { value(column).double }
}
