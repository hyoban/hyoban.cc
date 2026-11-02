import Foundation

// What the phone changed and hasn't published yet, kept as one JSON file under the app's own
// store, which no sync or change of API touches: entries written or changed on the phone, the
// uids of the site's entries deleted here, the places made or changed here (a new one with a
// provisional `p-<ulid>` id), and the trips changed here as collections. The originals of new
// photos and videos are files beside it, in `local/media`, until they are published.

nonisolated struct LocalChanges: Codable, Equatable, Sendable {
    var entries: [String: JSON] = [:]
    var deleted: Set<String> = []
    var places: [String: JSON] = [:]
    var collections: [String: JSON] = [:]

    var isEmpty: Bool { entries.isEmpty && deleted.isEmpty && places.isEmpty && collections.isEmpty }
}

enum LocalStore {
    static let directory = Storage.directory("local")
    static let mediaDirectory: URL = {
        let url = directory.appending(path: "media", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    private static let file = directory.appending(path: "changes.json")

    static func read() -> LocalChanges {
        guard let data = try? Data(contentsOf: file) else { return LocalChanges() }
        return (try? JSONDecoder().decode(LocalChanges.self, from: data)) ?? LocalChanges()
    }

    static func write(_ changes: LocalChanges) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? encoder.encode(changes).write(to: file, options: .atomic)
    }

    /// A file kept for a media item, by the name the entry records.
    static func mediaURL(_ name: String) -> URL { mediaDirectory.appending(path: name) }

    /// Deletes the originals no unpublished entry names any more.
    static func sweep(keeping names: Set<String>) {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: mediaDirectory.path)) ?? []
        for file in files where !names.contains(file) {
            try? FileManager.default.removeItem(at: mediaURL(file))
        }
    }
}
