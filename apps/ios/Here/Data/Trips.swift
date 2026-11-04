import CoreLocation
import Foundation

// Trips as the site reads them: a collection of the entries in its days, or taken in from outside
// them; its Moments are its entries with words, its waypoints the marks among them with a place.
// A Moment belongs to the first trip that holds it. The stops are the Moments' places and the
// waypoints, in the order they happened, the same place twice in a row one stop.

nonisolated struct Trip: Identifiable, Hashable, Sendable {
    var collection: Collection
    /// Its Moments, oldest first.
    var moments: [Entry]
    var stops: [Stop]

    var id: String { collection.id }
    var title: String { collection.title }
    var start: String { String(collection.start.prefix(10)) }
    var end: String { String(collection.end.prefix(10)) }
    var isPrivate: Bool { collection.isPrivate }

    /// How many days it spans.
    var dayCount: Int {
        guard let a = Times.date(start), let b = Times.date(end) else { return 1 }
        return max(1, Int((b.timeIntervalSince(a) / 86400).rounded()) + 1)
    }

    /// Whether today falls in it, and which of its days that is.
    func dayToday(_ today: String = Times.day(Date())) -> Int? {
        guard today >= start, today <= end, let a = Times.date(start), let b = Times.date(today) else { return nil }
        return Int((b.timeIntervalSince(a) / 86400).rounded()) + 1
    }

    /// Its Moments by day, `YYYY-MM-DD`, in order.
    var days: [(day: String, moments: [Entry])] {
        var out: [(String, [Entry])] = []
        for moment in moments {
            if out.last?.0 == moment.day { out[out.count - 1].1.append(moment) } else { out.append((moment.day, [moment])) }
        }
        return out
    }

    /// The photos to show for it: its chosen covers, else its Moments' first showcase media.
    func covers(limit: Int = 3) -> [(entry: Entry, media: MediaItem)] {
        let byUid = Dictionary(moments.map { ($0.uid, $0) }, uniquingKeysWith: { a, _ in a })
        let chosen: [(entry: Entry, media: MediaItem)] = collection.covers.compactMap { cover in
            guard let entry = byUid[cover.entry], let media = entry.media.first(where: { $0.uid == cover.media }) else { return nil }
            return (entry, media)
        }
        if !chosen.isEmpty { return Array(chosen.prefix(limit)) }
        return Array(moments.compactMap { entry in entry.media.first(where: { !$0.utility }).map { (entry, $0) } }.prefix(limit))
    }

    static func == (a: Trip, b: Trip) -> Bool { a.collection == b.collection && a.moments == b.moments }
    func hash(into hasher: inout Hasher) { hasher.combine(collection) }
}

/// One stop on a trip's map: a place, and when the trip reached it.
nonisolated struct Stop: Hashable, Sendable {
    var placeId: String
    var place: Place
    var at: Date
}

enum Trips {
    /// The trips worked out from the collections and the entries, newest first.
    static func make(collections: [Collection], entries: [Entry], places: [String: Place]) -> [Trip] {
        let ordered = collections.sorted { ($0.start, $0.id) < ($1.start, $1.id) }
        var momentsOf: [String: [Entry]] = [:]
        var marksOf: [String: [Entry]] = [:]
        for entry in entries {
            if entry.isMoment {
                // The first trip holding it, one that takes it in by name before one whose days do.
                let trip = ordered.first(where: { $0.include.contains(entry.uid) && !$0.exclude.contains(entry.uid) })
                    ?? ordered.first(where: { $0.contains(entry) })
                if let trip { momentsOf[trip.id, default: []].append(entry) }
            } else if entry.isMark, entry.place != nil {
                for trip in ordered where trip.contains(entry) { marksOf[trip.id, default: []].append(entry) }
            }
        }
        return ordered.reversed().map { collection in
            let moments = (momentsOf[collection.id] ?? []).sorted { ($0.date, $0.uid) < ($1.date, $1.uid) }
            let marks = (marksOf[collection.id] ?? []).sorted { ($0.order ?? 0, $0.at) < ($1.order ?? 0, $1.at) }
            return Trip(collection: collection, moments: moments, stops: stops(moments: moments, marks: marks, start: collection.start, places: places))
        }
    }

    private static func stops(moments: [Entry], marks: [Entry], start: String, places: [String: Place]) -> [Stop] {
        var events: [(Date, Int, String)] = []
        var order = 0
        for moment in moments {
            if let place = moment.place { events.append((moment.date, order, place)); order += 1 }
        }
        // A waypoint on a day of its own follows the one before it on that day.
        var previous = Times.date(start) ?? .distantPast
        for mark in marks {
            guard let place = mark.place else { continue }
            let at = mark.at.count == 10 && Times.day(previous) == mark.at ? previous : mark.date
            previous = at
            events.append((at, order, place))
            order += 1
        }
        var out: [Stop] = []
        for (at, _, id) in events.sorted(by: { ($0.0, $0.1) < ($1.0, $1.1) }) {
            guard let place = places[id], out.last?.placeId != id else { continue }
            out.append(Stop(placeId: id, place: place, at: at))
        }
        return out
    }

    /// The trip a Moment belongs to.
    static func of(_ entry: Entry, in trips: [Trip]) -> Trip? {
        trips.first { trip in trip.moments.contains { $0.uid == entry.uid } }
    }
}

/// The site's colours for the years places were first visited in, so a place is the same colour on
/// the phone as on the site's map: years in order from the earliest, past the palette's end starting it over.
enum Years {
    static func colors(_ years: some Sequence<Int>) -> [Int: Int] {
        Dictionary(uniqueKeysWithValues: Set(years).sorted().enumerated().map { ($1, $0 % Tokens.yearColors.count) })
    }

    /// The year of each place's first visit.
    static func firstVisits(_ entries: [Entry]) -> [String: Int] {
        var out: [String: Int] = [:]
        for entry in entries {
            guard let place = entry.place, let year = Int(entry.at.prefix(4)) else { continue }
            out[place] = min(out[place] ?? year, year)
        }
        return out
    }
}
