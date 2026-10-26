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
)

const val TRACK_GAP_MS = 5 * 60 * 1000L

fun distanceMetres(a: TrackPoint, b: TrackPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Reject stale, imprecise and implausible fixes before they reach storage. */
fun acceptsFix(point: TrackPoint, previous: TrackPoint?, now: Long): Boolean =
    evaluateFix(point, previous, now) == FixDecision.ACCEPT

enum class FixDecision {
    ACCEPT, NOT_RECORDING, INVALID_COORDINATES, INACCURATE, STALE, FUTURE,
    INVALID_ALTITUDE, OUT_OF_ORDER, IMPLAUSIBLE_JUMP, STATIONARY_JITTER,
}

fun evaluateFix(point: TrackPoint, previous: TrackPoint?, now: Long): FixDecision {
    if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
        !point.longitude.isFinite() || point.longitude !in -180.0..180.0
    ) return FixDecision.INVALID_COORDINATES
    if (!point.accuracy.isFinite() || point.accuracy !in 0.0..100.0) return FixDecision.INACCURATE
    if (point.time < now - 120_000) return FixDecision.STALE
    if (point.time > now + 10_000) return FixDecision.FUTURE
    if (point.altitude?.isFinite() == false) return FixDecision.INVALID_ALTITUDE
    if (previous == null) return FixDecision.ACCEPT
    val dt = point.time - previous.time
    if (dt <= 0) return FixDecision.OUT_OF_ORDER
    if (dt > TRACK_GAP_MS) return FixDecision.ACCEPT
    val distance = distanceMetres(previous, point)
    // Permit air travel, but not a fix jumping across the country in seconds.
    if (distance > dt / 1000.0 * 400 + previous.accuracy + point.accuracy) return FixDecision.IMPLAUSIBLE_JUMP
    // Stationary jitter contributes neither distance nor thousands of identical points.
    return if (distance >= max(5.0, (previous.accuracy + point.accuracy) / 2)) FixDecision.ACCEPT else FixDecision.STATIONARY_JITTER
}

fun connected(a: TrackPoint, b: TrackPoint): Boolean =
    a.segment == b.segment && b.time > a.time && b.time - a.time <= TRACK_GAP_MS

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
fun writeGpx(writer: Writer, points: Sequence<TrackPoint>) {
    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Moments\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>\n")
    var previous: TrackPoint? = null
    points.forEach { point ->
        if (previous == null || !connected(previous, point)) {
            if (previous != null) writer.write("</trkseg>\n")
            writer.write("<trkseg>\n")
        }
        writer.write("<trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\">")
        point.altitude?.let { writer.write("<ele>$it</ele>") }
        writer.write("<time>${Instant.ofEpochMilli(point.time)}</time></trkpt>\n")
        previous = point
    }
    if (previous != null) writer.write("</trkseg>\n")
    writer.write("</trk></gpx>\n")
}
