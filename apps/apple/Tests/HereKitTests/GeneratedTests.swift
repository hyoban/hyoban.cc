import Foundation
import SQLite3
import SwiftUI
import Testing

@testable import HereKit

// The generated code is checked against its sources by `vp test`; these check that it does what
// it says on the platform: the tables make a database, the icons read as paths.

@Test func theCopyTablesMakeADatabase() throws {
    var db: OpaquePointer?
    #expect(sqlite3_open(":memory:", &db) == SQLITE_OK)
    defer { sqlite3_close(db) }

    for statement in CopyTables.statements {
        var error: UnsafeMutablePointer<CChar>?
        let result = sqlite3_exec(db, statement, nil, nil, &error)
        #expect(result == SQLITE_OK, "\(error.map { String(cString: $0) } ?? "") in \(statement)")
    }

    var query: OpaquePointer?
    sqlite3_prepare_v2(db, "SELECT name FROM sqlite_master WHERE type = 'table'", -1, &query, nil)
    defer { sqlite3_finalize(query) }
    var tables: Set<String> = []
    while sqlite3_step(query) == SQLITE_ROW {
        tables.insert(String(cString: sqlite3_column_text(query, 0)))
    }
    #expect(tables == Set(CopyTables.names))
}

@Test func theWorkedOutColumnsReadTheRecord() throws {
    var db: OpaquePointer?
    sqlite3_open(":memory:", &db)
    defer { sqlite3_close(db) }
    for statement in CopyTables.statements { sqlite3_exec(db, statement, nil, nil, nil) }

    let record = #"{"uid":"e1","at":"2026-01-02T03:04:05+08:00","parts":{"text":{"text":"made up"}}}"#
    sqlite3_exec(db, "INSERT INTO entries (key, rev, record) VALUES ('e1', 1, '\(record)')", nil, nil, nil)
    var query: OpaquePointer?
    sqlite3_prepare_v2(db, "SELECT day, kind FROM entries WHERE key = 'e1'", -1, &query, nil)
    defer { sqlite3_finalize(query) }
    #expect(sqlite3_step(query) == SQLITE_ROW)
    #expect(String(cString: sqlite3_column_text(query, 0)) == "2026-01-02")
    #expect(String(cString: sqlite3_column_text(query, 1)) == "moment")
}

@Test func everyModuleOwnsSomething() {
    for module in Module.allCases {
        #expect(!module.plugins.isEmpty || !module.tables.isEmpty, "\(module)")
        #expect(Module(rawValue: module.rawValue) == module)
    }
}

@Test func theIconsReadAsPathsOnTheGrid() {
    let grid = CGRect(x: 0, y: 0, width: 24, height: 24).insetBy(dx: -1, dy: -1)
    #expect(AppIcon.all.count == Set(AppIcon.all.map(\.name)).count)
    for icon in AppIcon.all {
        let bounds = icon.strokes.boundingRect.union(icon.fills.boundingRect).union(icon.holed.boundingRect)
        #expect(!bounds.isNull && bounds.width + bounds.height > 0, "\(icon.name)")
        #expect(grid.contains(bounds), "\(icon.name) \(bounds)")
    }
    #expect(AppIcon.chevronLeft.mirror && !AppIcon.plus.mirror)
}

@Test func theYearColoursAreSix() {
    #expect(Tokens.yearColors.count == 6)
    #expect(Space.gutter(width: 400) < Space.gutter(width: 800))
}
