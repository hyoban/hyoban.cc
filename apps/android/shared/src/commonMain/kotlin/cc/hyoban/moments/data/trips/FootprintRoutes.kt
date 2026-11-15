package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.footprints.Footprints
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackSelection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// A trip's route drawn from the footprints the device kept over its time, in place of the
// routers' along the roads between its stops. It is only drawn on the device: the footprints
// never leave it, and the site's route stays the routers'.

/** A trip's time as epoch milliseconds: from when it began to when it ended, the whole days where those aren't known. */
fun spanOf(trip: TripRecord, zone: ZoneId = ZoneId.systemDefault()): LongRange {
    val instant = { stamp: String? -> stamp?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } }
    val start = instant(trip.startAt) ?: LocalDate.parse(trip.start).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = instant(trip.endAt) ?: (LocalDate.parse(trip.end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
    return start..end
}

/** The footprints as a route: each recorded stretch a line of its own, simplified as the routers' are. */
fun footprintRoute(points: List<TrackPoint>): List<RouteSegment> =
    points.groupBy { it.segment }.values
        .sortedBy { it.first().time }
        .map { stretch -> Routers.simplify(stretch.map { it.longitude to it.latitude }) }
        .filter { it.size >= 2 }
        .map { RouteSegment(it, straight = false) }

/**
 * Each trip's route from the footprints, by trip id, for the trips there are any of. A trip
 * already worked out is read again only while it may still be gaining footprints (it reaches
 * past [now] less a day) or when [fresh] says what is kept has changed otherwise.
 */
class FootprintRoutes(private val footprints: Footprints) {
    private var cache = mapOf<String, Pair<LongRange, List<RouteSegment>>>()
    private var days = emptyList<String>()

    fun routes(trips: List<TripRecord>, fresh: Boolean, now: Long = System.currentTimeMillis()): Map<String, List<RouteSegment>> {
        val kept = footprints.days()
        if (fresh || kept != days) cache = emptyMap()
        days = kept
        if (kept.isEmpty()) return emptyMap()
        val first = kept.last()
        val last = kept.first()
        val next = mutableMapOf<String, Pair<LongRange, List<RouteSegment>>>()
        for (trip in trips) {
            if (trip.end < first || trip.start > last) continue
            val span = runCatching { spanOf(trip) }.getOrNull() ?: continue
            val cached = cache[trip.id]?.takeIf { it.first == span && span.last < now - DAY_MS }
            next[trip.id] = cached ?: (span to footprintRoute(footprints.points(TrackSelection(from = span.first, to = span.last))))
        }
        cache = next
        return next.mapValues { it.value.second }.filterValues { it.isNotEmpty() }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
