package cc.hyoban.moments.data.local

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.footprints.TrackStay
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 对齐时间: a record whose time its photos or the footprints say otherwise, what it is at and until
 * now, and what it would be: a Moment by its photos, earliest to latest; a check-in by the stay of
 * the footprints it was at, from its start to its end.
 */
data class TimeFix(
    val uid: String,
    val checkin: Boolean,
    val name: String,
    val at: String,
    val end: String?,
    val newAt: String,
    val newEnd: String?,
)

/**
 * The time a Moment's photos say, as the editor's 用照片的时间 does; none without a photo that says
 * when it was taken. A photo that says no time of its own takes the NAS's ([nas], by the photo's
 * uid), the clock's reading then, at the Moment's offset.
 */
fun photoSpan(moment: MomentRecord, nas: Map<String, LocalDateTime> = emptyMap(), zone: ZoneId = ZoneId.systemDefault()): Pair<String, String?>? {
    val offset = moment.occurredAt.takeIf { it.length > 10 }?.let { runCatching { OffsetDateTime.parse(it).offset }.getOrNull() }
    val taken = moment.media.mapNotNull { media ->
        media.takenAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?: nas[media.uid]?.let { clock -> offset?.let(clock::atOffset) ?: clock.atZone(zone).toOffsetDateTime() }
    }
    val first = taken.minByOrNull { it.toInstant() } ?: return null
    val last = taken.maxByOrNull { it.toInstant() }!!
    return stamp(first) to stamp(last).takeUnless { sameMinuteStamp(it, stamp(first)) }
}

/** A Moment whose time its photos say otherwise, planned ones aside. */
fun momentFix(moment: MomentRecord, nas: Map<String, LocalDateTime> = emptyMap()): TimeFix? {
    if (moment.planned == true) return null
    val (at, end) = photoSpan(moment, nas) ?: return null
    if (sameTime(moment.occurredAt, moment.end, at, end)) return null
    return TimeFix(moment.uid, checkin = false, moment.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), moment.occurredAt, moment.end, at, end)
}

/**
 * A check-in whose time the stay it was at says otherwise: of [stays], those its time meets within
 * [SAME_PLACE_METRES] of where it was ([point], latitude and longitude), the one it meets most.
 * Several check-ins in one stay all take its time.
 */
fun checkinFix(checkin: Checkin, point: Pair<Double, Double>?, stays: List<TrackStay>, zone: ZoneId = ZoneId.systemDefault()): TimeFix? {
    val span = checkin.span ?: return null
    point ?: return null
    val stay = stays
        .filter { it.start <= span.last && it.end >= span.first }
        .filter { greatCircleMetres(it.latitude, it.longitude, point.first, point.second) <= SAME_PLACE_METRES }
        .maxByOrNull { minOf(it.end, span.last) - maxOf(it.start, span.first) }
        ?: return null
    val at = stamp(Instant.ofEpochMilli(stay.start).atZone(zone).toOffsetDateTime())
    val end = stamp(Instant.ofEpochMilli(stay.end).atZone(zone).toOffsetDateTime())
    if (sameTime(checkin.at, checkin.end, at, end)) return null
    return TimeFix(checkin.uid, checkin = true, checkin.venue, checkin.at, checkin.end, at, end)
}

/** Whether two times, each with or without its end, are the same to the minute, as the time dialog picks them. */
private fun sameTime(at: String, end: String?, newAt: String, newEnd: String?): Boolean =
    sameMinuteStamp(at, newAt) && (if (end == null || newEnd == null) end == newEnd else sameMinuteStamp(end, newEnd))

private fun sameMinuteStamp(a: String, b: String): Boolean {
    if (a.length <= 10 || b.length <= 10) return a == b
    val first = runCatching { OffsetDateTime.parse(a).toInstant() }.getOrNull() ?: return false
    val second = runCatching { OffsetDateTime.parse(b).toInstant() }.getOrNull() ?: return false
    return first.truncatedTo(ChronoUnit.MINUTES) == second.truncatedTo(ChronoUnit.MINUTES)
}
