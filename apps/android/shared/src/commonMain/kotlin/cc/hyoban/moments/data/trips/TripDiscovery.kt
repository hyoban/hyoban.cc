package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

// Trips the phone finds by itself, for the map's 可能的旅程: the days spent far from home,
// until a record there again, that no trip holds yet. Home is the area of about 20 km with
// records on the most days in the months around: only that one, so a city gone to often (a
// day out to Shanghai) or the family's home is away each time, and a move is followed. Worked
// out on the phone from its own records, nothing sent anywhere.

/** Somewhere a record says one was: when, on which day where it happened, where, and how many [photos] it kept there. */
data class Sighting(val at: Long, val day: String, val lat: Double, val lng: Double, val place: Place? = null, val photos: Int = 0)

/**
 * A trip the records suggest, from [start] to [end], `YYYY-MM-DD`, a [name] for it made from where
 * and when it went, how many records and photos it has, and on how many of its days [recorded].
 */
data class TripSuggestion(
    val start: String,
    val end: String,
    val name: String,
    val records: Int,
    val photos: Int = 0,
    val recorded: Int = records,
) {
    /** What dismissing it keeps, so it doesn't come back. */
    val key: String get() = "$start..$end"

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
    /** Beyond this distance a short visit or an observed overnight can suffice, in kilometres. */
    const val AWAY_KM = 100.0

    /** Nearby outings need a stay, while distant journeys can have sparse records. */
    private const val OUTING_KM = 50.0
    private const val HOME_KM = 30.0

    /** The longest a trip goes without a record before the next one far away starts another. */
    const val MAX_GAP_DAYS = 14L

    /** How far either side of a day the records count for where home was then: short enough to follow a move. */
    private const val AROUND_DAYS = 120L

    /** An area's size, in degrees: about 20 km. */
    private const val CELL = 0.2

    /**
     * The trips [sightings] suggest, the best first by [TripSuggestion.score], then the newest:
     * each run beyond [OUTING_KM], ending within [HOME_KM] or after [MAX_GAP_DAYS]
     * without any, with two or more records and local activity, a stay for nearby outings,
     * and none of its days in one of [trips] or a trip
     * [dismissed] by its key. A record [atHome] says is in a home set by hand is home as well.
     * Each is named by [nameOf], its records' cities those of their places, else those [cityAt]
     * finds there.
     */
    fun discover(
        sightings: List<Sighting>,
        trips: List<TripRecord>,
        dismissed: Set<String> = emptySet(),
        atHome: (lat: Double, lng: Double) -> Boolean = { _, _ -> false },
        cityAt: (lat: Double, lng: Double) -> String? = { _, _ -> null },
    ): List<TripSuggestion> {
        return runsOf(sightings.sortedBy { it.at }, atHome)
            .mapNotNull { suggestionOf(it, cityAt) }
            .filter { suggestion ->
                suggestion.key !in dismissed && trips.none { it.start <= suggestion.end && it.end >= suggestion.start }
            }.sortedWith(compareByDescending<TripSuggestion> { it.score }.thenByDescending { it.start })
    }

    private data class AwaySighting(
        val sighting: Sighting,
        val distanceFromHome: Double,
        val home: Pair<Double, Double>,
    )

    private data class NamedSighting(val sighting: Sighting, val city: String?)

    /** Keep the complete away sequence, including transport observations between visits. */
    private fun runsOf(sorted: List<Sighting>, atHome: (Double, Double) -> Boolean): List<List<AwaySighting>> {
        val dayOf = sorted.associate { it.day to LocalDate.parse(it.day).toEpochDay() }
        val areas = areasOf(sorted, dayOf)
        val homes = HashMap<Long, Pair<Double, Double>>()
        val runs = mutableListOf<List<AwaySighting>>()
        var run = mutableListOf<AwaySighting>()
        for (sighting in sorted) {
            val day = dayOf.getValue(sighting.day)
            val home = homes.getOrPut(day) { homeAt(day, areas) }
            val distance = distanceKm(home.first, home.second, sighting.lat, sighting.lng)
            if (distance <= HOME_KM || atHome(sighting.lat, sighting.lng)) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
                continue
            }
            val last = run.lastOrNull()?.sighting
            if (last != null && day - dayOf.getValue(last.day) > MAX_GAP_DAYS) {
                runs += run
                run = mutableListOf()
            }
            if (run.isEmpty() && distance < OUTING_KM) continue
            run += AwaySighting(sighting, distance, home)
        }
        if (run.isNotEmpty()) runs += run
        return runs
    }

    private fun suggestionOf(run: List<AwaySighting>, cityAt: (Double, Double) -> String?): TripSuggestion? {
        if (run.size < 2) return null
        val points = run.map { it.sighting }
        val evidence = if (run.any { it.distanceFromHome > AWAY_KM }) TripEvidence.activity(points) else TripEvidence.stay(points)
        if (evidence.isEmpty()) return null

        val supported = evidence.toSet()
        val representative = run.filter { it.sighting in supported }.maxBy { it.distanceFromHome }.sighting
        val named = points.map { sighting ->
            NamedSighting(sighting, (sighting.place?.division?.city ?: cityAt(sighting.lat, sighting.lng))?.let(::shortCity))
        }
        val visited = destinationCities(named)
        val homeCities = run.mapNotNull { cityAt(it.home.first, it.home.second)?.let(::shortCity) }.toSet()
        val destinations = if (visited.any { it !in homeCities }) visited.filterNot { it in homeCities } else visited
        val start = points.first().day
        val end = points.last().day
        val where = named.first { it.sighting === representative }.city ?: representative.place?.name ?:
            "%.2f, %.2f".format(representative.lat, representative.lng)
        return TripSuggestion(
            start = start,
            end = end,
            name = nameOf(LocalDate.parse(start), LocalDate.parse(end), destinations, where),
            records = points.size,
            photos = points.sumOf { it.photos },
            recorded = points.map { it.day }.distinct().size,
        )
    }

    /** Keep destination order and counts after qualifying each visit and repeated presence. */
    private fun destinationCities(named: List<NamedSighting>): List<String> {
        val visited = mutableSetOf<String>()
        var start = 0
        for (index in 1..named.size) {
            if (index == named.size || named[index].city != named[start].city) {
                val city = named[start].city
                if (city != null && TripEvidence.activity(named.subList(start, index).map { it.sighting }).isNotEmpty()) visited += city
                start = index
            }
        }
        named.groupBy { it.city }.forEach { (city, records) ->
            if (city != null && TripEvidence.repeatedPresence(records.map { it.sighting })) visited += city
        }
        return named.mapNotNull { it.city }.filter { it in visited }
    }

    /** Formats destinations already qualified by visit evidence. */
    fun nameOf(start: LocalDate, end: LocalDate, cities: List<String>, where: String): String =
        TripNames.nameOf(start, end, cities, where)

    /** Where home is around [day], `YYYY-MM-DD`, by [sightings]: the middle of its area, or none without any. */
    fun homeOf(sightings: List<Sighting>, day: String): Pair<Double, Double>? =
        if (sightings.isEmpty()) null else homeAt(LocalDate.parse(day).toEpochDay(), areasOf(sightings, sightings.associate { it.day to LocalDate.parse(it.day).toEpochDay() }))

    /** Whether a city's region, by its id, is one of [homes] set by hand: the city itself, or its province by the id's first two digits. */
    fun isHome(regionId: String?, homes: Set<String>): Boolean = regionId != null && (regionId in homes || regionId.take(2) in homes)

    /** Each area's days with records, as epoch days by [dayOf], oldest first. */
    private fun areasOf(sightings: List<Sighting>, dayOf: Map<String, Long>): Map<Pair<Int, Int>, LongArray> =
        sightings.groupBy({ cellOf(it.lat, it.lng) }, { dayOf.getValue(it.day) }).mapValues { (_, days) -> days.distinct().sorted().toLongArray() }

    /**
     * The middle of home around [day], an epoch day: the area of [areas] with records on the most
     * days within [AROUND_DAYS] of it, the first of them on a tie. There is always one, the day's own.
     */
    private fun homeAt(day: Long, areas: Map<Pair<Int, Int>, LongArray>): Pair<Double, Double> {
        val (cell) = areas.maxBy { (_, days) -> insertionPoint(days, day + AROUND_DAYS + 1) - insertionPoint(days, day - AROUND_DAYS) }
        return cell.first * CELL to cell.second * CELL
    }

    /** Where [value] would go in the sorted [days]: how many of them come before it. */
    private fun insertionPoint(days: LongArray, value: Long): Int = days.binarySearch(value).let { if (it < 0) -it - 1 else it }

    /** A city's short name, shared with trip titles. */
    fun shortCity(name: String): String = TripNames.shortCity(name)

    private fun cellOf(lat: Double, lng: Double) = (lat / CELL).roundToInt() to (lng / CELL).roundToInt()

    /**
     * Where the records say one was: each Moment at its places, or its own point without one, its
     * photos and videos counted at the first, and each entry beyond the Moments (a check-in, a
     * charge) at its point, or its place without one.
     */
    fun sightingsOf(moments: List<MomentRecord>, entries: Collection<JsonObject>, places: Map<String, Place>): List<Sighting> {
        val fromMoments = moments.flatMap { moment ->
            val at = runCatching { epochOf(moment.occurredAt) }.getOrNull() ?: return@flatMap emptyList()
            val atPlaces = moment.places.mapNotNull { id -> places[id]?.let { Sighting(at, moment.dateKey, it.latitude, it.longitude, it) } }
            atPlaces.ifEmpty { listOfNotNull(pointOf(moment.extra)?.let { (lat, lng) -> Sighting(at, moment.dateKey, lat, lng) }) }
                .mapIndexed { index, sighting -> if (index == 0) sighting.copy(photos = moment.media.size) else sighting }
        }
        val fromEntries = entries.mapNotNull { entry ->
            val time = (entry["at"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val at = runCatching { epochOf(time) }.getOrNull() ?: return@mapNotNull null
            val place = (entry["place"] as? JsonPrimitive)?.content?.let(places::get)
            val (lat, lng) = pointOf(entry) ?: place?.let { it.latitude to it.longitude } ?: return@mapNotNull null
            Sighting(at, time.take(10), lat, lng, place)
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
