import Foundation
import Security

// The API (apps/api in the repository), with the owner's token, which every request needs:
// every row D1 keeps a page at a time, the trips' routes, a photo's original, then everything
// the phone changed as one change set. The app names no API of its own: nothing is asked of
// one until both its address and its token are set in 同步与来源.

/// A page of the rows (`GET /v2/rows`).
nonisolated struct RowsPage: Decodable, Sendable {
    var revision: Int64
    var rows: [Row]
    var next: String?
    var database: Int64?

    struct Row: Decodable, Sendable {
        var table: String
        var key: String
        var rev: Int64
        var deletedAt: String?
        var record: JSON

        var copyRow: CopyRow { CopyRow(table: table, key: key, rev: rev, deletedAt: deletedAt, record: record.text) }
    }
}

/// A trip's route as the site's overview draws it: drives joined into one stretch, other legs straight.
nonisolated struct RouteStretch: Codable, Hashable, Sendable {
    var line: [[Double]]
    var straight: Bool
}

struct APIError: LocalizedError {
    var status: Int
    var message: String
    var errorDescription: String? { message }
}

struct NoAPIError: LocalizedError {
    var errorDescription: String? { String(localized: "error.noApi") }
}

/// What a change set did: the id each place sent is known by, the entries as published, the uids deleted.
nonisolated struct AppliedChanges: Decodable, Sendable {
    var places: [String: String]
    var entries: [JSON]
    var deleted: [String]
    var revision: Int64?
}

/// An original of a media item: a photo, a live photo's still, or a video or a live photo's motion.
nonisolated enum UploadPart: String, Sendable { case image, video }

nonisolated struct API: Sendable {
    var base: URL
    var token: String

    /// The API the settings name, or nil until both its address and token are set.
    @MainActor static var current: API? {
        guard let base = Settings.apiURL, let token = Settings.token, !token.isEmpty else { return nil }
        return API(base: base, token: token)
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.waitsForConnectivity = true
        return URLSession(configuration: configuration)
    }()

    private func request(_ path: String, method: String = "GET") -> URLRequest {
        var request = URLRequest(url: URL(string: base.absoluteString.trimmingSuffix("/") + path)!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private func send(_ request: URLRequest, body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        var request = request
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await Self.session.data(for: request)
        let http = response as! HTTPURLResponse
        guard (200..<300).contains(http.statusCode) || http.statusCode == 304 || http.statusCode == 404 else {
            let said = (try? JSON.parse(data))?["error"]?.string
            throw APIError(status: http.statusCode, message: said ?? "\(request.httpMethod ?? "GET") \(request.url?.path ?? "") answered \(http.statusCode)")
        }
        return (data, http)
    }

    /// `GET /` : the API's name and revision.
    func info() async throws -> JSON {
        try JSON.parse(try await send(request("/")).0)
    }

    /// The rows that changed after revision [since], or from [cursor] when it continues a read.
    func rows(since: Int64, cursor: String?) async throws -> RowsPage {
        let path = cursor.map { "/v2/rows?cursor=\($0)&limit=500" } ?? "/v2/rows?since=\(since)&limit=500"
        return try JSONDecoder().decode(RowsPage.self, from: try await send(request(path)).0)
    }

    /// Each published trip's route by its key, or nil when it hasn't changed since [etag].
    func routes(etag: String?) async throws -> (routes: [String: [RouteStretch]], etag: String?)? {
        var request = request("/v2/routes")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await send(request)
        if response.statusCode == 304 { return nil }
        struct Answer: Decodable { var trips: [String: [RouteStretch]] }
        return (try JSONDecoder().decode(Answer.self, from: data).trips, response.value(forHTTPHeaderField: "ETag"))
    }

    /// The MD5 of the original kept for a media item, or nil when none is.
    func uploaded(entry: String, media: String, part: UploadPart) async throws -> String? {
        let (_, response) = try await send(request("/v2/entries/\(entry)/media/\(media)/\(part.rawValue)", method: "HEAD"))
        if response.statusCode == 404 { return nil }
        return response.value(forHTTPHeaderField: "ETag")?.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }

    /// Keeps an original of a media item until its entry publishes.
    func upload(entry: String, media: String, part: UploadPart, file: URL, contentType: String) async throws {
        var request = request("/v2/entries/\(entry)/media/\(media)/\(part.rawValue)", method: "PUT")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120
        let (data, response) = try await Self.session.upload(for: request, fromFile: file)
        let status = (response as! HTTPURLResponse).statusCode
        guard (200..<300).contains(status) else {
            throw APIError(status: status, message: (try? JSON.parse(data))?["error"]?.string ?? "PUT answered \(status)")
        }
    }

    /// Applies a change set in one write (`POST /v2/changes`); the API takes all of it or none.
    func applyChanges(_ changes: JSON) async throws -> AppliedChanges {
        var request = request("/v2/changes", method: "POST")
        request.timeoutInterval = 180
        let (data, _) = try await send(request, body: changes.data)
        return try JSONDecoder().decode(AppliedChanges.self, from: data)
    }
}

nonisolated extension String {
    func trimmingSuffix(_ suffix: String) -> String {
        hasSuffix(suffix) ? String(dropLast(suffix.count)) : self
    }
}

/// What is set in 同步与来源: the API's address, the media's origin, and the token, kept in the Keychain.
enum Settings {
    private static let defaults = UserDefaults.standard

    static var apiURL: URL? {
        get { defaults.string(forKey: "api").flatMap { $0.isEmpty ? nil : URL(string: $0) } }
        set { defaults.set(newValue?.absoluteString, forKey: "api") }
    }

    /// Where published photos and videos are served from, the API's bucket.
    static var mediaOrigin: String {
        get { defaults.string(forKey: "media") ?? "" }
        set { defaults.set(newValue, forKey: "media") }
    }

    static var token: String? {
        get { Keychain.read("api-token") }
        set { if let newValue, !newValue.isEmpty { Keychain.write("api-token", newValue) } else { Keychain.delete("api-token") } }
    }
}

/// A secret kept on this device only, out of backups and other devices.
enum Keychain {
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "cc.hyoban.moments", kSecAttrAccount as String: key]
    }

    static func read(_ key: String) -> String? {
        var query = query(key)
        query[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    static func write(_ key: String, _ value: String) {
        delete(key)
        var query = query(key)
        query[kSecValueData as String] = Data(value.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    static func delete(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}
