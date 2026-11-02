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

/** A trip the records suggest, from [start] to [end], `YYYY-MM-DD`, and a [name] for it made from where and when it went. */
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
     * named by [nameOf], its records' cities those of their places, else those [cityAt] finds there.
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
            val cities = run.map { (sighting) -> (sighting.place?.division?.city ?: cityAt(sighting.lat, sighting.lng))?.let(::shortCity) }
            val start = run.first().first.day
            val end = run.last().first.day
            val where = cities[run.indexOfFirst { it.first === farthest }] ?: farthest.place?.name ?: "%.2f, %.2f".format(farthest.lat, farthest.lng)
            TripSuggestion(start, end, nameOf(LocalDate.parse(start), LocalDate.parse(end), cities.filterNotNull(), where), run.size)
        }.filter { suggestion ->
            suggestion.key !in dismissed && trips.none { it.start <= suggestion.end && it.end >= suggestion.start }
        }.sortedByDescending { it.start }
    }

    /**
     * A trip's name, as one would write it: where it went, the [cities] with the most of its records
     * in the order they were reached, those a single record passed through (a station on the way)
     * left out among several, or [where], its farthest, when each had one or there are none; and
     * when, the holiday it spans before it (国庆香港), else 一日 or 周末 after it (南京一日, 合肥周末).
     */
    fun nameOf(start: LocalDate, end: LocalDate, cities: List<String>, where: String): String {
        val counts = cities.groupingBy { it }.eachCount()
        val reached = cities.distinct().let { all -> if (all.size > 1) all.filter { counts.getValue(it) > 1 } else all }
        val most = reached.sortedByDescending { counts.getValue(it) }
        val chosen = if (most.size <= 3 && most.sumOf { it.length } <= 9) most else most.take(2)
        val place = when {
            reached.isEmpty() -> where
            // 等 only for a city left out with as many records as one named.
            else -> reached.filter { it in chosen }.joinToString("、") +
                if (most.drop(chosen.size).any { counts.getValue(it) >= counts.getValue(chosen.last()) }) "等" else ""
        }
        holidayOf(start, end)?.let { return it + place }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toList()
        return when {
            days.size == 1 -> place + "一日"
            days.size <= 3 && days.all { it.dayOfWeek.value >= 5 } && days.any { it.dayOfWeek.value >= 6 } -> place + "周末"
            else -> place
        }
    }

    /** The day of the Spring Festival, by year, for the years the records reach. */
    private val SPRING_FESTIVAL = mapOf(
        2016 to "02-08", 2017 to "01-28", 2018 to "02-16", 2019 to "02-05", 2020 to "01-25", 2021 to "02-12",
        2022 to "02-01", 2023 to "01-22", 2024 to "02-10", 2025 to "01-29", 2026 to "02-17", 2027 to "02-06",
        2028 to "01-26", 2029 to "02-13", 2030 to "02-03",
    )

    /** The holiday a trip from [start] to [end] spans any of, with a day either side for the travel there and back. */
    private fun holidayOf(start: LocalDate, end: LocalDate): String? {
        val spans = buildList {
            for (year in start.year..end.year) {
                SPRING_FESTIVAL[year]?.let { LocalDate.parse("$year-$it") }?.let { add("春节" to (it.minusDays(8)..it.plusDays(8))) }
                add("元旦" to (LocalDate.of(year - 1, 12, 30)..LocalDate.of(year, 1, 2)))
                add("清明" to (LocalDate.of(year, 4, 3)..LocalDate.of(year, 4, 7)))
                add("五一" to (LocalDate.of(year, 4, 30)..LocalDate.of(year, 5, 6)))
                add("国庆" to (LocalDate.of(year, 9, 30)..LocalDate.of(year, 10, 8)))
            }
        }
        return spans.firstOrNull { (_, days) -> start <= days.endInclusive && end >= days.start }?.first
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
