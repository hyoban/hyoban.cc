package cc.hyoban.moments.data.local

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The earth's mean radius, in metres, every distance on it is reckoned by. */
const val EARTH_RADIUS_METRES = 6_371_000.0

/**
 * The great-circle (haversine) distance between two points, in metres: the one way the app
 * measures along the earth, its rounding kept from asking the square root of more than 1.
 */
fun greatCircleMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_METRES * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
