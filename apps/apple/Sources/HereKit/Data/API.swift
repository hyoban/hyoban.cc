import Foundation

/// The API (apps/api in the repository), with the owner's token, which every request needs.
public struct HereAPI: Sendable {
    public struct Failure: Error, LocalizedError {
        public let status: Int
        public let message: String
        public var errorDescription: String? { message }
    }

    /// A page of `GET /v2/rows`: every row that changed after a revision, deleted ones too.
    public struct RowsPage: Sendable {
        public let revision: Int64
        public let rows: [CopyRow]
        public let next: String?
        public let database: Int64?
    }

    /// What the API did with a change set.
    public let baseURL: URL
    public let token: String
    public let session: URLSession

    public init(baseURL: URL, token: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
    }

    private func request(_ path: String, method: String = "GET") -> URLRequest {
        var request = URLRequest(url: URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private func send(_ request: URLRequest, body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = if let body { try await session.upload(for: request, from: body) } else { try await session.data(for: request) }
        guard let response = response as? HTTPURLResponse else { throw Failure(status: 0, message: "No answer") }
        guard (200 ..< 300).contains(response.statusCode) else {
            let said = (try? JSON(data: data))?["error"].string
            throw Failure(status: response.statusCode, message: said ?? "\(request.httpMethod ?? "GET") \(request.url?.path ?? "") answered \(response.statusCode)")
        }
        return (data, response)
    }

    private func get(_ path: String) async throws -> JSON {
        try await JSON(data: send(request(path)).0)
    }

    /// What the API says of itself: its name, revision and where its media are served from.
    public func site() async throws -> (name: String, revision: Int64, assets: String?) {
        let json = try await get("/")
        return (json["name"].string ?? "", Int64(json["revision"].double ?? 0), json["assets"].string)
    }

    /// The rows changed after [since], or from [cursor] when it continues a read.
    public func rows(since: Int64, cursor: String?) async throws -> RowsPage {
        let path = cursor.map { "/v2/rows?cursor=\($0.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? $0)&limit=500" } ?? "/v2/rows?since=\(since)&limit=500"
        let json = try await get(path)
        let rows = json["rows"].array?.compactMap { row -> CopyRow? in
            guard let table = row["table"].string, let key = row["key"].string else { return nil }
            return CopyRow(table: table, key: key, rev: Int64(row["rev"].double ?? 0), deletedAt: row["deletedAt"].string, record: row["record"].text)
        } ?? []
        return RowsPage(revision: Int64(json["revision"].double ?? 0), rows: rows, next: json["next"].string, database: json["database"].double.map(Int64.init))
    }

    /// The published trips' routes, `{ revision, trips }`.
    public func routes() async throws -> JSON { try await get("/v2/routes") }
}
