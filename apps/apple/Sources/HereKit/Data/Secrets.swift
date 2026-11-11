import Foundation

/// Secrets (the API's token, the WebDAV password) kept as files only the owner can read, in a
/// folder of the app's own, as the Compose desktop app keeps them: an app signed ad hoc on each
/// build would otherwise ask for the Keychain again every time.
public struct Secrets: Sendable {
    public let folder: URL

    public init(folder: URL) {
        self.folder = folder
    }

    public func read(_ name: String) -> String {
        (try? String(contentsOf: folder.appendingPathComponent(name), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    /// Keeps [value], or forgets it when empty.
    public func write(_ name: String, _ value: String) {
        let file = folder.appendingPathComponent(name)
        guard !value.isEmpty else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        FileManager.default.createFile(atPath: file.path, contents: Data(value.utf8), attributes: [.posixPermissions: 0o600])
    }
}
