package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TravelMode
import cc.hyoban.moments.data.spec.TripRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// A trip played back as the site's player plays it (packages/trip-map/src/player), worked
// out once without the screen: every place the route runs through in visiting order, each
// Moment's waypoints then its own places, as `buildTrip` lays them; the cards, an opening,
// each day's opener before its first Moment, each Moment and a look back, as `buildSlides`
// does; how long each lasts and how long each drive takes to draw. The player keeps the
// time and draws; this answers every question about the route.

/**
 * The route as one line, walked along by its length as the map draws it: the trip's
 * stretches as `GET /v1/routes` serves them, one after another. A ferry or a flight taken
 * both ways is served once, so where one stretch doesn't start where the last ended, the
 * way between is taken straight, as that crossing was.
 */
class PlaybackRoute(segments: List<RouteSegment>) {
    /** Every point, (longitude, latitude), in order. */
    val points: List<Pair<Double, Double>>

    /** Whether the stretch from each point to the next is a crossing, by train, plane or ferry. */
    private val straight: BooleanArray

    /** How far along the line each point is, in Mercator degrees. */
    private val along: DoubleArray

    val length: Double get() = along.lastOrNull() ?: 0.0

    init {
        val points = mutableListOf<Pair<Double, Double>>()
        val straight = mutableListOf<Boolean>()
        for (segment in segments) {
            segment.line.forEachIndexed { index, point ->
                if (index == 0 && points.lastOrNull() == point) return@forEachIndexed
                if (points.isNotEmpty()) straight += segment.straight || index == 0
                points += point
            }
        }
        this.points = points
        this.straight = straight.toBooleanArray()
        along = DoubleArray(points.size)
        for (index in 1 until points.size) {
            along[index] = along[index - 1] + distance(points[index - 1], points[index])
        }
    }

    /** The edge [at] falls on: the index of the point it starts from. */
    private fun edgeAt(at: Double): Int {
        val found = along.binarySearch(at.coerceIn(0.0, length))
        val index = if (found >= 0) found else -found - 2
        return index.coerceIn(0, maxOf(points.size - 2, 0))
    }

    /** The point [at] along the line. */
    fun pointAt(at: Double): Pair<Double, Double> {
        if (points.size < 2) return points.firstOrNull() ?: (0.0 to 0.0)
        val edge = edgeAt(at)
        val span = along[edge + 1] - along[edge]
        val t = if (span == 0.0) 1.0 else ((at - along[edge]) / span).coerceIn(0.0, 1.0)
        val (x0, y0) = points[edge]
        val (x1, y1) = points[edge + 1]
        return (x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t)
    }

    /** The line from [from] to [to] along it, its ends included. */
    fun line(from: Double, to: Double): List<Pair<Double, Double>> {
        if (points.size < 2 || to <= from) return listOf(pointAt(from))
        val first = edgeAt(from)
        val last = edgeAt(to)
        return listOf(pointAt(from)) + (first + 1..last).map { points[it] } + pointAt(to)
    }

    /**
     * How far along the line [place] is reached, at or after [after]: where the line comes
     * nearest it, the first time when it passes as near more than once, as a trip back the
     * same way does.
     */
    fun reach(place: Pair<Double, Double>, after: Double): Double {
        if (points.size < 2) return after
        val target = mercator(place)
        val candidates = (edgeAt(after) until points.size - 1).map { edge ->
            val a = mercator(points[edge])
            val b = mercator(points[edge + 1])
            val dx = b.first - a.first
            val dy = b.second - a.second
            val span = dx * dx + dy * dy
            val t = if (span == 0.0) 0.0 else (((target.first - a.first) * dx + (target.second - a.second) * dy) / span).coerceIn(0.0, 1.0)
            val gap = hypot(a.first + dx * t - target.first, a.second + dy * t - target.second)
            gap to maxOf(along[edge] + (along[edge + 1] - along[edge]) * t, after)
        }
        val nearest = candidates.minOf { it.first }
        return candidates.first { it.first <= nearest + NEAR_ENOUGH }.second
    }

    /** How far the trip drove, in kilometres, leaving out what it took a train, a plane or a ferry for. */
    fun drivenKm(): Double = (1 until points.size).sumOf { index ->
        if (straight[index - 1]) 0.0 else kilometres(points[index - 1], points[index])
    }

    private companion object {
        /** About 5 km: a place the route passes again within this of its nearest is reached the first time. */
        const val NEAR_ENOUGH = 0.05
    }
}

/** How a leg is travelled, which the route's head shows: a car, or the leg's train, plane or ferry. */
enum class Travel { CAR, FERRY, PLANE, TRAIN }

/** A day of the trip and, when known, its time, `HH:mm`: where the route's clock stands. */
data class PlaybackClock(val dateKey: String, val day: Int, val time: String?)

/**
 * A place the route runs through: a Moment's own, or a waypoint passed on the way to one;
 * when the route passes it, and how far along the route that is.
 */
data class PlaybackPoint(
    val id: String,
    val place: Place,
    val clock: PlaybackClock,
    val stopIndex: Int,
    val waypoint: Boolean,
    val at: Double,
) {
    val coordinates get() = place.longitude to place.latitude
}

/** A place passed on the way to a Moment, by its name, and when. */
data class PlaybackWaypoint(val name: String, val clock: PlaybackClock) {
    val day get() = clock.day
}

/** A Moment with a place on the trip, and its waypoints before it, in the order they were passed. */
data class PlaybackStop(val moment: MomentRecord, val day: Int, val places: List<Place>, val waypoints: List<PlaybackWaypoint>) {
    /** The photos it shows in turn: its stills, else its videos' posters. */
    val photos: List<MomentMedia> get() = moment.media.filter { it.type != MediaType.VIDEO }.ifEmpty { moment.media }
}

/** One card of a trip's playback, as the site's `TripSlide`. */
sealed interface PlaybackSlide {
    /** The trip's title, dates and covers, over its whole route. */
    data object Intro : PlaybackSlide

    /**
     * A day's opener before its first Moment: its date and the places ahead. A day with only
     * waypoints, driving through without a Moment, is [waypointsOnly], and passes them while
     * it is up. [waypointsBefore] are the waypoints passed before it shows, as the first and
     * how many, counted from its stop's first point.
     */
    data class Day(
        val dateKey: String,
        val day: Int,
        val places: List<String>,
        val stopIndex: Int,
        val waypointsBefore: Pair<Int, Int>,
        val waypointsOnly: Boolean = false,
    ) : PlaybackSlide

    data class Stop(val stop: PlaybackStop, val stopIndex: Int) : PlaybackSlide

    /** The trip looked back on; playback ends here. */
    data object Summary : PlaybackSlide
}

// The site's pacing (slides.ts, route-drawer.ts, player.ts), in milliseconds.
private const val INTRO_MS = 3_000L
private const val DAY_MS = 1_600L
private const val DAY_MS_PER_CHAR = 30L
private const val DAY_MAX_MS = 2_800L
private const val STOP_MS = 3_000L
private const val PHOTO_MS = 1_500L
private const val CHARACTER_MS = 120L
private const val SHORT_LEG_MS = 1_500.0
private const val LEG_MS = 2_500.0
private const val LONG_LEG_KM = 400.0

/** How long a waypoint's name shows before driving on. */
const val WAYPOINT_PAUSE_MS = 1_000L

/** The closest the camera comes for a trip or a place on its own: a city's scale. */
const val STOP_ZOOM = 9.0

/** The closest the camera comes following a short drive. */
const val NEARBY_MAX_ZOOM = 13.0

/** A trip's playback: the places its route runs through, its Moments and its cards. */
class TripPlayback(
    val route: PlaybackRoute,
    val points: List<PlaybackPoint>,
    val stops: List<PlaybackStop>,
    val slides: List<PlaybackSlide>,
    /** How each leg, from a point to the next, is travelled. */
    private val modes: List<Travel>,
) {
    /** Index into [points] just past each stop's last place. */
    private val stopPointEnds = stops.runningFold(0) { end, stop -> end + stop.waypoints.size + stop.places.size }.drop(1)

    /** A stop's first point: its first waypoint, or else its first place. */
    fun pointStart(stopIndex: Int) = if (stopIndex == 0) 0 else stopPointEnds[stopIndex - 1]

    /** A stop's first place, after the waypoints leading to it. */
    fun placeStart(stopIndex: Int) = pointStart(stopIndex) + (stops.getOrNull(stopIndex)?.waypoints?.size ?: 0)

    /** The last point a stop's card drives on to: the next place, or the route's end. */
    fun onwardEnd(stopIndex: Int) = min(stopPointEnds[stopIndex], points.size - 1)

    /** The line from `points[from]` to `points[to - 1]`, along roads where the route has them. */
    fun line(from: Int, to: Int): List<Pair<Double, Double>> {
        val start = points.getOrNull(from) ?: return emptyList()
        val end = points.getOrNull(to - 1) ?: start
        return if (end.at > start.at) route.line(start.at, end.at) else listOf(start.coordinates)
    }

    /** How the leg from `points[from]` on is travelled. */
    fun travel(from: Int): Travel = modes.getOrElse(from) { Travel.CAR }

    /** The waypoints the map passes before a card shows, as the first point and how many. */
    fun waypointsBefore(slide: PlaybackSlide): Pair<Int, Int> = when (slide) {
        PlaybackSlide.Intro -> 0 to 0
        PlaybackSlide.Summary -> (stopPointEnds.lastOrNull() ?: 0) to (points.size - (stopPointEnds.lastOrNull() ?: 0))
        is PlaybackSlide.Day -> pointStart(slide.stopIndex) + slide.waypointsBefore.first to slide.waypointsBefore.second
        is PlaybackSlide.Stop -> {
            val stop = stops[slide.stopIndex]
            val opensDay = stops.getOrNull(slide.stopIndex - 1)?.day != stop.day
            val earlier = if (opensDay) stop.earlierDayWaypoints() else 0
            pointStart(slide.stopIndex) + earlier to stop.waypoints.size - earlier
        }
    }

    /** How long drawing the route from `points[from]` to `points[to - 1]` takes: longer for a longer drive, as distance feels. */
    fun driveTime(from: Int, to: Int): Long {
        val start = points.getOrNull(from) ?: return 0
        val end = points.getOrNull(to - 1) ?: return 0
        val km = kilometres(start.coordinates, end.coordinates, great = true)
        return (SHORT_LEG_MS + (LEG_MS - SHORT_LEG_MS) * sqrt(min(km / LONG_LEG_KM, 1.0))).toLong()
    }

    /**
     * The day and time where the route's tip is, [fraction] of the way from `points[from]` to
     * `points[to]`, the clock running on between their times, through midnight into the next
     * day; without both times, or across more than a night, it keeps the departure's until it arrives.
     */
    fun clock(from: Int, to: Int = from, fraction: Double = 0.0): PlaybackClock? {
        val departure = points.getOrNull(from)?.clock ?: return null
        val arrival = points.getOrNull(to)?.clock ?: departure
        if (fraction >= 1) return arrival
        val nights = arrival.day - departure.day
        val start = minutes(departure.time)
        val end = minutes(arrival.time)
        if (fraction <= 0 || start == null || end == null || nights < 0 || nights > 1 || end + nights * DAY_MINUTES < start) return departure
        val passed = (start + (end + nights * DAY_MINUTES - start) * fraction).toInt()
        val clock = passed % DAY_MINUTES
        val time = "%d:%02d".format(clock / 60, clock % 60)
        return (if (passed >= DAY_MINUTES) arrival else departure).copy(time = time)
    }

    private fun minutes(time: String?): Int? =
        time?.let { Regex("""^(\d{1,2}):(\d{2})$""").find(it) }?.destructured?.let { (h, m) -> h.toInt() * 60 + m.toInt() }

    companion object {
        private const val DAY_MINUTES = 24 * 60

        /** How long a card stays up, its drive there aside; none for the look back, where playback ends. */
        fun duration(slide: PlaybackSlide): Long? = when (slide) {
            PlaybackSlide.Intro -> INTRO_MS
            is PlaybackSlide.Day -> min(DAY_MS + slide.places.joinToString("").length * DAY_MS_PER_CHAR, DAY_MAX_MS)
            is PlaybackSlide.Stop -> maxOf(STOP_MS, PHOTO_MS * slide.stop.photos.size, CHARACTER_MS * slide.stop.moment.text.length)
            PlaybackSlide.Summary -> null
        }

        /** The photo showing [share] of the way through a stop's photos, each given the same time. */
        fun photoAt(stop: PlaybackStop, share: Double): Int {
            val count = stop.photos.size
            return if (count <= 1) 0 else (share * count).toInt().coerceIn(0, count - 1)
        }
    }
}

/** How many of a stop's waypoints were passed on an earlier day; they come first. */
private fun PlaybackStop.earlierDayWaypoints() = waypoints.count { it.day < day }

/**
 * A trip's playback from its Moments, oldest first, the places they name and its route's
 * stretches; without a route, its places are joined straight, as its map draws them. As the
 * site takes them, only the Moments with a place within the trip's days are stops.
 */
fun tripPlayback(trip: TripRecord, moments: List<MomentRecord>, places: Map<String, Place>, segments: List<RouteSegment>): TripPlayback {
    // The waypoints in their order, each one without a time taking the one before's.
    var previous = trip.start
    val waypoints = trip.waypoints.mapNotNull { waypoint ->
        val at = waypoint.at ?: previous
        previous = at
        places[waypoint.place]?.let { Triple(waypoint.place, it, at) }
    }
    var next = 0
    val stopPlaces = mutableListOf<Pair<List<Pair<String, Place>>, List<Pair<String, Place>>>>()
    val stops = moments
        .filter { moment -> moment.dateKey >= trip.start && moment.dateKey <= trip.end && moment.places.any { it in places } }
        .map { moment ->
            // On the same day, a waypoint comes before a Moment that has no time.
            val before = mutableListOf<Triple<String, Place, String>>()
            while (next < waypoints.size && (
                    if (moment.occurredAt.length <= 10) waypoints[next].third.take(10) <= moment.dateKey
                    else compareTimes(waypoints[next].third, moment.occurredAt) <= 0
                    )
            ) before += waypoints[next++]
            val own = moment.places.mapNotNull { id -> places[id]?.let { id to it } }
            stopPlaces += before.map { it.first to it.second } to own
            PlaybackStop(moment, dayOf(trip.start, moment.dateKey), own.map { it.second }, before.map { PlaybackWaypoint(it.second.name, clockOf(trip.start, it.third)) })
        }
    val trailing = waypoints.drop(next)

    // Every point in visiting order: each stop's waypoints, then its places; the waypoints after the last.
    data class Visit(val id: String, val place: Place, val clock: PlaybackClock, val stopIndex: Int, val waypoint: Boolean)
    val visits = stops.flatMapIndexed { index, stop ->
        val (before, own) = stopPlaces[index]
        before.mapIndexed { n, (id, place) -> Visit(id, place, stop.waypoints[n].clock, index, true) } +
            own.map { (id, place) -> Visit(id, place, PlaybackClock(stop.moment.dateKey, stop.day, timeOf(stop.moment.occurredAt)), index, false) }
    } + trailing.map { (id, place, at) -> Visit(id, place, clockOf(trip.start, at), stops.size, true) }

    val line = segments.ifEmpty {
        if (visits.size > 1) listOf(RouteSegment(visits.map { it.place.longitude to it.place.latitude }, straight = false)) else emptyList()
    }
    val route = PlaybackRoute(line)
    var at = 0.0
    val points = visits.map { visit ->
        at = route.reach(visit.place.longitude to visit.place.latitude, at)
        PlaybackPoint(visit.id, visit.place, visit.clock, visit.stopIndex, visit.waypoint, at)
    }
    // A leg by train, plane or ferry where the trip says so, either way round; else driven.
    val modes = points.zipWithNext { from, to ->
        when (trip.straightLegs.firstOrNull { (it.from == from.id && it.to == to.id) || (it.from == to.id && it.to == from.id) }?.by) {
            TravelMode.FERRY -> Travel.FERRY
            TravelMode.PLANE -> Travel.PLANE
            TravelMode.TRAIN -> Travel.TRAIN
            null -> Travel.CAR
        }
    }
    return TripPlayback(route, points, stops, slidesOf(stops), modes)
}

/**
 * An opening, then each day's opener before its first stop, then a look back. A day with
 * only waypoints, driving through without a Moment, gets an opener of its own.
 */
private fun slidesOf(stops: List<PlaybackStop>): List<PlaybackSlide> = buildList {
    add(PlaybackSlide.Intro)
    stops.forEachIndexed { stopIndex, stop ->
        val slide = PlaybackSlide.Stop(stop, stopIndex)
        val previousDay = stops.getOrNull(stopIndex - 1)?.day
        if (previousDay == stop.day) {
            add(slide)
            return@forEachIndexed
        }
        // Each day between the last stop's and this one with waypoints is a travel day.
        val earlier = stop.waypoints.take(stop.earlierDayWaypoints())
        val travelDays = earlier.map { it.day }.distinct().filter { previousDay == null || it > previousDay }
        fun firstOfDay(day: Int) = earlier.indexOfFirst { it.day >= day }
        travelDays.forEachIndexed { n, day ->
            val from = if (n == 0) 0 else firstOfDay(travelDays[n - 1])
            val ofDay = earlier.indices.filter { earlier[it].day == day }
            add(
                PlaybackSlide.Day(
                    earlier[ofDay.first()].clock.dateKey,
                    day,
                    ofDay.map { earlier[it].name }.distinct(),
                    stopIndex,
                    from to firstOfDay(day) - from,
                    waypointsOnly = true,
                ),
            )
        }
        val ownFrom = if (travelDays.isNotEmpty()) firstOfDay(travelDays.last()) else 0
        val dayStops = stops.filter { it.day == stop.day }
        // Waypoints from an earlier day, leaving the night before, aren't this day's places.
        val dayPlaces = dayStops.flatMap { item ->
            item.waypoints.filter { it.day == stop.day }.map { it.name } + item.places.map { it.name }
        }.distinct()
        add(PlaybackSlide.Day(dayStops.first().moment.dateKey, stop.day, dayPlaces, stopIndex, ownFrom to earlier.size - ownFrom))
        add(slide)
    }
    add(PlaybackSlide.Summary)
}

/** The day of the trip and the time of a timestamp or a day. */
private fun clockOf(start: String, at: String) = PlaybackClock(at.take(10), dayOf(start, at.take(10)), timeOf(at))

private fun timeOf(at: String) = if (at.length > 10) at.substring(11, 16) else null

/** Day [day] of a trip that starts on [start], the first being 1. */
private fun dayOf(start: String, day: String) = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(day)).toInt() + 1

/** Two timestamps or days in order, a day before a time on it, as the site compares them. */
private fun compareTimes(first: String, second: String): Int {
    val days = first.take(10).compareTo(second.take(10))
    if (days != 0 || first.length <= 10 || second.length <= 10) return if (days != 0) days else first.length - second.length
    return epochOf(first).compareTo(epochOf(second))
}

/** A place's name without what's in brackets, for labels with little room: 眼镜烤肉（老街店） → 眼镜烤肉. */
fun shortPlaceName(name: String): String =
    name.replace(Regex("""[（(][^（）()]*[）)]"""), " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { name }

// Latitudes nearer the poles than this are drawn as if at it, as Web Mercator does.
private const val MAX_LATITUDE = 85.051129

/** Mercator, scaled so a degree of longitude stays a unit, so the tip's speed is even on the map. */
private fun mercator(point: Pair<Double, Double>): Pair<Double, Double> {
    val radians = point.second.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180
    return point.first to ln(tan(PI / 4 + radians / 2)) * 180 / PI
}

private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val (x0, y0) = mercator(a)
    val (x1, y1) = mercator(b)
    return hypot(x1 - x0, y1 - y0)
}

/** Kilometres between two points: near enough flat for a road's short stretches, else along the great circle. */
private fun kilometres(a: Pair<Double, Double>, b: Pair<Double, Double>, great: Boolean = false): Double {
    if (great) {
        val radians = PI / 180
        val dLat = (b.second - a.second) * radians
        val dLon = (b.first - a.first) * radians
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.second * radians) * cos(b.second * radians) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(h))
    }
    val latitude = (a.second + b.second) / 2 * PI / 180
    return hypot((b.first - a.first) * cos(latitude), b.second - a.second) * 111.32
}
