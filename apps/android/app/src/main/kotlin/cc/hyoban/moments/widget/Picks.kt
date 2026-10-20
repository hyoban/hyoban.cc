package cc.hyoban.moments.widget

import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import java.time.LocalDate
import kotlin.random.Random

// What the home screen's widgets show on a given day, kept apart from drawing them so the
// choice can be tested.

/** A Moment from an earlier year, with the photo to show; [yearsAgo] is null when none fell on this day. */
data class Memory(val moment: MomentRecord, val media: MomentMedia, val yearsAgo: Int?)

/**
 * The Moment to remember [today]: one with a photo from this day in an earlier year, else
 * any earlier Moment with a photo, picked at random. The same one all day, and another the next.
 */
fun memoryFor(moments: List<MomentRecord>, today: LocalDate, hasStill: (MomentMedia) -> Boolean): Memory? {
    val todayKey = today.toString()
    val withPhoto = moments
        .filter { it.dateKey < todayKey }
        .mapNotNull { moment -> moment.media.firstOrNull(hasStill)?.let { moment to it } }
        .sortedBy { it.first.uid }
    if (withPhoto.isEmpty()) return null

    val monthDay = todayKey.substring(4)
    val onThisDay = withPhoto.filter { it.first.dateKey.endsWith(monthDay) }
    val pool = onThisDay.ifEmpty { withPhoto }
    // At random, seeded by the day, so the widget doesn't change each time it is drawn.
    val (moment, media) = pool[Random(today.toEpochDay()).nextInt(pool.size)]
    val yearsAgo = if (onThisDay.isEmpty()) null else today.year - moment.dateKey.take(4).toInt()

    return Memory(moment, media, yearsAgo)
}

/** Where a trip stands on a day: under way, over, or still to come. */
enum class TripPhase { ONGOING, PAST, UPCOMING }

/** The trip to show [today]: the one under way, else the last one over, else the next to come. */
fun tripFor(trips: List<TripRecord>, today: LocalDate): Pair<TripRecord, TripPhase>? {
    val day = today.toString()
    trips.firstOrNull { day >= it.start && day <= it.end }?.let { return it to TripPhase.ONGOING }
    trips.filter { it.end < day }.maxByOrNull { it.end }?.let { return it to TripPhase.PAST }
    return trips.filter { it.start > day }.minByOrNull { it.start }?.let { it to TripPhase.UPCOMING }
}
