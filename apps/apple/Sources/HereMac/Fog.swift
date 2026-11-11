import Foundation
import HereKit
import MapKit
import Observation
import SwiftUI

// The map's fog of war, as the site's (packages/trip-map/src/fog-of-war.ts and
// map/fog-layer.ts) and Android's (ui/map/FogOfWar.kt): a light fog of the page's ink over
// everywhere not been to, thinning over each city been to by the log of how many Moments it
// holds, to `first` of it for one and none from about twenty. The cities are the site's
// `cities.json` (apps/web/src/data/boundaries), each a region with an id; a place on no city,
// on a coast the boundaries were simplified past, lights the nearest within reach.
//
// MapKit draws it as one polygon over the world with a hole for every city lit, and each city
// lit drawn again as thin as it is familiar. What MapKit can't do: the site draws the
// basemap's water over the fog so the sea stays clear, and sweeps the fog in from the edges;
// here the sea is fogged too and the fog fades in.

/// The fog's numbers, as fog-layer.ts's `FOG`.
private enum Thickness {
    static let opacity = 0.22
    static let first = 0.6
    static let clear = log(21.0)

    /// How thick the fog is over a city holding [count] Moments.
    static func of(_ count: Double) -> Double {
        let x = log(1 + count)
        if x <= 0 { return opacity }
        if x <= log(2.0) { return opacity + (opacity * first - opacity) * (x / log(2.0)) }
        if x >= clear { return 0 }
        return opacity * first * (1 - (x - log(2.0)) / (clear - log(2.0)))
    }
}

/// One region of the cities' GeoJSON: its id, and its polygons, each an outer ring then its
/// holes, every ring flat as longitude, latitude, longitude, latitude…
struct FogRegion: Sendable {
    let id: String
    let fogged: Bool
    let polygons: [[[Double]]]
    /// West, south, east, north.
    let bounds: [Double]
}

/// The cities lit, and the shapes MapKit draws the fog with.
@MainActor
final class FogShape {
    /// The world, with a hole for every city lit.
    let world: MKPolygon
    /// Each city lit, drawn again as thick as it is still unfamiliar.
    let thinned: [(polygon: MKPolygon, opacity: Double)]
    /// The holes of the cities lit, other cities inside them, fogged as the rest.
    let enclaves: [MKPolygon]
    let cities: Int
    let provinces: Int

    init(regions: [FogRegion], lit: [String: Double]) {
        var holes: [MKPolygon] = []
        var thinned: [(MKPolygon, Double)] = []
        var enclaves: [MKPolygon] = []
        for region in regions where lit[region.id] != nil {
            let opacity = Thickness.of(lit[region.id] ?? 1)
            for polygon in region.polygons {
                guard let outer = polygon.first else { continue }
                let shape = Self.polygon(outer)
                holes.append(shape)
                if opacity > 0.001 { thinned.append((shape, opacity)) }
                enclaves += polygon.dropFirst().map(Self.polygon)
            }
        }
        // The world as map points, a little inside its edges so MapKit doesn't wrap it.
        let world = MKMapRect.world
        let inset = world.width * 0.0001
        let corners = [
            MKMapPoint(x: world.minX + inset, y: world.minY + inset),
            MKMapPoint(x: world.maxX - inset, y: world.minY + inset),
            MKMapPoint(x: world.maxX - inset, y: world.maxY - inset),
            MKMapPoint(x: world.minX + inset, y: world.maxY - inset),
        ]
        self.world = MKPolygon(points: corners, count: corners.count, interiorPolygons: holes)
        self.thinned = thinned
        self.enclaves = enclaves
        cities = lit.count
        provinces = Set(lit.keys.map { $0.prefix(2) }).count
    }

    private static func polygon(_ ring: [Double]) -> MKPolygon {
        var coordinates: [CLLocationCoordinate2D] = []
        coordinates.reserveCapacity(ring.count / 2)
        var index = 0
        while index + 1 < ring.count {
            coordinates.append(CLLocationCoordinate2D(latitude: ring[index + 1], longitude: ring[index]))
            index += 2
        }
        return MKPolygon(coordinates: coordinates, count: coordinates.count)
    }
}

/// The fog's regions, read once, and the fog for the places been to.
@MainActor
@Observable
final class FogModel {
    static let shared = FogModel()

    private(set) var regions: [FogRegion]?
    private(set) var failed = false
    private var loading: Task<Void, Never>?

    /// The cities: the app's own copy of the site's file, else the one kept from the site.
    func load(folder: URL) {
        guard regions == nil, loading == nil else { return }
        let kept = folder.appendingPathComponent("cities.json")
        loading = Task {
            let regions = await Self.read(bundled: Bundle.main.url(forResource: "cities", withExtension: "json"), kept: kept)
            self.regions = regions
            failed = regions == nil
            loading = nil
        }
    }

    private nonisolated static func read(bundled: URL?, kept: URL) async -> [FogRegion]? {
        if let bundled, let data = try? Data(contentsOf: bundled), let regions = parse(data) { return regions }
        if let data = try? Data(contentsOf: kept), let regions = parse(data) { return regions }
        // The site serves the same file as /map/cities.json; keep it for the next time.
        guard let site = Site.url, let url = URL(string: "\(site)/map/cities.json"),
              let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let regions = parse(data) else { return nil }
        try? FileManager.default.createDirectory(at: kept.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: kept)
        return regions
    }

    /// The regions of the GeoJSON collection.
    nonisolated static func parse(_ data: Data) -> [FogRegion]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]] else { return nil }
        return features.compactMap { feature in
            guard let properties = feature["properties"] as? [String: Any],
                  let geometry = feature["geometry"] as? [String: Any],
                  let coordinates = geometry["coordinates"] as? [Any] else { return nil }
            let id = (properties["id"] as? String) ?? (properties["id"] as? NSNumber)?.stringValue ?? ""
            let polygons: [[[Double]]]
            if geometry["type"] as? String == "MultiPolygon" {
                polygons = coordinates.compactMap { ($0 as? [Any])?.map(ring) }
            } else {
                polygons = [coordinates.map(ring)]
            }
            var bounds = [Double.infinity, .infinity, -Double.infinity, -Double.infinity]
            for polygon in polygons {
                guard let outer = polygon.first else { continue }
                var index = 0
                while index + 1 < outer.count {
                    bounds[0] = min(bounds[0], outer[index])
                    bounds[1] = min(bounds[1], outer[index + 1])
                    bounds[2] = max(bounds[2], outer[index])
                    bounds[3] = max(bounds[3], outer[index + 1])
                    index += 2
                }
            }
            return FogRegion(id: id, fogged: properties["fogged"] as? Bool ?? false, polygons: polygons, bounds: bounds)
        }
    }

    private nonisolated static func ring(_ value: Any) -> [Double] {
        guard let points = value as? [[Any]] else { return [] }
        var flat: [Double] = []
        flat.reserveCapacity(points.count * 2)
        for point in points where point.count >= 2 {
            flat.append((point[0] as? NSNumber)?.doubleValue ?? 0)
            flat.append((point[1] as? NSNumber)?.doubleValue ?? 0)
        }
        return flat
    }

    /// The cities holding [places] (longitude, latitude, weight), and how much each holds.
    nonisolated static func lit(_ regions: [FogRegion], places: [(Double, Double, Double)]) -> [String: Double] {
        let candidates = regions.filter { !$0.fogged }
        var lit: [String: Double] = [:]
        for (x, y, weight) in places {
            let found = candidates.first { region in
                x >= region.bounds[0] && x <= region.bounds[2] && y >= region.bounds[1] && y <= region.bounds[3]
                    && region.polygons.contains { polygon in
                        guard let outer = polygon.first else { return false }
                        return contains(outer, x, y) && !polygon.dropFirst().contains { contains($0, x, y) }
                    }
            } ?? nearest(candidates, x, y)
            if let found { lit[found.id, default: 0] += weight }
        }
        return lit
    }

    /// About 10 km, in degrees: how far a place on no city may be from the nearest one's edge.
    private nonisolated static let reach = 0.1

    private nonisolated static func nearest(_ regions: [FogRegion], _ x: Double, _ y: Double) -> FogRegion? {
        var best = reach
        var nearest: FogRegion?
        for region in regions {
            let b = region.bounds
            if x < b[0] - best || x > b[2] + best || y < b[1] - best || y > b[3] + best { continue }
            for ring in region.polygons.joined() {
                var index = 2
                while index + 1 < ring.count {
                    let distance = segmentDistance(x, y, ring[index - 2], ring[index - 1], ring[index], ring[index + 1])
                    if distance < best {
                        best = distance
                        nearest = region
                    }
                    index += 2
                }
            }
        }
        return nearest
    }

    private nonisolated static func segmentDistance(_ x: Double, _ y: Double, _ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> Double {
        let length = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
        let t = length == 0 ? 0 : max(0, min(1, ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / length))
        return hypot(x - (x1 + t * (x2 - x1)), y - (y1 + t * (y2 - y1)))
    }

    /// Even-odd: whether a ray east from the point crosses the ring an odd number of times.
    private nonisolated static func contains(_ ring: [Double], _ x: Double, _ y: Double) -> Bool {
        var inside = false
        let count = ring.count / 2
        var previous = count - 1
        for index in 0 ..< count {
            let x1 = ring[index * 2], y1 = ring[index * 2 + 1]
            let x2 = ring[previous * 2], y2 = ring[previous * 2 + 1]
            if (y1 > y) != (y2 > y), x < (x2 - x1) * (y - y1) / (y2 - y1) + x1 { inside.toggle() }
            previous = index
        }
        return inside
    }
}

/// The places the fog lifts over: where Moments were written or check-ins made, each weighed
/// by how many were, as the site's `/map/fog.json`.
@MainActor
func fogPlaces(_ app: AppModel) -> [(Double, Double, Double)] {
    var counts: [String: Double] = [:]
    for entry in app.store.timeline where (entry.isMoment || !entry.part("checkin").isNull) {
        if let place = entry.place, app.store.places[place] != nil { counts[place, default: 0] += 1 }
    }
    return counts.compactMap { id, count in
        app.store.places[id].map { ($0.longitude, $0.latitude, count) }
    }
}

/// Works the fog out for a page: off the main thread, again when the records change.
struct FogLoader: ViewModifier {
    @Environment(AppModel.self) private var app
    let on: Bool
    @Binding var shape: FogShape?

    func body(content: Content) -> some View {
        let model = FogModel.shared
        content
            .task(id: FogKey(on: on, version: app.store.version, loaded: model.regions != nil)) {
                guard on else { return }
                model.load(folder: app.folder)
                guard let regions = model.regions else { return }
                let places = fogPlaces(app)
                let lit = await Task.detached(priority: .userInitiated) { FogModel.lit(regions, places: places) }.value
                guard !Task.isCancelled else { return }
                shape = FogShape(regions: regions, lit: lit)
            }
    }

    private struct FogKey: Hashable {
        let on: Bool
        let version: Int
        let loaded: Bool
    }
}

/// The fog as map content: the page's ink, light over everywhere not been to.
struct FogContent: MapContent {
    let shape: FogShape
    let ink: Color

    var body: some MapContent {
        MapPolygon(shape.world)
            .foregroundStyle(ink.opacity(Thickness.opacity))
            .stroke(.clear, lineWidth: 0)
        ForEach(Array(shape.thinned.enumerated()), id: \.offset) { _, city in
            MapPolygon(city.polygon)
                .foregroundStyle(ink.opacity(city.opacity))
                .stroke(.clear, lineWidth: 0)
        }
        ForEach(Array(shape.enclaves.enumerated()), id: \.offset) { _, enclave in
            MapPolygon(enclave)
                .foregroundStyle(ink.opacity(Thickness.opacity))
                .stroke(.clear, lineWidth: 0)
        }
    }
}

extension ColorScheme {
    /// The page's ink, what the fog is made of: the text colour.
    var ink: Color { self == .dark ? .white : .black }
}
