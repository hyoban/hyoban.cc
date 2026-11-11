import CoreLocation
import Foundation

// MapKit draws the mainland's basemap, as every map of China does, in GCJ-02: WGS 84 shifted by a
// few hundred metres. It takes the coordinates it is given as they are, so a point of the records,
// in WGS 84, lands that far off the street it is on. What the map draws is shifted here first, by
// the formula and over the area Android shifts its points back by (shared/…/ChinaCoordinates.kt):
// the mainland, Hong Kong and Macau, from the city outlines (`gcj-area.json`, which
// `scripts/mac-app.sh` puts in the app). Taiwan and the rest of the world stay as they are. The
// records keep WGS 84; only what is drawn or handed to Maps is shifted.

@MainActor
enum ChinaShift {
    /// [coordinate] as the map draws it.
    static func onMap(_ coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        holds(coordinate) ? gcj(coordinate) : coordinate
    }

    /// [coordinate] shifted, wherever it is: for what is known to be in the area.
    static func gcj(_ coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let x = coordinate.longitude - 105
        let y = coordinate.latitude - 35
        var dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * abs(x).squareRoot()
            + (20 * sin(6 * x * .pi) + 20 * sin(2 * x * .pi)) * 2 / 3
            + (20 * sin(y * .pi) + 40 * sin(y / 3 * .pi)) * 2 / 3
            + (160 * sin(y / 12 * .pi) + 320 * sin(y * .pi / 30)) * 2 / 3
        var dLng = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * abs(x).squareRoot()
            + (20 * sin(6 * x * .pi) + 20 * sin(2 * x * .pi)) * 2 / 3
            + (20 * sin(x * .pi) + 40 * sin(x / 3 * .pi)) * 2 / 3
            + (150 * sin(x / 12 * .pi) + 300 * sin(x / 30 * .pi)) * 2 / 3
        let radians = coordinate.latitude / 180 * .pi
        let magic = 1 - eccentricity * sin(radians) * sin(radians)
        let root = magic.squareRoot()
        dLat = dLat * 180 / (semiMajor * (1 - eccentricity) / (magic * root) * .pi)
        dLng = dLng * 180 / (semiMajor / root * cos(radians) * .pi)
        return CLLocationCoordinate2D(latitude: coordinate.latitude + dLat, longitude: coordinate.longitude + dLng)
    }

    /// Whether [coordinate] is where the basemap is shifted, worked out once for each cell of
    /// about five kilometres a side: no coarser than the area's own outline and margin.
    static func holds(_ coordinate: CLLocationCoordinate2D) -> Bool {
        let cell = Cell(x: Int((coordinate.longitude / cellSize).rounded(.down)), y: Int((coordinate.latitude / cellSize).rounded(.down)))
        if let known = cells[cell] { return known }
        let middle = CLLocationCoordinate2D(latitude: (Double(cell.y) + 0.5) * cellSize, longitude: (Double(cell.x) + 0.5) * cellSize)
        let answer = area.contains { $0.holds(middle) }
        cells[cell] = answer
        return answer
    }

    /// Krasovsky 1940, the ellipsoid GCJ-02 is worked on.
    private static let semiMajor = 6378245.0
    private static let eccentricity = 0.00669342162296594323
    private static let cellSize = 0.05

    private struct Cell: Hashable {
        let x: Int
        let y: Int
    }

    private static var cells: [Cell: Bool] = [:]

    /// The area's polygons, each its rings of longitude, latitude pairs, outer first.
    private static let area: [AreaPolygon] = {
        guard let url = Bundle.main.url(forResource: "gcj-area", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let geometry = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let coordinates = geometry["coordinates"] as? [Any]
        else { return [] }
        let polygons = geometry["type"] as? String == "Polygon" ? [coordinates] : coordinates.compactMap { $0 as? [Any] }
        return polygons.compactMap { polygon in
            let rings = polygon.compactMap { ring in
                (ring as? [[Double]])?.flatMap { $0.count >= 2 ? [$0[0], $0[1]] : [] }
            }
            return rings.isEmpty ? nil : AreaPolygon(rings: rings)
        }
    }()
}

/// One polygon of the area, with the box round it; a point within five kilometres of its edge is
/// in it too, as a pier or a boat off the coast is.
private struct AreaPolygon {
    let rings: [[Double]]
    let west: Double, south: Double, east: Double, north: Double

    private static let margin = 5000.0
    private static let metresPerDegree = 111_320.0

    init(rings: [[Double]]) {
        self.rings = rings
        let outer = rings[0]
        let longitudes = stride(from: 0, to: outer.count, by: 2).map { outer[$0] }
        let latitudes = stride(from: 1, to: outer.count, by: 2).map { outer[$0] }
        west = longitudes.min() ?? 0
        east = longitudes.max() ?? 0
        south = latitudes.min() ?? 0
        north = latitudes.max() ?? 0
    }

    func holds(_ point: CLLocationCoordinate2D) -> Bool {
        let x = point.longitude, y = point.latitude
        let marginY = Self.margin / Self.metresPerDegree
        let marginX = marginY / max(cos(y / 180 * .pi), 0.01)
        guard x >= west - marginX, x <= east + marginX, y >= south - marginY, y <= north + marginY else { return false }
        if Self.contains(rings[0], x, y), !rings.dropFirst().contains(where: { Self.contains($0, x, y) }) { return true }
        return rings.contains { Self.near($0, x, y, marginX, marginY) }
    }

    /// Inside [ring], by a ray cast east.
    private static func contains(_ ring: [Double], _ x: Double, _ y: Double) -> Bool {
        var inside = false
        var j = ring.count - 2
        var i = 0
        while i + 1 < ring.count {
            let xi = ring[i], yi = ring[i + 1], xj = ring[j], yj = ring[j + 1]
            if (yi > y) != (yj > y), x < (xj - xi) * (y - yi) / (yj - yi) + xi { inside.toggle() }
            j = i
            i += 2
        }
        return inside
    }

    /// Within the margin of one of [ring]'s edges, measured in the margin's units each way.
    private static func near(_ ring: [Double], _ x: Double, _ y: Double, _ marginX: Double, _ marginY: Double) -> Bool {
        let px = x / marginX, py = y / marginY
        var i = 0
        while i + 3 < ring.count {
            let x1 = ring[i] / marginX, y1 = ring[i + 1] / marginY
            let x2 = ring[i + 2] / marginX, y2 = ring[i + 3] / marginY
            let dx = x2 - x1, dy = y2 - y1
            let length = dx * dx + dy * dy
            let t = length == 0 ? 0 : min(max(((px - x1) * dx + (py - y1) * dy) / length, 0), 1)
            let ex = px - (x1 + t * dx), ey = py - (y1 + t * dy)
            if ex * ex + ey * ey <= 1 { return true }
            i += 2
        }
        return false
    }
}
