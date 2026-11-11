import Foundation

// The records of `@hyoban/data-spec/entry` as the apps read them: each keeps its whole JSON in
// `json`, so what this app doesn't know travels through untouched, and reads the fields it uses
// from it once. Writing changes `json` and reads the record again.

public enum Audience: String, Sendable, CaseIterable {
    case `public`, `private`
}

/// Where something happened, and how that is known.
public struct EntryPoint: Sendable, Hashable {
    public var lat: Double
    public var lng: Double
    public var accuracy: Double?
    public var from: String

    public init(lat: Double, lng: Double, accuracy: Double? = nil, from: String = "manual") {
        self.lat = lat
        self.lng = lng
        self.accuracy = accuracy
        self.from = from
    }

    init?(_ json: JSON) {
        guard let lat = json["lat"].double, let lng = json["lng"].double else { return nil }
        self.init(lat: lat, lng: lng, accuracy: json["accuracy"].double, from: json["from"].string ?? "manual")
    }

    public var json: JSON {
        var json: JSON = ["lat": .number(lat), "lng": .number(lng), "from": .string(from)]
        if let accuracy { json["accuracy"] = .number(accuracy) }
        return json
    }
}

/// One photo, live photo or video of an entry's media part.
public struct MediaItem: Sendable, Hashable, Identifiable {
    public enum Kind: String, Sendable { case image, livePhoto = "live-photo", video }

    public var json: JSON
    public let uid: String
    public let kind: Kind
    public let width: Int?
    public let height: Int?
    public let alt: String
    public let takenAt: String?
    public let utility: Bool
    public let point: EntryPoint?

    public var id: String { uid }

    public init(json: JSON) {
        self.json = json
        uid = json["uid"].string ?? ""
        kind = Kind(rawValue: json["type"].string ?? "") ?? .image
        width = json["width"].int
        height = json["height"].int
        alt = json["alt"].string ?? ""
        takenAt = json["takenAt"].string
        utility = json["utility"].bool ?? false
        point = EntryPoint(json["point"])
    }

}

/// A record: a moment and an optional point with parts by plugin.
public struct Entry: Sendable, Hashable, Identifiable {
    public var json: JSON
    public let uid: String
    /// When, as written: a timestamp with its offset, or just the day.
    public let at: String
    /// Its day, `2025-03-01`.
    public let day: String
    public let date: Date
    public let end: String?
    public let point: EntryPoint?
    public let place: String?
    public let visibility: Audience
    public let text: String?
    public let media: [MediaItem]
    public let order: Int?
    public let publishedAt: String?
    public let createdAt: String?
    public let updatedAt: String?

    public var id: String { uid }

    public init(json: JSON) {
        self.json = json
        uid = json["uid"].string ?? ""
        at = json["at"].string ?? ""
        day = String(at.prefix(10))
        date = Times.date(at) ?? .distantPast
        end = json["end"].string
        point = EntryPoint(json["point"])
        place = json["place"].string
        visibility = Audience(rawValue: json["visibility"].string ?? "") ?? .public
        text = json.at("parts.text.text").string
        media = json.at("parts.media.items").array?.map(MediaItem.init(json:)) ?? []
        order = json["order"].int
        publishedAt = json.at("published.at").string
        createdAt = json["createdAt"].string
        updatedAt = json["updatedAt"].string
    }

    public var parts: [String: JSON] { json["parts"].object ?? [:] }

    /// A part by its plugin, `bill`, or null.
    public func part(_ plugin: String) -> JSON { json["parts"][plugin] }

    /// Something the owner wrote: words or photos.
    public var isMoment: Bool { text != nil || !media.isEmpty }

    /// The plugins of the parts it holds, besides words and photos.
    public var plugins: [String] { parts.keys.filter { $0 != "text" && $0 != "media" }.sorted() }

    /// Its time of day as written, `14:05`, or nil for a day alone.
    public var wallTime: String? { at.count >= 16 ? String(at.dropFirst(11).prefix(5)) : nil }

    /// Sorting entries: by time, a day alone before its times, then by their order.
    public static func chronological(_ a: Entry, _ b: Entry) -> Bool {
        if a.date != b.date { return a.date < b.date }
        if (a.order ?? 0) != (b.order ?? 0) { return (a.order ?? 0) < (b.order ?? 0) }
        return a.uid < b.uid
    }
}

/// A named place, by its id.
public struct Place: Sendable, Hashable, Identifiable {
    public var json: JSON
    public let id: String
    public let name: String
    public let latitude: Double
    public let longitude: Double
    public let kind: String?
    public let province: String?
    public let city: String?
    public let district: String?
    public let note: String?

    public init(id: String, json: JSON) {
        self.id = id
        self.json = json
        name = json["name"].string ?? id
        latitude = json["latitude"].double ?? 0
        longitude = json["longitude"].double ?? 0
        kind = json["kind"].string
        province = json.at("division.province").string
        city = json.at("division.city").string
        district = json.at("division.district").string
        note = json["note"].string
    }

    /// Where it is, as a line: `Nanjing Xuanwu District`.
    public var area: String {
        [city ?? province, district].compactMap { $0 }.joined(separator: " ")
    }
}

/// A trip: a collection that takes the entries in its span.
public struct Trip: Sendable, Hashable, Identifiable {
    public var json: JSON
    public let uid: String
    public let slug: String?
    public let title: String
    public let start: String
    public let end: String
    public let startDay: String
    public let endDay: String
    public let include: Set<String>
    public let exclude: Set<String>
    public let covers: [(entry: String, media: String)]
    public let visibility: Audience
    public let note: String?

    public var id: String { uid }

    public init(json: JSON) {
        self.json = json
        uid = json["uid"].string ?? ""
        slug = json["slug"].string
        title = json["title"].string ?? ""
        start = json.at("span.start").string ?? ""
        end = json.at("span.end").string ?? ""
        startDay = String(start.prefix(10))
        endDay = String(end.prefix(10))
        include = Set(json["include"].array?.compactMap(\.string) ?? [])
        exclude = Set(json["exclude"].array?.compactMap(\.string) ?? [])
        covers = json["covers"].array?.compactMap { cover in
            guard let entry = cover["entry"].string, let media = cover["media"].string else { return nil }
            return (entry, media)
        } ?? []
        visibility = Audience(rawValue: json["visibility"].string ?? "") ?? .public
        note = json["note"].string
    }

    public static func == (a: Trip, b: Trip) -> Bool { a.json == b.json }
    public func hash(into hasher: inout Hasher) { hasher.combine(json) }

    /// Whether [entry] belongs to it: in its span and not left out, or taken in.
    public func holds(_ entry: Entry) -> Bool {
        if exclude.contains(entry.uid) { return false }
        if include.contains(entry.uid) { return true }
        return entry.day >= startDay && entry.day <= endDay
    }

    /// How many days it spans, the first and last included.
    public var days: Int {
        guard let a = Times.day(startDay), let b = Times.day(endDay) else { return 1 }
        return (Calendar.gregorian.dateComponents([.day], from: a, to: b).day ?? 0) + 1
    }

    public var year: Int { Int(startDay.prefix(4)) ?? 0 }
}

public extension Calendar {
    static let gregorian: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }()
}

/// The spec's times: ISO timestamps with their offsets, or days alone.
public enum Times {
    nonisolated(unsafe) private static let withSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let lock = NSLock()

    /// A timestamp or a day as an instant; a day alone is its midnight in UTC.
    public static func date(_ text: String) -> Date? {
        if text.count == 10 { return day(text) }
        lock.lock()
        defer { lock.unlock() }
        if let date = withSeconds.date(from: text) ?? fractional.date(from: text) { return date }
        // Without seconds: `2025-03-01T12:30+08:00`.
        if text.count >= 16, text[text.index(text.startIndex, offsetBy: 16)...].first != ":" {
            var padded = text
            padded.insert(contentsOf: ":00", at: padded.index(padded.startIndex, offsetBy: 16))
            return withSeconds.date(from: padded)
        }
        return nil
    }

    /// A day, `2025-03-01`, as its midnight in UTC.
    public static func day(_ text: String) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar.gregorian.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// A day's key from a date in UTC, as `day(_:)` makes them.
    public static func key(_ date: Date) -> String {
        let parts = Calendar.gregorian.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The day [days] after [key].
    public static func adding(_ days: Int, to key: String) -> String {
        guard let date = day(key), let moved = Calendar.gregorian.date(byAdding: .day, value: days, to: date) else { return key }
        return self.key(moved)
    }

    /// Today on this computer, as a key.
    public static func today(_ now: Date = Date()) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}
