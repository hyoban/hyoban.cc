package cc.hyoban.moments.footprints

import java.io.Writer
import java.time.Instant
import kotlin.math.*

/** Original WGS84 fixes, kept independently of the site's places and inferred routes. */
data class TrackPoint(
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double? = null,
    val segment: String = "",
    val speed: Double? = null,
    val speedAccuracy: Double? = null,
)

const val TRACK_GAP_MS = 45_000L
const val MAX_TRACK_ACCURACY = 50.0

fun distanceMetres(a: TrackPoint, b: TrackPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

enum class FixDecision {
    ACCEPT, NOT_RECORDING, INVALID_COORDINATES, INACCURATE, STALE, FUTURE,
    INVALID_ALTITUDE, OUT_OF_ORDER,
}

/** The rest/wake policy may observe a coarse fix even when it is unsuitable for a route. */
fun evaluatePolicyFix(point: TrackPoint, now: Long): FixDecision {
    val validity = evaluateRawFix(point, now)
    if (validity != FixDecision.ACCEPT) return validity
    return if (point.accuracy > 100.0) FixDecision.INACCURATE else FixDecision.ACCEPT
}

/** Raw storage rejects malformed/expired observations, not uncertain or stationary positions. */
fun evaluateRawFix(point: TrackPoint, now: Long): FixDecision {
    if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
        !point.longitude.isFinite() || point.longitude !in -180.0..180.0
    ) return FixDecision.INVALID_COORDINATES
    if (!point.accuracy.isFinite() || point.accuracy < 0) return FixDecision.INACCURATE
    if (point.time < now - 120_000) return FixDecision.STALE
    if (point.time > now + 10_000) return FixDecision.FUTURE
    if (point.altitude?.isFinite() == false) return FixDecision.INVALID_ALTITUDE
    return FixDecision.ACCEPT
}

fun connected(a: TrackPoint, b: TrackPoint): Boolean = trackBreakReason(a, b) == null

/** Shared by rendering, totals, GPX and the on-demand diagnostic replay. */
internal fun trackBreakReason(a: TrackPoint, b: TrackPoint): String? = when {
    a.segment != b.segment -> "segment_change"
    b.time <= a.time -> "out_of_order"
    b.time - a.time > TRACK_GAP_MS -> "time_gap"
    a.accuracy > MAX_TRACK_ACCURACY || b.accuracy > MAX_TRACK_ACCURACY -> "poor_accuracy"
    !plausibleMovement(a, b) -> "speed_conflict"
    else -> null
}

/** Speed uncertainty is evidence, not an exact speed limit. Missing speed permits ordinary transport. */
internal fun plausibleMovement(a: TrackPoint, b: TrackPoint): Boolean {
    if (b.time <= a.time) return false
    return distanceMetres(a, b) <= movementAllowanceMetres(a, b)
}

internal fun movementAllowanceMetres(a: TrackPoint, b: TrackPoint): Double {
    val seconds = (b.time - a.time) / 1000.0
    val speeds = listOf(a, b).mapNotNull { point ->
        val speed = point.speed
        val error = point.speedAccuracy
        if (speed != null && speed.isFinite() && speed >= 0 && error != null && error.isFinite() && error in 0.0..2.5)
            speed + 3 * error else null
    }
    val ceiling = max(3.0, speeds.maxOrNull() ?: 55.0)
    return ceiling * seconds + a.accuracy + b.accuracy
}

data class TrackStats(val metres: Double, val recordedMillis: Long)

fun trackStats(points: List<TrackPoint>): TrackStats {
    var metres = 0.0
    var millis = 0L
    points.zipWithNext { a, b ->
        if (connected(a, b)) {
            metres += distanceMetres(a, b)
            millis += b.time - a.time
        }
    }
    return TrackStats(metres, millis)
}

/** Preserve every break and each segment's endpoints when reducing a long track for display. */
fun trackSegments(points: List<TrackPoint>, budget: Int = 8000): List<List<TrackPoint>> {
    require(budget > 0)
    val segments = mutableListOf<MutableList<TrackPoint>>()
    points.forEach { point ->
        val last = segments.lastOrNull()?.lastOrNull()
        if (last == null || !connected(last, point)) segments += mutableListOf<TrackPoint>()
        segments.last() += point
    }
    val stride = max(1, ceil(points.size.toDouble() / budget).toInt())
    return segments.map { segment -> segment.filterIndexed { index, _ -> index == 0 || index == segment.lastIndex || index % stride == 0 } }
}

/** Streaming export: does not load a lifetime of samples into memory. */
fun writeGpx(writer: Writer, points: Sequence<TrackPoint>, raw: Boolean = false) {
    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Moments\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:m=\"https://hyoban.cc/ns/footprints/1\"><trk>\n")
    var previous: TrackPoint? = null
    points.forEach { point ->
        // Raw observations are isolated waypoints: an export must not imply rejected fixes are a route.
        if (previous == null || raw || !connected(previous, point)) {
            if (previous != null) writer.write("</trkseg>\n")
            writer.write("<trkseg>\n")
        }
        writer.write("<trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">")
        point.altitude?.let { writer.write("<ele>$it</ele>") }
        writer.write("<time>${Instant.ofEpochMilli(point.time)}</time><extensions><m:accuracy>${point.accuracy}</m:accuracy>")
        if (raw) writer.write("<m:segment>${point.segment.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</m:segment>")
        point.speed?.let { writer.write("<m:speed>$it</m:speed>") }
        point.speedAccuracy?.let { writer.write("<m:speedAccuracy>$it</m:speedAccuracy>") }
        writer.write("</extensions></trkpt>\n")
        previous = point
    }
    if (previous != null) writer.write("</trkseg>\n")
    writer.write("</trk></gpx>\n")
}
