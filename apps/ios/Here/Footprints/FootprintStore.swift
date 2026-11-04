import CoreLocation
import Foundation
import Observation

// The footprints: GPS tracks kept on the phone alone, in `footprints.sqlite` under the app's own
// store, out of the system's backup. Recording follows Core Location's live updates under a
// background activity session, so iOS rests the receiver itself while the phone stays still
// and wakes it when it moves; every fresh, valid fix is stored raw. The map's line is worked
// out from them: fixes over 50 m uncertain are left out, and the line breaks at gaps over 45
// seconds. Spans can be made public (a trip's page has a switch): only their line, simplified,
// is published, at the next 全部发布到网站; the raw samples never leave the phone.

nonisolated struct TrackPoint: Hashable, Sendable {
    /// Epoch milliseconds.
    var time: Int64
    var latitude: Double
    var longitude: Double
    var accuracy: Double
    var altitude: Double?
    var speed: Double?

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
    var location: CLLocation { CLLocation(latitude: latitude, longitude: longitude) }
}

/// A span of the footprints made public, and what of it was last published.
nonisolated struct TrackShare: Codable, Identifiable, Hashable, Sendable {
    var uid: String
    var start: Int64
    var end: Int64
    var collection: String?
    var simplify: Double = 30
    var removed = false
    var published: String?

    var id: String { uid }
}

@Observable
final class FootprintStore: NSObject {
    /// Daily recording, on until turned off.
    var daily: Bool = UserDefaults.standard.bool(forKey: "footprints-daily") {
        didSet {
            UserDefaults.standard.set(daily, forKey: "footprints-daily")
            daily ? start() : stop()
        }
    }

    private(set) var recording = false
    /// Whether Core Location says the phone is still, the receiver resting.
    private(set) var resting = false
    private(set) var authorization: CLAuthorizationStatus = .notDetermined
    private(set) var lastFix: TrackPoint?
    private(set) var shares: [TrackShare] = []
    /// Bumped on each stored fix, so what draws today's line draws it again.
    private(set) var version = 0

    private var db: SQLite?
    private let manager = CLLocationManager()
    private var updates: Task<Void, Never>?
    private var session: CLBackgroundActivitySession?
    private var service: CLServiceSession?

    override init() {
        super.init()
        manager.delegate = self
    }

    func load() {
        guard db == nil else { return }
        do {
            let db = try SQLite(path: Storage.file("footprints.sqlite").path)
            try db.execute("""
                CREATE TABLE IF NOT EXISTS samples (
                  time INTEGER PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL,
                  accuracy REAL NOT NULL, altitude REAL, speed REAL
                )
                """)
            try db.execute("CREATE TABLE IF NOT EXISTS shares (uid TEXT PRIMARY KEY, record TEXT NOT NULL)")
            self.db = db
            readShares()
        } catch {
            print("Footprints failed to open: \(error)")
        }
        authorization = manager.authorizationStatus
        if daily { start() }
    }

    // MARK: Recording

    func start() {
        guard updates == nil else { return }
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        service = CLServiceSession(authorization: .always)
        session = CLBackgroundActivitySession()
        recording = true
        updates = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.otherNavigation) {
                    guard let self else { return }
                    resting = update.stationary
                    if let location = update.location { keep(location) }
                }
            } catch {
                self?.recording = false
            }
        }
    }

    func stop() {
        updates?.cancel()
        updates = nil
        session?.invalidate()
        session = nil
        service?.invalidate()
        service = nil
        recording = false
        resting = false
    }

    /// Stores a fix if it is fresh and valid.
    private func keep(_ location: CLLocation) {
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy < 200, abs(location.timestamp.timeIntervalSinceNow) < 60 else { return }
        let point = TrackPoint(
            time: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracy: location.horizontalAccuracy,
            altitude: location.verticalAccuracy >= 0 ? location.altitude : nil,
            speed: location.speed >= 0 ? location.speed : nil
        )
        insert([point])
        lastFix = point
        version += 1
    }

    private func insert(_ points: [TrackPoint]) {
        try? db?.run(
            "INSERT OR IGNORE INTO samples (time, latitude, longitude, accuracy, altitude, speed) VALUES (?, ?, ?, ?, ?, ?)",
            points.map { [.int($0.time), .double($0.latitude), .double($0.longitude), .double($0.accuracy), $0.altitude.map { .double($0) } ?? .null, $0.speed.map { .double($0) } ?? .null] }
        )
    }

    // MARK: Reading

    /// Every stored sample between two instants, oldest first.
    func samples(from: Date, to: Date) -> [TrackPoint] {
        let rows = try? db?.query(
            "SELECT time, latitude, longitude, accuracy, altitude, speed FROM samples WHERE time >= ? AND time < ? ORDER BY time",
            [.int(Int64(from.timeIntervalSince1970 * 1000)), .int(Int64(to.timeIntervalSince1970 * 1000))]
        ) { row in
            TrackPoint(time: row.int(0) ?? 0, latitude: row.double(1) ?? 0, longitude: row.double(2) ?? 0, accuracy: row.double(3) ?? 0, altitude: row.double(4), speed: row.double(5))
        }
        return rows ?? []
    }

    /// The samples of a day, `YYYY-MM-DD`, in the phone's zone.
    func samples(day: String) -> [TrackPoint] {
        guard let (from, to) = Self.bounds(day) else { return [] }
        return samples(from: from, to: to)
    }

    static func bounds(_ day: String) -> (Date, Date)? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let start = formatter.date(from: day) else { return nil }
        return (start, Calendar.current.date(byAdding: .day, value: 1, to: start)!)
    }

    /// The line drawn of samples: each unbroken stretch of good fixes.
    static func lines(_ points: [TrackPoint]) -> [[TrackPoint]] {
        var out: [[TrackPoint]] = []
        for point in points where point.accuracy <= 50 {
            if let last = out.last?.last, connected(last, point) {
                out[out.count - 1].append(point)
            } else {
                out.append([point])
            }
        }
        return out.filter { $0.count >= 2 }
    }

    /// Whether two fixes are one line: no gap over 45 seconds.
    static func connected(_ a: TrackPoint, _ b: TrackPoint) -> Bool { b.time - a.time <= 45_000 }

    static func distance(_ lines: [[TrackPoint]]) -> Double {
        lines.reduce(0) { total, line in
            total + zip(line, line.dropFirst()).reduce(0) { $0 + $1.0.location.distance(from: $1.1.location) }
        }
    }

    /// How many samples are stored, and since when.
    var summary: (count: Int, first: Date?) {
        let count = (try? db?.query("SELECT count(*), min(time) FROM samples") { ($0.int(0) ?? 0, $0.int(1)) })?.first
        return (Int(count?.0 ?? 0), count?.1.map { Date(timeIntervalSince1970: Double($0) / 1000) })
    }

    /// Deletes the samples of a day.
    func delete(day: String) {
        guard let (from, to) = Self.bounds(day) else { return }
        try? db?.run("DELETE FROM samples WHERE time >= ? AND time < ?", [.int(Int64(from.timeIntervalSince1970 * 1000)), .int(Int64(to.timeIntervalSince1970 * 1000))])
        version += 1
    }

    // MARK: GPX

    /// Every stored sample of a day as GPX, raw, to keep or import again.
    func gpx(day: String) -> URL? {
        let points = samples(day: day)
        guard !points.isEmpty else { return nil }
        var out = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Here" xmlns="http://www.topografix.com/GPX/1/1" xmlns:here="https://hyoban.cc/ns/footprints/1">
            <trk><name>\(day)</name><trkseg>

            """
        let stamp = ISO8601DateFormatter()
        for point in points {
            out += "<trkpt lat=\"\(point.latitude)\" lon=\"\(point.longitude)\">"
            if let altitude = point.altitude { out += "<ele>\(altitude)</ele>" }
            out += "<time>\(stamp.string(from: Date(timeIntervalSince1970: Double(point.time) / 1000)))</time>"
            out += "<extensions><here:accuracy>\(point.accuracy)</here:accuracy>"
            if let speed = point.speed { out += "<here:speed>\(speed)</here:speed>" }
            out += "</extensions></trkpt>\n"
        }
        out += "</trkseg></trk></gpx>\n"
        let url = FileManager.default.temporaryDirectory.appending(path: "footprints-\(day).gpx")
        try? out.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// Reads a GPX's points back as samples; where the phone already has a sample within 45
    /// seconds, its own is kept. Gives how many were added.
    func importGPX(_ url: URL) -> Int {
        guard let data = try? Data(contentsOf: url) else { return 0 }
        let reader = GPXReader()
        let parser = XMLParser(data: data)
        parser.delegate = reader
        parser.parse()
        guard let first = reader.points.first, let last = reader.points.last else { return 0 }
        let kept = samples(from: Date(timeIntervalSince1970: Double(first.time - 45_000) / 1000), to: Date(timeIntervalSince1970: Double(last.time + 45_000) / 1000))
        let fresh = reader.points.filter { point in !kept.contains { abs($0.time - point.time) <= 45_000 } }
        insert(fresh)
        version += 1
        return fresh.count
    }

    // MARK: Public spans

    private func readShares() {
        let decoder = JSONDecoder()
        shares = ((try? db?.query("SELECT record FROM shares") { $0.text(0) ?? "" }) ?? [])
            .compactMap { try? decoder.decode(TrackShare.self, from: Data($0.utf8)) }
    }

    private func put(_ share: TrackShare) {
        guard let data = try? JSONEncoder().encode(share) else { return }
        try? db?.run("INSERT INTO shares (uid, record) VALUES (?, ?) ON CONFLICT (uid) DO UPDATE SET record = excluded.record", [.text(share.uid), .text(String(decoding: data, as: UTF8.self))])
        readShares()
    }

    /// The public span of a trip, if it has one that isn't taken back.
    func share(of collection: String) -> TrackShare? { shares.first { $0.collection == collection && !$0.removed } }

    /// Makes a trip's footprints public, or private again.
    func setShared(_ shared: Bool, trip: Trip) {
        if shared {
            if var share = shares.first(where: { $0.collection == trip.collection.uid }) {
                share.removed = false
                put(share)
            } else {
                guard let (from, _) = Self.bounds(trip.start), let (_, to) = Self.bounds(trip.end) else { return }
                put(TrackShare(uid: Ulid.make(), start: Int64(from.timeIntervalSince1970 * 1000), end: Int64(to.timeIntervalSince1970 * 1000), collection: trip.collection.uid))
            }
        } else if var share = shares.first(where: { $0.collection == trip.collection.uid }) {
            share.removed = true
            put(share)
        }
    }

    /// A span's public line: a segment for each unbroken stretch, each simplified to the span's
    /// metres, `[longitude, latitude, unix seconds]` a point, and how far it went.
    func publicLine(_ share: TrackShare) -> JSON? {
        guard !share.removed else { return nil }
        let stretches = Self.lines(samples(from: Date(timeIntervalSince1970: Double(share.start) / 1000), to: Date(timeIntervalSince1970: Double(share.end) / 1000)))
        guard !stretches.isEmpty else { return nil }
        let round = { (value: Double) in (value * 1e6).rounded() / 1e6 }
        var line: JSON = [
            "uid": JSON(share.uid),
            "start": JSON(Times.local(Date(timeIntervalSince1970: Double(share.start) / 1000))),
            "end": JSON(Times.local(Date(timeIntervalSince1970: Double(share.end) / 1000))),
            "simplify": JSON(share.simplify),
            "segments": .array(stretches.map { stretch in
                ["coordinates": .array(Self.simplify(stretch, tolerance: share.simplify).map { point in
                    [JSON(round(point.longitude)), JSON(round(point.latitude)), JSON(Int(point.time / 1000))]
                })]
            }),
            "distance": JSON(Int(Self.distance(stretches).rounded())),
        ]
        if let collection = share.collection { line["collection"] = JSON(collection) }
        return line
    }

    /// Every public span whose line isn't what was last published, or taken back.
    var pendingTracks: [(share: TrackShare, line: JSON?)] {
        shares.compactMap { share in
            let line = publicLine(share)
            if line?.text == share.published || (line == nil && share.published == nil) { return nil }
            return (share, line)
        }
    }

    func markSharesPublished(_ sent: [String: String?]) {
        for (uid, line) in sent {
            guard var share = shares.first(where: { $0.uid == uid }) else { continue }
            share.published = line
            put(share)
        }
    }

    /// Douglas–Peucker to [tolerance] metres, on a plane local to the stretch.
    static func simplify(_ points: [TrackPoint], tolerance: Double) -> [TrackPoint] {
        guard points.count >= 3 else { return points }
        let perLongitude = 111_320.0 * cos(points[0].latitude * .pi / 180)
        let x = { (p: TrackPoint) in p.longitude * perLongitude }
        let y = { (p: TrackPoint) in p.latitude * 110_540.0 }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var stack = [(0, points.count - 1)]
        while let (first, last) = stack.popLast() {
            let (ax, ay, bx, by) = (x(points[first]), y(points[first]), x(points[last]), y(points[last]))
            let length = hypot(bx - ax, by - ay)
            var farthest = -1
            var distance = tolerance
            for index in (first + 1)..<last {
                let (px, py) = (x(points[index]), y(points[index]))
                let d = length == 0 ? hypot(px - ax, py - ay) : abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / length
                if d > distance { distance = d; farthest = index }
            }
            if farthest >= 0 {
                keep[farthest] = true
                stack.append((first, farthest))
                stack.append((farthest, last))
            }
        }
        return points.indices.filter { keep[$0] }.map { points[$0] }
    }
}

extension FootprintStore: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in self.authorization = status }
    }

    /// Asks for the phone's place once, for the map's locate button.
    func requestWhenInUse() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
    }

    func requestAlways() { manager.requestAlwaysAuthorization() }
}

/// Reads a GPX file's track points.
private final class GPXReader: NSObject, XMLParserDelegate {
    var points: [TrackPoint] = []
    private var current: TrackPoint?
    private var text = ""
    private let stamp = ISO8601DateFormatter()

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        text = ""
        if name == "trkpt", let lat = attributes["lat"].flatMap(Double.init), let lon = attributes["lon"].flatMap(Double.init) {
            current = TrackPoint(time: 0, latitude: lat, longitude: lon, accuracy: 10)
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch name {
        case "time": if let date = stamp.date(from: value) { current?.time = Int64(date.timeIntervalSince1970 * 1000) }
        case "ele": current?.altitude = Double(value)
        case "here:accuracy", "accuracy": if let accuracy = Double(value) { current?.accuracy = accuracy }
        case "here:speed", "speed": current?.speed = Double(value)
        case "trkpt":
            if let point = current, point.time > 0 { points.append(point) }
            current = nil
        default: break
        }
    }
}
