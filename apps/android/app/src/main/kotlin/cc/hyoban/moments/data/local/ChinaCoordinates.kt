package cc.hyoban.moments.data.local

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Maps in China, Amap's among them, give their points in GCJ-02, WGS 84 shifted by a few
// hundred metres in a way the law keeps and everyone has since written down. The records
// and the map are in WGS 84, so a point from Amap is shifted back before it is kept.

/** Whether a point is where GCJ-02 shifts it: mainland China, roughly; elsewhere the two agree. */
private fun inChina(fix: Fix): Boolean = fix.longitude in 72.004..137.8347 && fix.latitude in 0.8293..55.8271

/** A WGS 84 point as a map in China would give it. */
fun wgsToGcj(fix: Fix): Fix {
    if (!inChina(fix)) return fix
    val (dLat, dLon) = shift(fix.latitude, fix.longitude)
    return Fix(fix.latitude + dLat, fix.longitude + dLon)
}

/** A GCJ-02 point back in WGS 84, to well within a metre: [wgsToGcj] undone by iterating on it. */
fun gcjToWgs(fix: Fix): Fix {
    if (!inChina(fix)) return fix
    var guess = fix
    repeat(10) {
        val there = wgsToGcj(guess)
        val dLat = there.latitude - fix.latitude
        val dLon = there.longitude - fix.longitude
        guess = Fix(guess.latitude - dLat, guess.longitude - dLon)
        if (abs(dLat) < 1e-9 && abs(dLon) < 1e-9) return guess
    }
    return guess
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
