package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.PlaceGroup
import cc.hyoban.moments.data.spec.PlaceKind
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The observations supporting a visit, shared by candidate selection, naming and fallback. */
object TripEvidence {
    private const val STAY_KM = 30.0
    private const val STAY_MS = 90 * 60 * 1000L
    private const val OBSERVATION_GAP_MS = 6 * 60 * 60 * 1000L
    private const val DISTINCT_STOP_KM = 0.5
    private const val SHORT_VISIT_MS = 20 * 60 * 1000L
    private const val LOCAL_SPEED_KMH = 15.0
    private const val PRESENCE_DAYS = 3

    private val ACTIVITY_GROUPS = setOf(PlaceGroup.SCENIC, PlaceGroup.FOOD, PlaceGroup.SHOPPING, PlaceGroup.STAY, PlaceGroup.LEISURE)
    private val Sighting.isTransport: Boolean get() = PlaceKind.of(place?.kind)?.group == PlaceGroup.TRANSPORT

    /** Keep every still-valid start: leaving one anchor's radius need not leave a later one's. */
    fun stay(sightings: List<Sighting>): List<Sighting> {
        val starts = mutableListOf<Int>()
        for (index in sightings.indices) {
            val here = sightings[index]
            if (here.isTransport ||
                index > 0 && here.at - sightings[index - 1].at > OBSERVATION_GAP_MS) starts.clear()
            if (here.isTransport) continue
            starts.removeAll { start ->
                val anchor = sightings[start]
                distanceKm(anchor.lat, anchor.lng, here.lat, here.lng) > STAY_KM
            }
            starts.firstOrNull()?.let { start ->
                if (here.at - sightings[start].at >= STAY_MS) return sightings.subList(start, index + 1)
            }
            starts += index
        }
        return emptyList()
    }

    /** Transport observations remain barriers, rather than disappearing from the timeline. */
    fun activity(sightings: List<Sighting>): List<Sighting> {
        var start = 0
        for (index in 0..sightings.size) {
            if (index == sightings.size || sightings[index].isTransport) {
                val evidence = localActivity(sightings.subList(start, index))
                if (evidence.isNotEmpty()) return evidence
                start = index + 1
            }
        }
        return emptyList()
    }

    /** Positive evidence of a visit, without guessing a place's type from its name. */
    private fun localActivity(points: List<Sighting>): List<Sighting> {
        points.firstOrNull { PlaceKind.of(it.place?.kind)?.group in ACTIVITY_GROUPS }
            ?.let { return listOf(it) }
        stay(points).takeIf { it.isNotEmpty() }?.let { return it }
        return dayPresence(points)
            .ifEmpty { overnightPresence(points) }
            .ifEmpty { shortVisit(points) }
    }

    /** Presence on one day does not assume a continuous stay through a quiet afternoon. */
    private fun dayPresence(points: List<Sighting>): List<Sighting> {
        for (day in points.groupBy { it.day }.values) {
            val first = day.first()
            day.firstOrNull { point ->
                point.at - first.at >= STAY_MS &&
                    distanceKm(first.lat, first.lng, point.lat, point.lng) in DISTINCT_STOP_KM..STAY_KM
            }?.let { return listOf(first, it) }
        }
        return emptyList()
    }

    private fun overnightPresence(points: List<Sighting>): List<Sighting> =
        points.zipWithNext().firstOrNull { (before, after) ->
            LocalDate.parse(after.day).toEpochDay() - LocalDate.parse(before.day).toEpochDay() == 1L &&
                after.at - before.at >= STAY_MS &&
                distanceKm(before.lat, before.lng, after.lat, after.lng) <= STAY_KM
        }?.let { (before, after) -> listOf(before, after) }.orEmpty()

    private fun shortVisit(points: List<Sighting>): List<Sighting> {
        // A short walk between several stops can be a visit too. A platform and its station,
        // repeated timestamps, long silence and fast travel cannot establish one.
        val first = points.firstOrNull() ?: return emptyList()
        var anchor = first
        var previous = first
        val stops = mutableListOf(first)
        var distance = 0.0
        for (here in points.drop(1)) {
            if (here.at - previous.at > OBSERVATION_GAP_MS || here.at - anchor.at > OBSERVATION_GAP_MS ||
                distanceKm(anchor.lat, anchor.lng, here.lat, here.lng) > STAY_KM) {
                anchor = here
                stops.clear()
                stops += here
                distance = 0.0
            } else {
                distance += distanceKm(previous.lat, previous.lng, here.lat, here.lng)
                if (stops.size < 3 && stops.all { distanceKm(it.lat, it.lng, here.lat, here.lng) >= DISTINCT_STOP_KM }) stops += here
                val elapsed = here.at - anchor.at
                if (stops.size >= 3 && elapsed >= SHORT_VISIT_MS && distance / (elapsed / 3_600_000.0) <= LOCAL_SPEED_KMH) return stops.toList()
            }
            previous = here
        }
        return emptyList()
    }

    /** Separate visits can establish repeated presence, without joining their timelines. */
    fun repeatedPresence(sightings: List<Sighting>): Boolean {
        val points = sightings.filterNot { it.isTransport }
        return points.map { it.day }.distinct().size >= PRESENCE_DAYS && points.any { point ->
            val first = points.first()
            point.day != first.day && distanceKm(first.lat, first.lng, point.lat, point.lng) >= DISTINCT_STOP_KM
        }
    }
}

/** The kilometres between two points on the earth. */
fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val rad = Math.PI / 180
    val a = sin((lat2 - lat1) * rad / 2).let { it * it } +
        cos(lat1 * rad) * cos(lat2 * rad) * sin((lng2 - lng1) * rad / 2).let { it * it }
    return 12742 * asin(sqrt(a))
}
