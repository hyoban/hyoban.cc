import CoreLocation
import Foundation

// The data spec packages/data-spec (@hyoban/data-spec) defines: every record an entry, a moment
// and an optional point, what it holds parts by plugin (words, photos, a meal, a bill, a charge,
// a device, a subscription), a trip a collection, and public spans of the footprints. Each is
// kept as the JSON it came as and read through these views, so what this version doesn't know
// travels through untouched.

/// Who sees something.
nonisolated enum Visibility: String, Sendable {
    case `public`, `private`
}

/// An entry (`entrySchema`).
nonisolated struct Entry: Identifiable, Hashable, Sendable {
    var json: JSON

    init(_ json: JSON) { self.json = json }

    var id: String { uid }
    var uid: String { json["uid"]?.string ?? "" }
    /// A timestamp with its offset, or just the day.
    var at: String { json["at"]?.string ?? "" }
    /// The day it happened, `YYYY-MM-DD`.
    var day: String { String(at.prefix(10)) }
    var place: String? { json["place"]?.string }
    var parts: [String: JSON] { json["parts"]?.object ?? [:] }
    var visibility: Visibility? { json["visibility"]?.string.flatMap(Visibility.init) }
    var isPrivate: Bool { visibility == .private }
    var order: Int? { json["order"]?.int }
    var publishedAt: String? { json["published"]?["at"]?.string }
    var createdAt: String { json["createdAt"]?.string ?? at }
    var updatedAt: String { json["updatedAt"]?.string ?? at }
    var date: Date { Times.date(at) ?? .distantPast }

    /// A Moment: it has words (`isMomentEntry`).
    var isMoment: Bool { parts["text"] != nil }
    /// A mark: nothing but its moment and point (`isMarkEntry`).
    var isMark: Bool { parts.isEmpty }

    var text: String { parts["text"]?["text"]?.string ?? "" }
    var media: [MediaItem] { parts["media"]?["items"]?.array?.map(MediaItem.init) ?? [] }
    var food: JSON? { parts["food"] }
    var bill: JSON? { parts["bill"] }
    var charge: JSON? { parts["charge"] }
    var device: JSON? { parts["device"] }
    var subscription: JSON? { parts["subscription"] }

    /// Whether the part of [plugin] is private: the entry is, or the part says so on its own.
    func isPartPrivate(_ plugin: String) -> Bool {
        if let own = parts[plugin]?["visibility"]?.string { return own == Visibility.private.rawValue }
        if plugin == "bill" { return true }
        return isPrivate
    }

    var point: CLLocationCoordinate2D? {
        guard let lat = json["point"]?["lat"]?.double, let lng = json["point"]?["lng"]?.double else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }
}

/// A photo, a live photo or a video of a Moment (`momentMediaSchema`).
nonisolated struct MediaItem: Identifiable, Hashable, Sendable {
    var json: JSON

    init(_ json: JSON) { self.json = json }

    enum Kind: String, Sendable { case image, livePhoto = "live-photo", video }

    var id: String { uid }
    var uid: String { json["uid"]?.string ?? "" }
    var kind: Kind { json["type"]?.string.flatMap(Kind.init) ?? .image }
    var alt: String { json["alt"]?.string ?? "" }
    var width: Int? { json["width"]?.int }
    var height: Int? { json["height"]?.int }
    var takenAt: String? { json["takenAt"]?.string }
    /// Kept for the record rather than worth showing off: never a cover, a widget or a share card.
    var utility: Bool { json["utility"]?.bool == true }
    var published: JSON? { json["published"] }
    /// Where it is on this phone, a file under the app's own store, until it is published.
    var localFile: String? { json["local"]?["uri"]?.string }
    var localVideoFile: String? { json["local"]?["videoUri"]?.string }

    var aspect: CGFloat {
        guard let width, let height, width > 0, height > 0 else { return 4 / 3 }
        return CGFloat(width) / CGFloat(height)
    }
}

/// A place (`placeSchema`), by its id.
nonisolated struct Place: Hashable, Sendable {
    var json: JSON

    init(_ json: JSON) { self.json = json }

    var name: String { json["name"]?.string ?? "" }
    var latitude: Double { json["latitude"]?.double ?? 0 }
    var longitude: Double { json["longitude"]?.double ?? 0 }
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
    /// What sort of place it is, as the spec names it.
    var kind: String? { json["kind"]?.string }
    var note: String? { json["note"]?.string }

    /// Its two most particular names, `芜湖市 镜湖区`, Beijing's city not repeating its province.
    var division: String? {
        guard let division = json["division"] else { return nil }
        var names: [String] = []
        for key in ["province", "city", "district"] {
            if let name = division[key]?.string, !names.contains(name) { names.append(name) }
        }
        return names.isEmpty ? nil : names.suffix(2).joined(separator: " ")
    }

    static let kinds = ["city", "town", "region", "scenic", "food", "shopping", "stay", "transport"]
}

/// A collection (`collectionSchema`): for now always a trip.
nonisolated struct Collection: Identifiable, Hashable, Sendable {
    var json: JSON

    init(_ json: JSON) { self.json = json }

    /// What it is known by: its slug, else its uid in lowercase (`getCollectionKey`).
    var id: String { json["slug"]?.string ?? uid.lowercased() }
    var uid: String { json["uid"]?.string ?? "" }
    var title: String { json["title"]?.string ?? "" }
    var start: String { json["span"]?["start"]?.string ?? "" }
    var end: String { json["span"]?["end"]?.string ?? "" }
    var include: [String] { json["include"]?.strings ?? [] }
    var exclude: [String] { json["exclude"]?.strings ?? [] }
    var isPrivate: Bool { json["visibility"]?.string == Visibility.private.rawValue }
    /// Its covers, entry uid to media uid, best first.
    var covers: [(entry: String, media: String)] {
        json["covers"]?.array?.compactMap { cover in
            guard let entry = cover["entry"]?.string, let media = cover["media"]?.string else { return nil }
            return (entry, media)
        } ?? []
    }

    /// Whether it holds an entry (`isInCollection`): in its span, or included, and not excluded.
    func contains(_ entry: Entry) -> Bool {
        if exclude.contains(entry.uid) { return false }
        if include.contains(entry.uid) { return true }
        return Times.isWithin(entry.at, start: start, end: end)
    }

    static func == (a: Collection, b: Collection) -> Bool { a.json == b.json }
    func hash(into hasher: inout Hasher) { hasher.combine(json) }
}

/// A public span of the footprints (`publicTrackSchema`).
nonisolated struct PublicTrack: Identifiable, Hashable, Sendable {
    var json: JSON

    var id: String { json["uid"]?.string ?? "" }
    var start: String { json["start"]?.string ?? "" }
    var end: String { json["end"]?.string ?? "" }
    var collection: String? { json["collection"]?.string }
    /// Its lines, each a list of longitude and latitude.
    var lines: [[CLLocationCoordinate2D]] {
        json["segments"]?.array?.map { segment in
            segment["coordinates"]?.array?.compactMap { point in
                guard let lng = point.array?.first?.double, let lat = point.array?.dropFirst().first?.double else { return nil }
                return CLLocationCoordinate2D(latitude: lat, longitude: lng)
            } ?? []
        } ?? []
    }
}

/// Times as the spec writes them: a timestamp with its offset, or a day, which the site keeps in UTC+8.
nonisolated enum Times {
    static let siteZone = TimeZone(secondsFromGMT: 8 * 3600)!

    nonisolated(unsafe) private static let withSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = siteZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    /// The instant of a timestamp or, for a day, its start in UTC+8.
    static func date(_ value: String) -> Date? {
        if value.count == 10 { return dayFormatter.date(from: value) }
        if let date = withSeconds.date(from: value) { return date }
        // `2026-05-01T10:00+08:00`, a timestamp without its seconds.
        if value.count >= 16, value.dropFirst(16).first != ":" {
            let index = value.index(value.startIndex, offsetBy: 16)
            return withSeconds.date(from: value[..<index] + ":00" + value[index...])
        }
        return nil
    }

    /// The day of a date in UTC+8, `YYYY-MM-DD`.
    static func day(_ date: Date) -> String { dayFormatter.string(from: date) }

    /// Now as records are stamped: UTC to the second.
    static func now() -> String { stamp(Date()) }

    static func stamp(_ date: Date) -> String { withSeconds.string(from: date) }

    /// A time with the offset of the zone it was in, as a Moment's `at` is written.
    static func local(_ date: Date, zone: TimeZone = .current) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = zone
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    /// Whether a moment falls in a span whose ends are timestamps or whole days (`isWithin`).
    static func isWithin(_ at: String, start: String, end: String) -> Bool {
        let day = at.prefix(10)
        if start.count == 10 || at.count == 10 {
            if day < start.prefix(10) { return false }
        } else if let a = date(at), let s = date(start), a < s {
            return false
        }
        if end.count == 10 || at.count == 10 { return day <= end.prefix(10) }
        guard let a = date(at), let e = date(end) else { return true }
        return a <= e
    }
}

/// ULIDs, as the spec's ids for entries, media and places made on the phone.
nonisolated enum Ulid {
    private static let crockford = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    static func make(_ date: Date = Date()) -> String {
        var milliseconds = UInt64(date.timeIntervalSince1970 * 1000)
        var head = [Character](repeating: "0", count: 10)
        for index in (0..<10).reversed() {
            head[index] = crockford[Int(milliseconds % 32)]
            milliseconds /= 32
        }
        let tail = (0..<16).map { _ in crockford[Int.random(in: 0..<32)] }
        return String(head + tail)
    }
}
