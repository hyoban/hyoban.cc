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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Trips the phone finds by itself, for the map's 可能的旅程: the days spent far from every
// place lived at, until a record there again, that no trip holds yet. A place lived at is an
// area of about 20 km that the records come back to on many days over many months of the year
// around, so a second home (where one works, where one's family lives) is one as well. Worked
// out on the phone from its own records, nothing sent anywhere.

/** Somewhere a record says one was: when, on which day where it happened, and where. */
data class Sighting(val at: Long, val day: String, val lat: Double, val lng: Double, val place: Place? = null)

/** A trip the records suggest, from [start] to [end], `YYYY-MM-DD`, named after the city it went farthest to. */
data class TripSuggestion(val start: String, val end: String, val name: String, val records: Int) {
    /** What dismissing it keeps, so it doesn't come back. */
    val key: String get() = "$start..$end"
}

object TripDiscovery {
    /** How far from every place lived at a record is for it to be away, in kilometres. */
    const val AWAY_KM = 100.0

    /** The longest a trip goes without a record before the next one far away starts another. */
    const val MAX_GAP_DAYS = 14L

    /** The days and months an area needs in the year around a day to be lived at then. */
    private const val HOME_DAYS = 8
    private const val HOME_MONTHS = 3

    /** How far either side of a day the records count for where one lived then. */
    private const val AROUND_DAYS = 365L

    /** An area's size, in degrees: about 20 km. */
    private const val CELL = 0.2

    /**
     * The trips [sightings] suggest, newest first: each run of them away from every place lived
     * at, ending at the next one back at one or after [MAX_GAP_DAYS] without any, with two or more
     * records, and none of its days in one of [trips] or a trip [dismissed] by its key. Each is
     * named after its farthest place's city, else the city [cityAt] finds there, else the place.
     */
    fun discover(
        sightings: List<Sighting>,
        trips: List<TripRecord>,
        dismissed: Set<String> = emptySet(),
        cityAt: (lat: Double, lng: Double) -> String? = { _, _ -> null },
    ): List<TripSuggestion> {
        val sorted = sightings.sortedBy { it.at }
        val dayOf = sorted.associate { it.day to LocalDate.parse(it.day).toEpochDay() }
        // Each area's days with records, oldest first, leaving out those with too few ever to be lived at.
        val areas = sorted.groupBy({ cellOf(it.lat, it.lng) }, { dayOf.getValue(it.day) })
            .mapValues { (_, days) -> days.distinct().sorted().toLongArray() }
            .filterValues { it.size >= HOME_DAYS }
        val homes = HashMap<Long, List<Pair<Double, Double>>>()

        val runs = mutableListOf<List<Pair<Sighting, Double>>>()
        var run = mutableListOf<Pair<Sighting, Double>>()
        for (sighting in sorted) {
            val day = dayOf.getValue(sighting.day)
            val near = homes.getOrPut(day) { homesAt(day, areas) }.ifEmpty { null }?.minOf { (lat, lng) -> km(lat, lng, sighting.lat, sighting.lng) } ?: continue
            if (near <= AWAY_KM) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
                continue
            }
            val last = run.lastOrNull()?.first
            if (last != null && day - dayOf.getValue(last.day) > MAX_GAP_DAYS) {
                runs += run
                run = mutableListOf()
            }
            run += sighting to near
        }
        if (run.isNotEmpty()) runs += run

        return runs.filter { it.size >= 2 }.map { run ->
            val farthest = run.maxBy { it.second }.first
            val name = (farthest.place?.division?.city ?: cityAt(farthest.lat, farthest.lng))?.let(::shortCity)
                ?: farthest.place?.name ?: "%.2f, %.2f".format(farthest.lat, farthest.lng)
            TripSuggestion(run.first().first.day, run.last().first.day, name, run.size)
        }.filter { suggestion ->
            suggestion.key !in dismissed && trips.none { it.start <= suggestion.end && it.end >= suggestion.start }
        }.sortedByDescending { it.start }
    }

    /** The middles of the areas lived at around [day], an epoch day, from each area's [areas] days with records. */
    private fun homesAt(day: Long, areas: Map<Pair<Int, Int>, LongArray>): List<Pair<Double, Double>> =
        areas.mapNotNull { (cell, days) ->
            val from = insertionPoint(days, day - AROUND_DAYS)
            val to = insertionPoint(days, day + AROUND_DAYS + 1)
            val lived = to - from >= HOME_DAYS &&
                (from until to).map { LocalDate.ofEpochDay(days[it]).let { date -> date.year * 12 + date.monthValue } }.distinct().size >= HOME_MONTHS
            if (lived) cell.first * CELL to cell.second * CELL else null
        }

    /** Where [value] would go in the sorted [days]: how many of them come before it. */
    private fun insertionPoint(days: LongArray, value: Long): Int = days.binarySearch(value).let { if (it < 0) -it - 1 else it }

    /** A city's name as said in passing: 杭州 for 杭州市, 香港 for 香港特别行政区. */
    fun shortCity(name: String): String = when {
        name.endsWith("特别行政区") -> name.removeSuffix("特别行政区")
        name.endsWith("市") && name.length > 2 -> name.removeSuffix("市")
        else -> name
    }

    private fun cellOf(lat: Double, lng: Double) = (lat / CELL).roundToInt() to (lng / CELL).roundToInt()

    /** The kilometres between two points on the earth. */
    private fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val rad = Math.PI / 180
        val a = sin((lat2 - lat1) * rad / 2).let { it * it } +
            cos(lat1 * rad) * cos(lat2 * rad) * sin((lng2 - lng1) * rad / 2).let { it * it }
        return 12742 * asin(sqrt(a))
    }

    /**
     * Where the records say one was: each Moment at its places, or its own point without one, and
     * each entry beyond the Moments (a check-in, a charge) at its point, or its place without one.
     */
    fun sightingsOf(moments: List<MomentRecord>, entries: Collection<JsonObject>, places: Map<String, Place>): List<Sighting> {
        val fromMoments = moments.flatMap { moment ->
            val at = runCatching { epochOf(moment.occurredAt) }.getOrNull() ?: return@flatMap emptyList()
            val atPlaces = moment.places.mapNotNull { id -> places[id]?.let { Sighting(at, moment.dateKey, it.latitude, it.longitude, it) } }
            atPlaces.ifEmpty { listOfNotNull(pointOf(moment.extra)?.let { (lat, lng) -> Sighting(at, moment.dateKey, lat, lng) }) }
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
