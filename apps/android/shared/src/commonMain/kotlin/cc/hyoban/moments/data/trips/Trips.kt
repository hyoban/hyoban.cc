package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.showcase
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Trips as the site reads them: a Moment belongs to the trip it names, or else to the
// trip whose days include it; the trip's stops are its Moments' places and its
// waypoints, in the order they happened.

/** The trip a Moment belongs to, if any. */
fun tripOf(moment: MomentRecord, trips: List<TripRecord>): TripRecord? =
    if (moment.trip != null) {
        trips.firstOrNull { it.id == moment.trip }
    } else {
        trips.firstOrNull { moment.dateKey >= it.start && moment.dateKey <= it.end }
    }

/** A trip's Moments, oldest first, each one's time read once rather than at every comparison. */
fun momentsOf(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>): List<MomentRecord> =
    moments.filter { tripOf(it, trips)?.id == trip.id }
        .map { it to epochOf(it.occurredAt) }
        .sortedWith(compareBy({ it.second }, { it.first.uid }))
        .map { it.first }

/** How many Moments a trip has, without putting them in order. */
fun countOf(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>): Int =
    moments.count { tripOf(it, trips)?.id == trip.id }

/** A trip's Moments by day, `YYYY-MM-DD`, in order. */
fun daysOf(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>): List<Pair<String, List<MomentRecord>>> =
    momentsOf(trip, moments, trips).groupBy { it.dateKey }.toList()

/** One stop on a trip's map: a place, and when the trip reached it. */
data class Stop(val placeId: String, val place: Place, val at: Long)

/**
 * The places a trip passes, in order: each Moment's places and each waypoint, by when
 * they happened. A waypoint without a time follows the one before it; the same place
 * twice in a row is one stop.
 */
fun stopsOf(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>, places: Map<String, Place>): List<Stop> {
    val events = mutableListOf<Triple<Long, Int, String>>()
    var order = 0

    for (moment in momentsOf(trip, moments, trips)) {
        val at = epochOf(moment.occurredAt)
        moment.places.forEach { events += Triple(at, order++, it) }
    }

    var previous = epochOf(trip.start)
    for (waypoint in trip.waypoints) {
        val at = waypoint.at?.let(::epochOf) ?: previous
        previous = at
        events += Triple(at, order++, waypoint.place)
    }

    return events.sortedWith(compareBy({ it.first }, { it.second }))
        .mapNotNull { (at, _, id) -> places[id]?.let { Stop(id, it, at) } }
        .fold(emptyList()) { stops, stop -> if (stops.lastOrNull()?.placeId == stop.placeId) stops else stops + stop }
}

/** The photos to show for a trip: its chosen covers, else its Moments' first showcase media. */
fun coversOf(trip: TripRecord, moments: List<MomentRecord>, trips: List<TripRecord>, limit: Int = 3): List<MomentMedia> {
    val byUid = moments.associateBy { it.uid }
    val chosen = trip.covers.mapNotNull { cover -> byUid[cover.moment]?.media?.firstOrNull { it.uid == cover.media } }

    return chosen.ifEmpty { momentsOf(trip, moments, trips).mapNotNull { moment -> moment.media.firstOrNull { it.showcase } } }.take(limit)
}

/** Milliseconds since the epoch of a timestamp or, for a day, its start in UTC+8, where the site keeps its days. */
fun epochOf(occurredAt: String): Long =
    if (occurredAt.length > 10) {
        OffsetDateTime.parse(occurredAt).toInstant().toEpochMilli()
    } else {
        LocalDate.parse(occurredAt).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
    }
