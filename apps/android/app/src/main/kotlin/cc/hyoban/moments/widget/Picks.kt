package cc.hyoban.moments.widget

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.showcase
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.tripOf
import java.time.LocalDate
import kotlin.random.Random

// What the home screen's widgets show on a given day, kept apart from drawing them so the
// choice can be tested.

/**
 * The Moments the widgets may show, those the public sees: not private by their own say, nor
 * by their trip's. [trips] are every trip, private ones too, so a Moment in one is known.
 */
fun publicMoments(moments: List<MomentRecord>, trips: List<TripRecord>, isTripPrivate: (String) -> Boolean): List<MomentRecord> =
    moments.filter { moment -> !moment.hidden && tripOf(moment, trips)?.let { isTripPrivate(it.id) } != true }

/** A Moment from an earlier year, with the photo to show; [yearsAgo] is null when none fell on this day. */
data class Memory(val moment: MomentRecord, val media: MomentMedia, val yearsAgo: Int?)

/**
 * The Moment to remember [today]: one with a photo from this day in an earlier year, else
 * any earlier Moment with a photo, picked at random. The same one all day, and another the next.
 * [moments] are those the public sees; a photo private on its own is passed over.
 */
fun memoryFor(moments: List<MomentRecord>, today: LocalDate, hasStill: (MomentMedia) -> Boolean): Memory? {
    val todayKey = today.toString()
    val withPhoto = moments
        .filter { it.dateKey < todayKey }
        .filterNot { Entries.isPartPrivate(it, "media") }
        .mapNotNull { moment -> moment.media.firstOrNull { it.showcase && hasStill(it) }?.let { moment to it } }
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

/** The trip to show [today], of [trips] the public sees: the one under way, else the last one over, else the next to come. */
fun tripFor(trips: List<TripRecord>, today: LocalDate): Pair<TripRecord, TripPhase>? {
    val day = today.toString()
    trips.firstOrNull { day >= it.start && day <= it.end }?.let { return it to TripPhase.ONGOING }
    trips.filter { it.end < day }.maxByOrNull { it.end }?.let { return it to TripPhase.PAST }
    return trips.filter { it.start > day }.minByOrNull { it.start }?.let { it to TripPhase.UPCOMING }
}

/** The photo to show for a trip, of [moments] the public sees, passing over photos private on their own. */
fun tripCover(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>): MomentMedia? =
    coversOf(trip, moments.filterNot { Entries.isPartPrivate(it, "media") }, trips, limit = 1).firstOrNull()
