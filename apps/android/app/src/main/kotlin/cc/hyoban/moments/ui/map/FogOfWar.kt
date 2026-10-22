package cc.hyoban.moments.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.hypot

// The map's fog of war, as the site's (packages/trip-map/src/fog-of-war.ts): every city
// under it but those a Moment was written in. The cities are the site's file, shipped as
// an asset, each a region with an id, and one `fogged`, the rest of the world, never lit;
// the map draws the fog as the regions not lit, so only which ids are lit is worked out
// here. A place on no city, on the coast the boundaries were simplified past, lights the
// nearest within reach.

/** The cities, read by MapLibre from the app's assets. */
const val FOG_REGIONS_URI = "asset://cities.json"
const val FOG_REGIONS_ASSET = "cities.json"

/** How far, in degrees, a place on no city may be from the nearest one's edge: about 10 km. */
private const val REACH = 0.1

/** A ring of `[longitude, latitude]` points. */
private typealias Ring = List<DoubleArray>

/** A region to light: its id, the first two digits its province's, and its polygons, each an outer ring then its holes. */
class FogRegion(val id: String, val fogged: Boolean, val polygons: List<List<Ring>>) {
    /** West, south, east and north. */
    internal val bounds: DoubleArray = DoubleArray(4).apply {
        this[0] = Double.POSITIVE_INFINITY
        this[1] = Double.POSITIVE_INFINITY
        this[2] = Double.NEGATIVE_INFINITY
        this[3] = Double.NEGATIVE_INFINITY
        for (polygon in polygons) for (point in polygon.firstOrNull().orEmpty()) {
            this[0] = minOf(this[0], point[0])
            this[1] = minOf(this[1], point[1])
            this[2] = maxOf(this[2], point[0])
            this[3] = maxOf(this[3], point[1])
        }
    }
}

/** The cities lit, by id, and how many cities and provinces they make. */
data class FogOfWar(val lit: List<String>, val cities: Int, val provinces: Int)

/** The regions of the site's GeoJSON collection. */
fun parseFogRegions(text: String): List<FogRegion> =
    Json.parseToJsonElement(text).jsonObject.getValue("features").jsonArray.map { element ->
        val feature = element.jsonObject
        val properties = feature.getValue("properties").jsonObject
        val geometry = feature.getValue("geometry").jsonObject
        val coordinates = geometry.getValue("coordinates").jsonArray
        val polygons = if (geometry.getValue("type").jsonPrimitive.content == "Polygon") listOf(coordinates) else coordinates.map { it.jsonArray }
        FogRegion(
            id = properties.getValue("id").jsonPrimitive.content,
            fogged = (properties["fogged"] as? JsonPrimitive)?.booleanOrNull == true,
            polygons = polygons.map { polygon -> polygon.map { ring -> ring.jsonArray.map(::toPoint) } },
        )
    }

private fun toPoint(element: JsonElement): DoubleArray =
    (element as JsonArray).let { doubleArrayOf(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }

/** The regions holding any of [places], as `(longitude, latitude)`. */
fun buildFogOfWar(regions: List<FogRegion>, places: Iterable<Pair<Double, Double>>): FogOfWar {
    val candidates = regions.filterNot { it.fogged }
    val lit = sortedSetOf<String>()
    for ((x, y) in places) {
        val found = candidates.firstOrNull { region ->
            val b = region.bounds
            x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] && region.contains(x, y)
        } ?: nearest(candidates, x, y)
        found?.let { lit += it.id }
    }
    return FogOfWar(lit.toList(), lit.size, lit.map { it.take(2) }.toSet().size)
}

/** Inside one of the region's polygons: inside its outer ring and none of its holes. */
private fun FogRegion.contains(x: Double, y: Double) = polygons.any { polygon ->
    val outer = polygon.firstOrNull() ?: return@any false
    outer.contains(x, y) && polygon.drop(1).none { it.contains(x, y) }
}

/** Even-odd: whether a ray east from the point crosses the ring an odd number of times. */
private fun Ring.contains(x: Double, y: Double): Boolean {
    var inside = false
    var previous = lastIndex
    for (index in indices) {
        val (x1, y1) = this[index]
        val (x2, y2) = this[previous]
        if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) inside = !inside
        previous = index
    }
    return inside
}

/** The region with an edge nearest the point, within [REACH] of it. */
private fun nearest(regions: List<FogRegion>, x: Double, y: Double): FogRegion? {
    var nearest: FogRegion? = null
    var best = REACH
    for (region in regions) {
        val b = region.bounds
        if (x < b[0] - best || x > b[2] + best || y < b[1] - best || y > b[3] + best) continue
        for (ring in region.polygons.flatten()) for (index in 1 until ring.size) {
            val distance = segmentDistance(x, y, ring[index - 1], ring[index])
            if (distance < best) {
                best = distance
                nearest = region
            }
        }
    }
    return nearest
}

private fun segmentDistance(x: Double, y: Double, from: DoubleArray, to: DoubleArray): Double {
    val (x1, y1) = from
    val (x2, y2) = to
    val length = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    val t = if (length == 0.0) 0.0 else (((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / length).coerceIn(0.0, 1.0)
    return hypot(x - (x1 + t * (x2 - x1)), y - (y1 + t * (y2 - y1)))
}
