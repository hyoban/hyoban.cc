import Foundation
import MapKit

// The map's fog of war, as the site's (packages/trip-map/src/map/fog-layer.ts): a light fog of the
// page's ink over everywhere not been to, thinning over the cities been to the more Moments each
// holds, to `first` of it for one and none from about twenty, on a fixed scale so a city keeps
// its fog as others fill up. The cities are the site's file, copied into the app as it builds,
// each a region with an id, and `outside`, the rest of the world, never lit. A place on no city,
// on a coast the boundaries were simplified past, lights the nearest within about 10 km.

/// Regions drawn with one fog's thickness, as one overlay.
nonisolated final class FogPolygons: MKMultiPolygon {
    var opacity: CGFloat = 0.22
}

/// The fog for a set of places, as overlays to draw.
nonisolated final class FogOverlay: @unchecked Sendable {
    let overlays: [FogPolygons]
    /// How many cities and provinces are lit.
    let cities: Int
    let provinces: Int

    init(overlays: [FogPolygons], cities: Int, provinces: Int) {
        self.overlays = overlays
        self.cities = cities
        self.provinces = provinces
    }
}

nonisolated struct FogRegion: Sendable {
    var id: String
    var fogged: Bool
    /// Each polygon an outer ring then its holes, as coordinates.
    var polygons: [[[CLLocationCoordinate2D]]]
    var west, south, east, north: Double

    func contains(_ point: CLLocationCoordinate2D) -> Bool {
        guard point.longitude >= west, point.longitude <= east, point.latitude >= south, point.latitude <= north else { return false }
        return polygons.contains { rings in
            guard let outer = rings.first, Self.inside(point, outer) else { return false }
            return !rings.dropFirst().contains { Self.inside(point, $0) }
        }
    }

    /// How far a point is from the region's box, in degrees.
    func distance(_ point: CLLocationCoordinate2D) -> Double {
        let dx = max(west - point.longitude, 0, point.longitude - east)
        let dy = max(south - point.latitude, 0, point.latitude - north)
        return hypot(dx, dy)
    }

    private static func inside(_ point: CLLocationCoordinate2D, _ ring: [CLLocationCoordinate2D]) -> Bool {
        var inside = false
        var j = ring.count - 1
        for i in ring.indices {
            let a = ring[i], b = ring[j]
            if (a.latitude > point.latitude) != (b.latitude > point.latitude),
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            {
                inside.toggle()
            }
            j = i
        }
        return inside
    }
}

nonisolated enum Fog {
    private static let opacity: CGFloat = 0.22
    private static let first: Double = 0.6
    private static let clear = log(21.0)
    private static let reach = 0.1

    /// The cities, read once from the bundle.
    static func regions() async -> [FogRegion] {
        if let cached = await Cache.shared.regions { return cached }
        let regions = await Task.detached(priority: .userInitiated) { () -> [FogRegion] in
            guard let url = Bundle.main.url(forResource: "cities", withExtension: "json"),
                let data = try? Data(contentsOf: url),
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let features = json["features"] as? [[String: Any]]
            else { return [] }
            return features.compactMap(Self.region)
        }.value
        await Cache.shared.keep(regions)
        return regions
    }

    private actor Cache {
        static let shared = Cache()
        var regions: [FogRegion]?
        func keep(_ regions: [FogRegion]) { self.regions = regions }
    }

    private nonisolated static func region(_ feature: [String: Any]) -> FogRegion? {
        guard let properties = feature["properties"] as? [String: Any], let geometry = feature["geometry"] as? [String: Any],
            let type = geometry["type"] as? String, let coordinates = geometry["coordinates"] as? [Any]
        else { return nil }
        let raw = type == "Polygon" ? [coordinates] : coordinates
        var west = 180.0, south = 90.0, east = -180.0, north = -90.0
        let polygons: [[[CLLocationCoordinate2D]]] = raw.compactMap { polygon in
            (polygon as? [[[Double]]])?.map { ring in
                ring.map { point in
                    let coordinate = CLLocationCoordinate2D(latitude: point[1], longitude: point[0])
                    west = min(west, point[0]); east = max(east, point[0])
                    south = min(south, point[1]); north = max(north, point[1])
                    return coordinate
                }
            }
        }
        let id = properties["id"] as? String ?? feature["id"] as? String ?? ""
        return FogRegion(id: id, fogged: properties["fogged"] as? Bool ?? false, polygons: polygons, west: west, south: south, east: east, north: north)
    }

    /// The fog over [regions] with the cities holding [places] lit by how many Moments each has.
    static func overlay(regions: [FogRegion], places: [(CLLocationCoordinate2D, Int)]) async -> FogOverlay {
        let built = await Task.detached(priority: .userInitiated) { () -> (buckets: [Int: [[[CLLocationCoordinate2D]]]], cities: Int, provinces: Int) in
            var weights: [String: Int] = [:]
            let candidates = regions.filter { !$0.fogged }
            for (point, weight) in places {
                let hit = candidates.first { $0.contains(point) }
                    ?? candidates.filter { $0.distance(point) < reach }.min { $0.distance(point) < $1.distance(point) }
                if let hit { weights[hit.id, default: 0] += weight }
            }
            // The fog's thickness in tenths: 10 for everywhere not been to.
            var buckets: [Int: [[[CLLocationCoordinate2D]]]] = [:]
            for region in regions {
                let factor: Double
                if let weight = weights[region.id], !region.fogged {
                    factor = max(0, first * (clear - log(Double(weight) + 1)) / (clear - log(2)))
                } else {
                    factor = 1
                }
                let level = Int((factor * 10).rounded())
                if level > 0 { buckets[level, default: []] += region.polygons }
            }
            let provinces = Set(weights.keys.map { $0.prefix(2) }).count
            return (buckets, weights.count, provinces)
        }.value
        let overlays = built.buckets.map { level, polygons in
            let overlay = FogPolygons(polygons.map { rings in
                let holes = rings.dropFirst().map { MKPolygon(coordinates: $0, count: $0.count) }
                return MKPolygon(coordinates: rings[0], count: rings[0].count, interiorPolygons: holes)
            })
            overlay.opacity = opacity * CGFloat(level) / 10
            return overlay
        }
        return FogOverlay(overlays: overlays, cities: built.cities, provinces: built.provinces)
    }
}
