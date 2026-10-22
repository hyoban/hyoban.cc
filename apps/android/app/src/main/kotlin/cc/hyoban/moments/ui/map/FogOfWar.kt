package cc.hyoban.moments.ui.map

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.io.StringReader
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

/** A ring of points, flat: longitude, latitude, longitude, latitude… */
private typealias Ring = DoubleArray

/**
 * A region to light: its id, the first two digits its province's, and its polygons, each an outer
 * ring then its holes. Its middle, [cx] and [cy], is where the fog's sweep reaches it.
 */
class FogRegion(val id: String, val fogged: Boolean, val polygons: List<List<Ring>>, cx: Double? = null, cy: Double? = null) {
    /** West, south, east and north. */
    internal val bounds: DoubleArray = DoubleArray(4).apply {
        this[0] = Double.POSITIVE_INFINITY
        this[1] = Double.POSITIVE_INFINITY
        this[2] = Double.NEGATIVE_INFINITY
        this[3] = Double.NEGATIVE_INFINITY
        for (polygon in polygons) {
            val outer = polygon.firstOrNull() ?: continue
            for (index in outer.indices step 2) {
                this[0] = minOf(this[0], outer[index])
                this[1] = minOf(this[1], outer[index + 1])
                this[2] = maxOf(this[2], outer[index])
                this[3] = maxOf(this[3], outer[index + 1])
            }
        }
    }

    val cx: Double = cx ?: ((bounds[0] + bounds[2]) / 2)
    val cy: Double = cy ?: ((bounds[1] + bounds[3]) / 2)
}

/** The cities lit, by id, and how many cities and provinces they make. */
data class FogOfWar(
    val lit: List<String>,
    val cities: Int,
    val provinces: Int,
    /** How much each city lit holds: the places' weights in it, as how many Moments were written there. */
    val weights: Map<String, Int> = emptyMap(),
)

/** The regions of the site's GeoJSON collection. */
fun parseFogRegions(text: String): List<FogRegion> = parseFogRegions(StringReader(text))

/**
 * The regions of the site's GeoJSON collection, streamed: each ring straight into one flat array
 * of numbers, rather than a tree of an object for every number first, which for the 1.8 MB file
 * held tens of megabytes on the phone.
 */
fun parseFogRegions(source: Reader): List<FogRegion> = JsonReader(source).use { reader ->
    val regions = mutableListOf<FogRegion>()
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() != "features") {
            reader.skipValue()
            continue
        }
        reader.beginArray()
        while (reader.hasNext()) regions += readRegion(reader)
        reader.endArray()
    }
    reader.endObject()
    regions
}

private fun readRegion(reader: JsonReader): FogRegion {
    var id = ""
    var fogged = false
    var cx: Double? = null
    var cy: Double? = null
    var polygons: List<List<Ring>> = emptyList()
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "properties" -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "id" -> id = reader.nextString()
                        "fogged" -> fogged = reader.nextBoolean()
                        "cx" -> cx = reader.nextDouble()
                        "cy" -> cy = reader.nextDouble()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            "geometry" -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "coordinates") polygons = toPolygons(readCoordinates(reader)) else reader.skipValue()
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return FogRegion(id, fogged, polygons, cx, cy)
}

/** A point, a ring of them, or a list of either, as nested in GeoJSON's coordinates. */
private sealed interface Coordinates {
    class Point(val x: Double, val y: Double) : Coordinates
    class Line(val points: Ring) : Coordinates
    class Many(val items: List<Coordinates>) : Coordinates
}

private fun readCoordinates(reader: JsonReader): Coordinates {
    reader.beginArray()
    if (reader.peek() == JsonToken.NUMBER) {
        val point = Coordinates.Point(reader.nextDouble(), reader.nextDouble())
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return point
    }
    val first = if (reader.hasNext()) readCoordinates(reader) else null
    if (first is Coordinates.Point) {
        var points = DoubleArray(64)
        var size = 0
        var point: Coordinates.Point? = first
        while (point != null) {
            if (size + 2 > points.size) points = points.copyOf(points.size * 2)
            points[size++] = point.x
            points[size++] = point.y
            point = if (reader.hasNext()) readCoordinates(reader) as Coordinates.Point else null
        }
        reader.endArray()
        return Coordinates.Line(points.copyOf(size))
    }
    val items = listOfNotNull(first).toMutableList()
    while (reader.hasNext()) items += readCoordinates(reader)
    reader.endArray()
    return Coordinates.Many(items)
}

/** A Polygon's rings, or a MultiPolygon's polygons, as polygons of flat rings. */
private fun toPolygons(coordinates: Coordinates): List<List<Ring>> {
    val items = (coordinates as? Coordinates.Many)?.items.orEmpty()
    return if (items.firstOrNull() is Coordinates.Line) {
        listOf(items.map { (it as Coordinates.Line).points })
    } else {
        items.map { polygon -> (polygon as Coordinates.Many).items.map { (it as Coordinates.Line).points } }
    }
}

/**
 * The regions holding any of [places], as `(longitude, latitude)`, and how much each holds: the
 * [weights] of its places, in their order, 1 each without them.
 */
fun buildFogOfWar(
    regions: List<FogRegion>,
    places: Iterable<Pair<Double, Double>>,
    weights: Iterable<Int>? = null,
): FogOfWar {
    val candidates = regions.filterNot { it.fogged }
    val lit = sortedMapOf<String, Int>()
    val weighed = places.zip(weights ?: places.map { 1 })
    for ((place, weight) in weighed) {
        val (x, y) = place
        val found = candidates.firstOrNull { region ->
            val b = region.bounds
            x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] && region.contains(x, y)
        } ?: nearest(candidates, x, y)
        found?.let { lit[it.id] = (lit[it.id] ?: 0) + weight }
    }
    return FogOfWar(lit.keys.toList(), lit.size, lit.keys.map { it.take(2) }.toSet().size, lit.toMap())
}

/** Inside one of the region's polygons: inside its outer ring and none of its holes. */
private fun FogRegion.contains(x: Double, y: Double) = polygons.any { polygon ->
    val outer = polygon.firstOrNull() ?: return@any false
    outer.contains(x, y) && polygon.drop(1).none { it.contains(x, y) }
}

/** Even-odd: whether a ray east from the point crosses the ring an odd number of times. */
private fun Ring.contains(x: Double, y: Double): Boolean {
    var inside = false
    var previous = size - 2
    for (index in indices step 2) {
        val x1 = this[index]
        val y1 = this[index + 1]
        val x2 = this[previous]
        val y2 = this[previous + 1]
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
        for (ring in region.polygons.flatten()) for (index in 2 until ring.size step 2) {
            val distance = segmentDistance(x, y, ring[index - 2], ring[index - 1], ring[index], ring[index + 1])
            if (distance < best) {
                best = distance
                nearest = region
            }
        }
    }
    return nearest
}

private fun segmentDistance(x: Double, y: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val length = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    val t = if (length == 0.0) 0.0 else (((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / length).coerceIn(0.0, 1.0)
    return hypot(x - (x1 + t * (x2 - x1)), y - (y1 + t * (y2 - y1)))
}
