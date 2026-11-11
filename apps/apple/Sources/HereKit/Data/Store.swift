import Foundation
import Observation

/// The records as the pages read them, from the copy of the API's. Pages read its arrays and maps.
@MainActor
@Observable
public final class HereStore {
    public enum Phase: Equatable, Sendable {
        case idle
        case syncing
        case failed(String)
    }

    public let copy: CopyStore

    /// Every entry, by uid.
    public private(set) var entries: [String: Entry] = [:]
    /// Every entry, oldest first.
    public private(set) var timeline: [Entry] = []
    /// Entries by their day, each day's oldest first.
    public private(set) var byDay: [String: [Entry]] = [:]
    public private(set) var places: [String: Place] = [:]
    /// Trips, the latest first.
    public private(set) var trips: [Trip] = []
    /// Published trips' routes as `GET /v2/routes` answers, by trip uid; empty until read.
    public private(set) var routes: [String: JSON] = [:]
    public private(set) var phase: Phase = .idle
    /// When the copy was last brought up to date, this session.
    public private(set) var syncedAt: Date?
    /// Bumped on every change, for views that cache what they work out.
    public private(set) var version = 0

    public init(folder: URL) throws {
        copy = try CopyStore(file: folder.appendingPathComponent("copy.sqlite"))
        reload()
    }

    // MARK: Reading

    /// Reads the copy again.
    public func reload() {
        var entries: [String: Entry] = [:]
        for (key, record) in copy.live("entries") {
            if let json = try? JSON(parsing: record) { entries[key] = Entry(json: json) }
        }
        var places: [String: Place] = [:]
        for (key, record) in copy.live("places") {
            if let json = try? JSON(parsing: record) { places[key] = Place(id: key, json: json) }
        }
        var trips: [String: Trip] = [:]
        for (key, record) in copy.live("collections") {
            if let json = try? JSON(parsing: record), json["kind"].string == "trip" { trips[key] = Trip(json: json) }
        }
        self.entries = entries
        self.places = places
        self.trips = trips.values.sorted { $0.start > $1.start }
        timeline = entries.values.sorted(by: Entry.chronological)
        byDay = Dictionary(grouping: timeline, by: \.day)
        version += 1
    }

    /// The entries [trip] holds, oldest first.
    public func entries(of trip: Trip) -> [Entry] {
        var held: [Entry] = []
        for day in dayKeys(from: trip.startDay, to: trip.endDay) {
            held += (byDay[day] ?? []).filter { !trip.exclude.contains($0.uid) }
        }
        held += trip.include.compactMap { entries[$0] }.filter { $0.day < trip.startDay || $0.day > trip.endDay }
        return held.sorted(by: Entry.chronological)
    }

    /// The entries at [place], latest first.
    public func entries(at place: String) -> [Entry] {
        timeline.filter { $0.place == place }.reversed()
    }

    /// Days from [start] to [end], both included.
    public func dayKeys(from start: String, to end: String) -> [String] {
        var days: [String] = []
        var day = start
        while day <= end, days.count < 3660 {
            days.append(day)
            day = Times.adding(1, to: day)
        }
        return days
    }

    // MARK: Syncing

    /// Brings the copy up to the API's latest revision and reads it again; the routes too.
    public func sync(with api: HereAPI) async {
        guard phase == .idle || { if case .failed = phase { true } else { false } }() else { return }
        phase = .syncing
        do {
            try await copy.sync(with: api)
            if let answer = try? await api.routes() {
                var routes: [String: JSON] = [:]
                if let trips = answer["trips"].object {
                    routes = trips
                } else if let trips = answer["trips"].array {
                    for trip in trips { if let uid = trip["uid"].string ?? trip["trip"].string { routes[uid] = trip } }
                }
                self.routes = routes
            }
            syncedAt = Date()
            reload()
            phase = .idle
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }
}
