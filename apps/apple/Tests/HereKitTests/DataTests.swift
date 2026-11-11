import Foundation
import Testing

@testable import HereKit

// The copy the apps read their records from, with made-up records.

private func temporaryFolder() -> URL {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("here-tests-\(UUID().uuidString)", isDirectory: true)
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    return folder
}

private let madeUpEntry = #"{"uid":"01JAAAAAAAAAAAAAAAAAAAAAAA","at":"2026-01-02T03:04:05+08:00","place":"made-up-park","parts":{"text":{"v":1,"text":"made up"},"media":{"v":1,"items":[{"uid":"01JBBBBBBBBBBBBBBBBBBBBBBB","type":"image","width":4000,"height":3000,"alt":"","published":{"key":"moments/a/b.webp","contentType":"image/webp","bytes":1,"etag":"00000000000000000000000000000000","width":4000,"height":3000,"variants":[{"key":"moments/a/b-960w.webp","contentType":"image/webp","bytes":1,"etag":"00000000000000000000000000000000","width":960,"height":720}]}}]},"future":{"v":3,"kept":true}},"createdAt":"2026-01-02T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"}"#

@Test func jsonKeepsWhatItDoesNotKnow() throws {
    let json = try JSON(parsing: madeUpEntry)
    let again = try JSON(parsing: json.text)
    #expect(again == json)
    #expect(again.at("parts.future.kept").bool == true)
    #expect(again.at("parts.media.items")[0]["width"].int == 4000)
    #expect(JSON.string("a\"b/c").text == #""a\"b/c""#)
}

@Test func anEntryReadsItsFields() throws {
    let entry = Entry(json: try JSON(parsing: madeUpEntry))
    #expect(entry.day == "2026-01-02")
    #expect(entry.wallTime == "03:04")
    #expect(entry.text == "made up")
    #expect(entry.media.count == 1)
    #expect(entry.isMoment)
    #expect(entry.plugins == ["future"])
    #expect(entry.visibility == .public)
    #expect(entry.date == Times.date("2026-01-01T19:04:05Z"))
}

@Test func timesReadEveryShapeTheSpecAllows() {
    #expect(Times.date("2026-01-02") == Times.day("2026-01-02"))
    #expect(Times.date("2026-01-02T03:04+08:00") == Times.date("2026-01-02T03:04:00+08:00"))
    #expect(Times.adding(1, to: "2025-12-31") == "2026-01-01")
    #expect(Times.adding(-1, to: "2024-03-01") == "2024-02-29")
}

@Test func mediaUrlsPickTheSmallestWideEnoughVariant() throws {
    let entry = Entry(json: try JSON(parsing: madeUpEntry))
    let urls = MediaURLs(origin: "https://images.example/")
    #expect(urls.still(entry.media[0], width: 600)?.absoluteString == "https://images.example/moments/a/b-960w.webp")
    // The original is wider than the site serves, so the variant stays the largest.
    #expect(urls.still(entry.media[0], width: 3000)?.absoluteString == "https://images.example/moments/a/b-960w.webp")
    #expect(MediaURLs.size(own: 1200, wanted: 1000) == 1200)
}

@Test func theCopyKeepsRowsAndForgetsDeletedOnes() throws {
    let folder = temporaryFolder()
    let copy = try CopyStore(file: folder.appendingPathComponent("copy.sqlite"))
    #expect(copy.revision() == 0)
    try copy.apply([
        CopyRow(table: "entries", key: "e1", rev: 1, deletedAt: nil, record: madeUpEntry),
        CopyRow(table: "places", key: "made-up-park", rev: 1, deletedAt: nil, record: #"{"name":"Park","latitude":1,"longitude":2}"#),
    ], revision: 1, databaseID: 7)
    #expect(copy.revision() == 1)
    #expect(copy.databaseID() == 7)
    #expect(copy.live("entries").count == 1)

    try copy.apply([CopyRow(table: "entries", key: "e1", rev: 2, deletedAt: "2026-01-03T00:00:00Z", record: madeUpEntry)], revision: 2)
    #expect(copy.live("entries").isEmpty)
    #expect(copy.count("places") == 1)

    // Opening it again keeps it: the tables haven't changed.
    let reopened = try CopyStore(file: folder.appendingPathComponent("copy.sqlite"))
    #expect(reopened.revision() == 2)
}

@MainActor
@Test func theStoreReadsTheCopy() throws {
    let folder = temporaryFolder()
    let store = try HereStore(folder: folder)
    try store.copy.apply([
        CopyRow(table: "entries", key: "01JAAAAAAAAAAAAAAAAAAAAAAA", rev: 1, deletedAt: nil, record: madeUpEntry),
        CopyRow(table: "collections", key: "01JCCCCCCCCCCCCCCCCCCCCCCC", rev: 1, deletedAt: nil, record: #"{"uid":"01JCCCCCCCCCCCCCCCCCCCCCCC","kind":"trip","title":"Made up","span":{"start":"2026-01-01","end":"2026-01-03"},"include":[],"exclude":[],"covers":[],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"#),
    ], revision: 1)
    store.reload()
    #expect(store.timeline.count == 1)
    #expect(store.entries(of: store.trips[0]).count == 1)
    #expect(store.entries(at: "made-up-park").count == 1)
}
