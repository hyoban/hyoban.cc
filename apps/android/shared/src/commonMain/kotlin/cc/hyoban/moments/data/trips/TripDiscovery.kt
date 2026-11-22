package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Trips the phone finds by itself, for the map's 可能的旅程: what lies between a check-in at home
// on leaving and the next one there on coming back, when it went far enough to be a trip, that no
// trip holds yet. Home is the saved places chosen in 我 → 家, any number of them. A check-in at
// home with none after it yet is a trip under way. Worked out on the phone from its own records,
// nothing sent anywhere.

/**
 * Somewhere a record says one was: when, on which day where it happened, where, and how many
 * [photos] it kept there; [placeId] the place it was at, and for a check-in its entry's [checkin]
 * uid and its [time] as written.
 */
data class Sighting(
    val at: Long,
    val day: String,
    val lat: Double,
    val lng: Double,
    val place: Place? = null,
    val photos: Int = 0,
    val placeId: String? = null,
    val checkin: String? = null,
    val time: String? = null,
)

/**
 * A trip the records suggest, from [start] to [end], `YYYY-MM-DD`, a [name] for it made from where
 * and when it went, how many records and photos it has, and on how many of its days [recorded].
 * It runs from the check-in [from] at [startAt] to the check-in [to] at [endAt]; [ongoing] when
 * there is none back home yet, its end then today.
 */
data class TripSuggestion(
    val start: String,
    val end: String,
    val name: String,
    val records: Int,
    val photos: Int = 0,
    val recorded: Int = records,
    val from: String? = null,
    val to: String? = null,
    val startAt: String = start,
    val endAt: String = end,
    val ongoing: Boolean = false,
) {
    /** What dismissing it keeps, so it doesn't come back: the check-in it left from, which stays as it goes on. */
    val key: String get() = from ?: "$start..$end"

    val days: Int get() = (LocalDate.parse(end).toEpochDay() - LocalDate.parse(start).toEpochDay()).toInt() + 1

    /**
     * How much it is worth making a trip of: each photo kept, and each day with a record three
     * times one, up to a week of them. Days without any don't count, and a stay longer than a week
     * gains nothing more for its length, so weeks of a few records each stay below a short trip
     * with many photos.
     */
    val score: Int get() = photos + DAY_SCORE * minOf(recorded, SCORED_DAYS)
}

/** What a day with records of a suggested trip is worth beside a photo, in its [TripSuggestion.score]. */
private const val DAY_SCORE = 3

/** How many of a suggested trip's days with records count towards its [TripSuggestion.score]. */
private const val SCORED_DAYS = 7

object TripDiscovery {
    /** A record this far from every home makes a trip of the time away, in kilometres. */
    const val AWAY_KM = 50.0

    /** A night away this far from every home makes a trip of it too. */
    const val NIGHT_KM = 30.0

    /**
     * The trips [sightings] suggest between check-ins at the [homes], by place id: one under way
     * first, then the best by [TripSuggestion.score], then the newest. Each holds the records
     * between the two check-ins away from home, and has one beyond [AWAY_KM] or one beyond
     * [NIGHT_KM] on another day than it left; none of its days in one of [trips], nor
     * [dismissed] by its key. Each is named by its records' cities, those of their places, else
     * those [cityAt] finds there; one under way ends [today].
     */
    fun discover(
        sightings: List<Sighting>,
        trips: List<TripRecord>,
        homes: Map<String, Place>,
        dismissed: Set<String> = emptySet(),
        cityAt: (lat: Double, lng: Double) -> String? = { _, _ -> null },
        today: String = LocalDate.now().toString(),
    ): List<TripSuggestion> {
        if (homes.isEmpty()) return emptyList()
        val sorted = sightings.sortedBy { it.at }
        val atHome = sorted.indices.filter { sorted[it].checkin != null && sorted[it].placeId in homes }
        val away = { from: Int, to: Int -> sorted.subList(from + 1, to).filter { it.placeId !in homes } }
        val found = atHome.zipWithNext().mapNotNull { (from, to) -> suggestionOf(sorted[from], sorted[to], away(from, to), homes, cityAt, today) } +
            listOfNotNull(atHome.lastOrNull()?.let { from -> suggestionOf(sorted[from], null, away(from, sorted.size), homes, cityAt, today) })
        return found
            .filter { suggestion ->
                suggestion.key !in dismissed && trips.none { it.start <= suggestion.end && it.end >= suggestion.start }
            }.sortedWith(
                compareByDescending<TripSuggestion> { it.ongoing }.thenByDescending { it.score }.thenByDescending { it.start },
            )
    }

    private fun suggestionOf(
        leaving: Sighting,
        back: Sighting?,
        away: List<Sighting>,
        homes: Map<String, Place>,
        cityAt: (Double, Double) -> String?,
        today: String,
    ): TripSuggestion? {
        if (away.isEmpty()) return null
        val fromHome = away.map { sighting -> homes.values.minOf { distanceKm(it.latitude, it.longitude, sighting.lat, sighting.lng) } }
        val end = back?.day ?: today
        val far = fromHome.any { it >= AWAY_KM }
        val night = end != leaving.day && fromHome.any { it >= NIGHT_KM }
        if (!far && !night) return null

        val cityOf = { sighting: Sighting -> (sighting.place?.division?.city ?: cityAt(sighting.lat, sighting.lng))?.let(::shortCity) }
        val homeCities = homes.values.mapNotNull { (it.division?.city ?: cityAt(it.latitude, it.longitude))?.let(::shortCity) }.toSet()
        val visited = away.mapNotNull(cityOf)
        val destinations = if (visited.any { it !in homeCities }) visited.filterNot { it in homeCities } else visited
        val farthest = away[fromHome.indices.maxBy { fromHome[it] }]
        val where = cityOf(farthest) ?: farthest.place?.name ?: "%.2f, %.2f".format(farthest.lat, farthest.lng)
        return TripSuggestion(
            start = leaving.day,
            end = end,
            name = nameOf(LocalDate.parse(leaving.day), LocalDate.parse(end), destinations, where),
            records = away.size,
            photos = away.sumOf { it.photos },
            recorded = away.map { it.day }.distinct().size,
            from = leaving.checkin,
            to = back?.checkin,
            startAt = leaving.time ?: leaving.day,
            endAt = back?.time ?: back?.day ?: end,
            ongoing = back == null,
        )
    }

    /** Formats destinations already qualified by visit evidence. */
    fun nameOf(start: LocalDate, end: LocalDate, cities: List<String>, where: String): String =
        TripNames.nameOf(start, end, cities, where)

    /** A city's short name, shared with trip titles. */
    fun shortCity(name: String): String = TripNames.shortCity(name)

    /**
     * Where the records say one was: each Moment at its places, or its own point without one, its
     * photos and videos counted at the first, and each entry beyond the Moments (a check-in, a
     * charge) at its point, or its place without one, a check-in knowing its uid and time.
     */
    fun sightingsOf(moments: List<MomentRecord>, entries: Collection<JsonObject>, places: Map<String, Place>): List<Sighting> {
        val fromMoments = moments.flatMap { moment ->
            val at = runCatching { epochOf(moment.occurredAt) }.getOrNull() ?: return@flatMap emptyList()
            val atPlaces = moment.places.mapNotNull { id -> places[id]?.let { Sighting(at, moment.dateKey, it.latitude, it.longitude, it, placeId = id) } }
            atPlaces.ifEmpty { listOfNotNull(pointOf(moment.extra)?.let { (lat, lng) -> Sighting(at, moment.dateKey, lat, lng) }) }
                .mapIndexed { index, sighting -> if (index == 0) sighting.copy(photos = moment.media.size) else sighting }
        }
        val fromEntries = entries.mapNotNull { entry ->
            val time = (entry["at"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val at = runCatching { epochOf(time) }.getOrNull() ?: return@mapNotNull null
            val placeId = (entry["place"] as? JsonPrimitive)?.content
            val place = placeId?.let(places::get)
            val (lat, lng) = pointOf(entry) ?: place?.let { it.latitude to it.longitude } ?: return@mapNotNull null
            val isCheckin = (entry["parts"] as? JsonObject)?.containsKey("checkin") == true
            Sighting(
                at, time.take(10), lat, lng, place,
                placeId = placeId,
                checkin = if (isCheckin) (entry["uid"] as? JsonPrimitive)?.content else null,
                time = time,
            )
        }
        return fromMoments + fromEntries
    }

    private fun pointOf(record: JsonObject): Pair<Double, Double>? {
        val point = record["point"] as? JsonObject ?: return null
        val lat = (point["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (point["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return lat to lng
    }
}

/** The kilometres between two points on the earth. */
fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
    cc.hyoban.moments.data.local.greatCircleMetres(lat1, lng1, lat2, lng2) / 1000
