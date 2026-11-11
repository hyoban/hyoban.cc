import CoreLocation
import HereKit
import MapKit
import SwiftUI

// What the map pages draw, worked out from the store once per change: each place with how
// often and since when it was been to, each trip with its route, and the years among them.

/// Distances and bounds on the globe.
enum Geo {
    /// Metres between two points along the globe.
    static func metres(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let lat = (b.latitude - a.latitude) * .pi / 180
        let lng = (b.longitude - a.longitude) * .pi / 180
        let h = pow(sin(lat / 2), 2) + cos(a.latitude * .pi / 180) * cos(b.latitude * .pi / 180) * pow(sin(lng / 2), 2)
        return 6_371_000 * 2 * asin(sqrt(min(max(h, 0), 1)))
    }

    /// The length of a line through [points].
    static func length(_ points: [CLLocationCoordinate2D]) -> Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + metres($1.0, $1.1) }
    }

    /// The map rect holding [points], with a margin around it; nil for none.
    static func rect(_ points: [CLLocationCoordinate2D], margin: Double = 0.18, least: Double = 1800) -> MKMapRect? {
        guard !points.isEmpty else { return nil }
        var rect = MKMapRect.null
        for point in points {
            let mapPoint = MKMapPoint(point)
            rect = rect.union(MKMapRect(x: mapPoint.x, y: mapPoint.y, width: 0, height: 0))
        }
        // Never closer than a few streets around a single place.
        let side = max(rect.width, rect.height, least * MKMapPointsPerMeterAtLatitude(points[0].latitude))
        let width = max(rect.width, side) * (1 + margin * 2)
        let height = max(rect.height, side * 0.6) * (1 + margin * 2)
        return MKMapRect(x: rect.midX - width / 2, y: rect.midY - height / 2, width: width, height: height)
    }

    /// A camera position showing [points], or nil for none.
    static func position(_ points: [CLLocationCoordinate2D], margin: Double = 0.18) -> MapCameraPosition? {
        rect(points, margin: margin).map { .rect($0) }
    }
}

extension CLLocationCoordinate2D {
    init(_ point: EntryPoint) { self.init(latitude: point.lat, longitude: point.lng) }
}

extension Place {
    /// Where the map draws it, shifted onto the mainland's basemap (`ChinaShift`).
    @MainActor
    var coordinate: CLLocationCoordinate2D { ChinaShift.onMap(CLLocationCoordinate2D(latitude: latitude, longitude: longitude)) }
}

/// A stretch of a trip's route: a drive along roads, or a train, ferry or flight drawn dashed.
struct RouteSegment: Hashable {
    let line: [CLLocationCoordinate2D]
    let straight: Bool

    static func == (a: RouteSegment, b: RouteSegment) -> Bool {
        a.straight == b.straight && a.line.count == b.line.count
            && zip(a.line, b.line).allSatisfy { $0.latitude == $1.latitude && $0.longitude == $1.longitude }
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(straight)
        hasher.combine(line.count)
        if let first = line.first {
            hasher.combine(first.latitude)
            hasher.combine(first.longitude)
        }
    }
}

/// Trips' routes as `GET /v2/routes` answers them: `{ revision, trips: { <id>: [{ line, straight }] } }`,
/// each trip by the id the site gives it, its slug or else its uid in lower case (apps/api's
/// `collectionToTrip`). HereStore keeps the `trips` object as it is, keyed so.
enum TripRoutes {
    static func key(_ trip: Trip) -> String { trip.slug ?? trip.uid.lowercased() }

    /// The published route of [trip], or nil when the API has none for it.
    @MainActor
    static func published(_ trip: Trip, in store: HereStore) -> [RouteSegment]? {
        let answer = store.routes[key(trip)] ?? store.routes[trip.uid]
        guard let stretches = (answer?.array ?? answer?["segments"].array), !stretches.isEmpty else { return nil }
        let segments = stretches.compactMap { stretch -> RouteSegment? in
            let line = (stretch["line"].array ?? stretch["coordinates"].array ?? []).compactMap { pair -> CLLocationCoordinate2D? in
                guard let lng = pair[0].double, let lat = pair[1].double else { return nil }
                return ChinaShift.onMap(CLLocationCoordinate2D(latitude: lat, longitude: lng))
            }
            return line.count >= 2 ? RouteSegment(line: line, straight: stretch["straight"].bool ?? false) : nil
        }
        return segments.isEmpty ? nil : segments
    }
}

/// Where an entry was, as the map draws it: its place's point, else its own.
@MainActor
func coordinate(of entry: Entry, in store: HereStore) -> CLLocationCoordinate2D? {
    if let id = entry.place, let place = store.places[id] { return place.coordinate }
    return entry.point.map { ChinaShift.onMap(CLLocationCoordinate2D($0)) }
}

/// A place as the map shows it.
struct PlaceSummary: Identifiable {
    let place: Place
    var count = 0
    var first: String = "9999"
    var years: Set<Int> = []
    /// The newest photo taken there, for its photo annotation.
    var photo: MediaItem?
    var photoDay = ""

    var id: String { place.id }
    var firstYear: Int { Int(first.prefix(4)) ?? 0 }
}

/// A trip as the map shows it: its route, else straight lines between its places.
struct TripSummary: Identifiable {
    let trip: Trip
    let segments: [RouteSegment]
    let distance: Double
    let moments: Int
    /// Whether the route is the API's, along roads, or only lines between places.
    let routed: Bool

    var id: String { trip.uid }
    var points: [CLLocationCoordinate2D] { segments.flatMap(\.line) }
}

/// Everything the map pages draw, worked out once per change to the store.
struct Atlas {
    var places: [PlaceSummary] = []
    var trips: [TripSummary] = []
    /// The years places were first been to and trips were taken in, oldest first.
    var years: [Int] = []

    var distance: Double { trips.reduce(0) { $0 + $1.distance } }

    func color(_ year: Int) -> Color { YearColors.color(for: year, among: years) }

    @MainActor
    static func build(_ app: AppModel) -> Atlas {
        let store = app.store
        var places: [String: PlaceSummary] = [:]
        for entry in store.timeline {
            guard let id = entry.place, let place = store.places[id] else { continue }
            var summary = places[id] ?? PlaceSummary(place: place)
            summary.count += 1
            if entry.day < summary.first { summary.first = entry.day }
            if let year = Int(entry.day.prefix(4)) { summary.years.insert(year) }
            if let photo = entry.media.first(where: { !$0.utility }), entry.day >= summary.photoDay {
                summary.photo = photo
                summary.photoDay = entry.day
            }
            places[id] = summary
        }
        let trips = store.trips.map { trip(of: $0, app: app) }
        var years = Set(places.values.map(\.firstYear))
        years.formUnion(trips.map(\.trip.year))
        years.remove(0)
        return Atlas(
            places: places.values.sorted { $0.place.id < $1.place.id },
            trips: trips,
            years: years.sorted()
        )
    }

    /// [trip]'s route and numbers.
    @MainActor
    static func trip(of trip: Trip, app: AppModel) -> TripSummary {
        let store = app.store
        let entries = store.entries(of: trip)
        let published = TripRoutes.published(trip, in: store)
        let segments: [RouteSegment]
        if let published {
            segments = published
        } else {
            var line: [CLLocationCoordinate2D] = []
            for entry in entries {
                guard let point = coordinate(of: entry, in: store) else { continue }
                if let last = line.last, last.latitude == point.latitude, last.longitude == point.longitude { continue }
                line.append(point)
            }
            segments = line.isEmpty ? [] : [RouteSegment(line: line, straight: false)]
        }
        return TripSummary(
            trip: trip,
            segments: segments,
            distance: segments.reduce(0) { $0 + Geo.length($1.line) },
            moments: entries.filter(\.isMoment).count,
            routed: published != nil
        )
    }
}

extension Formats {
    /// A trip's dates, `Oct 22 – 27`, as the system writes a range.
    static func span(_ trip: Trip) -> String {
        guard let start = Times.day(trip.startDay), let end = Times.day(trip.endDay) else { return trip.startDay }
        let formatter = DateIntervalFormatter()
        formatter.locale = .autoupdatingCurrent
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let sameYear = trip.startDay.prefix(4) == Times.today().prefix(4) && trip.endDay.prefix(4) == trip.startDay.prefix(4)
        formatter.dateTemplate = sameYear ? "MMMd" : "yMMMd"
        return formatter.string(from: start, to: end)
    }

    /// `Apr 2025`.
    static func month(_ key: String) -> String {
        guard let date = Times.day(String(key.prefix(10))) else { return key }
        return formatter("yMMM").string(from: date)
    }
}
