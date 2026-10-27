package cc.hyoban.moments.data.local

import cc.hyoban.moments.data.spec.Place
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// When and where a Moment written on the phone happened, worked out from its photos or
// from the phone: the earliest photo's time and the first place a photo was taken at,
// else now and where the phone is, and the known places near it to choose instead.

/** A point on the earth, from a photo or from the phone. */
data class Fix(val latitude: Double, val longitude: Double)

/** How far apart two points are, in metres, along the earth's surface. */
fun distance(a: Fix, b: Fix): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 2 * radius * asin(sqrt(h))
}

fun Place.fix() = Fix(latitude, longitude)

/** The places nearest [fix], nearest first, with how far each is. */
fun nearestPlaces(places: Map<String, Place>, fix: Fix, limit: Int = 8): List<Pair<String, Double>> =
    places.map { (id, place) -> id to distance(fix, place.fix()) }.sortedBy { it.second }.take(limit)

/** Close enough to a known place to offer it instead of a new one: a village, a park, a part of town. */
const val NEARBY_METRES = 2_000.0

/** How close a known place of the same name must be to be the one a map shares. */
const val SAME_PLACE_METRES = 300.0

/** A Moment's time as the records write it: to the second, with its offset, `+00:00` rather than `Z`. */
fun stamp(time: OffsetDateTime): String = time.truncatedTo(ChronoUnit.SECONDS).format(STAMP)

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

/** A record's time read back, a bare day as its start; null if it is neither. */
fun parseStamp(occurredAt: String, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(occurredAt) }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(occurredAt).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()

/**
 * When a photo was taken, from its EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`) and
 * the offset the camera wrote beside it, or the phone's own zone at that time when it wrote none.
 */
fun exifTime(dateTime: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime? {
    val local = dateTime?.trim()?.let { runCatching { LocalDateTime.parse(it, EXIF_TIME) }.getOrNull() } ?: return null
    val zoneOffset = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
    return if (zoneOffset != null) local.atOffset(zoneOffset) else local.atZone(zone).toOffsetDateTime()
}

private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * When a video was recorded, from its container's creation time as `MediaMetadataRetriever`
 * gives it (`yyyyMMdd'T'HHmmss`, then perhaps millis and a `Z`), always UTC, in the phone's
 * zone at that time; null for none, or the 1904 or 1970 an unset time reads as.
 */
fun videoTime(date: String?, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime? {
    val match = date?.trim()?.let(VIDEO_TIME::find) ?: return null
    val utc = runCatching { LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], VIDEO_TIME_FORMAT) }.getOrNull() ?: return null
    if (utc.year <= 1970) return null
    return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toOffsetDateTime()
}

private val VIDEO_TIME = Regex("""^(\d{8})T(\d{6})""")
private val VIDEO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/** Where a video was recorded, from its ISO 6709 location (`+31.2304+121.4737/`); null for none or 0, 0. */
fun videoFix(location: String?): Fix? {
    val match = location?.trim()?.let(VIDEO_FIX::find) ?: return null
    val fix = Fix(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    return fix.takeIf { (it.latitude != 0.0 || it.longitude != 0.0) && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
}

private val VIDEO_FIX = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

/** A place's id while it is the phone's own, until publishing gives it one spelled from its name. */
fun newPlaceId(): String = "p-" + Ulid.next().lowercase()

/** A place with no name found for it, named by where it is. */
fun coordinatesName(fix: Fix): String = "%.4f, %.4f".format(java.util.Locale.ROOT, fix.latitude, fix.longitude)
