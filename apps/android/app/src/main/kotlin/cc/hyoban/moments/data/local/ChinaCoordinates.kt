package cc.hyoban.moments.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Maps in China, Amap's and Tencent's among them, give their points in GCJ-02, WGS 84
// shifted by a few hundred metres in a way the law keeps and everyone has since written
// down. The records and the map are in WGS 84, so a point from them is shifted back before
// it is kept. They shift only the mainland, Hong Kong and Macau; Taiwan and the rest of the
// world they give in WGS 84.

/**
 * Whether a point is where GCJ-02 shifts it: in the mainland, Hong Kong or Macau, or within
 * [AREA_MARGIN_METRES] of their coasts and borders, a pier or a boat off the shore. The
 * area is the city outlines' (scripts/generate-city-boundaries.mjs), a resource of its own.
 */
internal fun inChina(fix: Fix): Boolean = GcjArea.holds(fix)

/** A WGS 84 point as a map in China would give it. */
fun wgsToGcj(fix: Fix): Fix = if (inChina(fix)) shifted(fix) else fix

/** A GCJ-02 point back in WGS 84, to well within a metre: the shift undone by iterating on it. */
fun gcjToWgs(fix: Fix): Fix {
    if (!inChina(fix)) return fix
    var guess = fix
    repeat(10) {
        val there = shifted(guess)
        val dLat = there.latitude - fix.latitude
        val dLon = there.longitude - fix.longitude
        guess = Fix(guess.latitude - dLat, guess.longitude - dLon)
        if (abs(dLat) < 1e-9 && abs(dLon) < 1e-9) return guess
    }
    return guess
}

private fun shifted(fix: Fix): Fix {
    val (dLat, dLon) = shift(fix.latitude, fix.longitude)
    return Fix(fix.latitude + dLat, fix.longitude + dLon)
}

/** How far GCJ-02 moves a point, in degrees of latitude and of longitude. */
private fun shift(latitude: Double, longitude: Double): Pair<Double, Double> {
    val x = longitude - 105.0
    val y = latitude - 35.0
    var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x)) +
        (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
        (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0 +
        (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
    var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x)) +
        (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
        (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0 +
        (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
    val radians = latitude / 180.0 * PI
    val magic = 1 - ECCENTRICITY * sin(radians) * sin(radians)
    val root = sqrt(magic)
    dLat = (dLat * 180.0) / ((SEMI_MAJOR * (1 - ECCENTRICITY)) / (magic * root) * PI)
    dLon = (dLon * 180.0) / (SEMI_MAJOR / root * cos(radians) * PI)
    return dLat to dLon
}

/** Krasovsky 1940, the ellipsoid GCJ-02 is worked on. */
private const val SEMI_MAJOR = 6378245.0
private const val ECCENTRICITY = 0.00669342162296594323

/** How far off the area's edge a point is still in it, in metres: its outline is a kilometre coarse, and a coast has piers. */
private const val AREA_MARGIN_METRES = 5_000.0

/** Metres in a degree of latitude, near enough for a margin. */
private const val METRES_PER_DEGREE = 111_320.0

/**
 * The mainland, Hong Kong and Macau as one coarse outline, in WGS 84: its polygons, each
 * rings of longitude, latitude pairs, outer ring first, with the box round each.
 */
private object GcjArea {
    private class Polygon(val rings: List<DoubleArray>, val west: Double, val south: Double, val east: Double, val north: Double)

    private val polygons: List<Polygon> by lazy {
        val text = GcjArea::class.java.getResourceAsStream("/gcj-area.json")?.use { it.reader().readText() }
            ?: error("gcj-area.json is missing from the app's resources")
        val geometry = Json.parseToJsonElement(text).jsonObject
        val coordinates = geometry.getValue("coordinates").jsonArray
        val polygons = if (geometry.getValue("type").jsonPrimitive.content == "Polygon") listOf(coordinates) else coordinates.map { it.jsonArray }
        polygons.map { polygon ->
            val rings = polygon.map { ring ->
                val points = (ring as JsonArray)
                DoubleArray(points.size * 2).also { flat ->
                    points.forEachIndexed { index, point ->
                        flat[index * 2] = point.jsonArray[0].jsonPrimitive.double
                        flat[index * 2 + 1] = point.jsonArray[1].jsonPrimitive.double
                    }
                }
            }
            val outer = rings.first()
            val longitudes = outer.indices.step(2).map { outer[it] }
            val latitudes = outer.indices.step(2).map { outer[it + 1] }
            Polygon(rings, longitudes.min(), latitudes.min(), longitudes.max(), latitudes.max())
        }
    }

    fun holds(fix: Fix): Boolean {
        val x = fix.longitude
        val y = fix.latitude
        val marginY = AREA_MARGIN_METRES / METRES_PER_DEGREE
        val marginX = marginY / cos(y / 180.0 * PI).coerceAtLeast(0.01)
        val near = polygons.filter { x in it.west - marginX..it.east + marginX && y in it.south - marginY..it.north + marginY }
        if (near.any { polygon -> polygon.rings.first().contains(x, y) && polygon.rings.drop(1).none { it.contains(x, y) } }) return true
        return near.any { polygon -> polygon.rings.any { it.within(x, y, marginX, marginY) } }
    }

    /** Inside the ring, by a ray cast east. */
    private fun DoubleArray.contains(x: Double, y: Double): Boolean {
        var inside = false
        var j = size - 2
        for (i in indices step 2) {
            val xi = this[i]
            val yi = this[i + 1]
            val xj = this[j]
            val yj = this[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Within the margin of one of the ring's edges, measured in the margin's units each way. */
    private fun DoubleArray.within(x: Double, y: Double, marginX: Double, marginY: Double): Boolean {
        val px = x / marginX
        val py = y / marginY
        for (i in 0 until size - 2 step 2) {
            val x1 = this[i] / marginX
            val y1 = this[i + 1] / marginY
            val x2 = this[i + 2] / marginX
            val y2 = this[i + 3] / marginY
            val dx = x2 - x1
            val dy = y2 - y1
            val length = dx * dx + dy * dy
            val t = if (length == 0.0) 0.0 else (((px - x1) * dx + (py - y1) * dy) / length).coerceIn(0.0, 1.0)
            val ex = px - (x1 + t * dx)
            val ey = py - (y1 + t * dy)
            if (ex * ex + ey * ey <= 1.0) return true
        }
        return false
    }
}
