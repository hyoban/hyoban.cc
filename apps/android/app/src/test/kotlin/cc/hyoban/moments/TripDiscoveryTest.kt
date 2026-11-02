package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Division
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.Sighting
import cc.hyoban.moments.data.trips.TripDiscovery
import cc.hyoban.moments.data.trips.TripSuggestion
import cc.hyoban.moments.data.trips.epochOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Trips found in the records: days far from every place lived at, until back at one. */
class TripDiscoveryTest {
    // Made-up places: a home, a second home visited now and then, and two places far from both.
    private val home = Place("家", 31.00, 121.00)
    private val family = Place("老家", 26.00, 118.00)
    private val coast = Place("海边", 35.00, 117.00, division = Division("000000", "某省", "海滨市"))
    private val hills = Place("山里", 28.00, 112.00)

    /** At home every other day of 2025, but for the days in [away]. */
    private fun year(away: Set<String>): List<Sighting> =
        generateSequence(LocalDate.parse("2025-01-01")) { it.plusDays(2) }.takeWhile { it.year == 2025 }
            .map { it.toString() }.filter { it !in away }.map { at(it, home) }.toList()

    private fun at(day: String, place: Place, hour: Int = 12) =
        Sighting(epochOf("${day}T%02d:00:00+08:00".format(hour)), day, place.latitude, place.longitude, place)

    private fun days(from: String, to: String) =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse(to) }.map { it.toString() }.toList()

    @Test
    fun `days far from home until back there are a trip, named after its farthest city`() {
        val trip = days("2025-06-10", "2025-06-13")
        val sightings = year(trip.toSet()) + trip.map { at(it, coast) }

        assertEquals(listOf(TripSuggestion("2025-06-10", "2025-06-13", "海滨", 4)), TripDiscovery.discover(sightings, emptyList()))
    }

    @Test
    fun `a place with no city is named after the city found there, else after itself`() {
        val trip = days("2025-06-10", "2025-06-11")
        val sightings = year(trip.toSet()) + trip.map { at(it, hills) }

        assertEquals("山城", TripDiscovery.discover(sightings, emptyList()) { lat, _ -> "山城市".takeIf { lat < 29 } }.single().name)
        assertEquals("山里", TripDiscovery.discover(sightings, emptyList()).single().name)
        assertEquals("香港", TripDiscovery.shortCity("香港特别行政区"))
        assertEquals("沙市", TripDiscovery.shortCity("沙市"))
    }

    @Test
    fun `a name says where it went, in order, and the holiday, day or weekend it took`() {
        val name = { start: String, end: String, cities: List<String> -> TripDiscovery.nameOf(LocalDate.parse(start), LocalDate.parse(end), cities, "某地") }

        assertEquals("国庆香港", name("2026-09-30", "2026-10-04", listOf("香港", "香港")))
        assertEquals("春节南平", name("2026-02-15", "2026-02-22", listOf("南平")))
        assertEquals("五一福州、厦门", name("2026-05-01", "2026-05-04", listOf("福州", "福州", "厦门", "福州", "厦门")))
        assertEquals("南京一日", name("2025-11-19", "2025-11-19", listOf("南京")))
        assertEquals("合肥周末", name("2026-07-18", "2026-07-19", listOf("合肥")))
        assertEquals("苏州", name("2026-07-14", "2026-07-16", listOf("苏州")))
        // A city a single record passed through, among several, is left out; when each had one, the farthest names it.
        assertEquals("某地一日", name("2025-11-19", "2025-11-19", listOf("上饶", "金华")))
        assertEquals("成都、雅安、康定", name("2025-10-20", "2025-10-28", listOf("成都", "成都", "绵阳", "雅安", "雅安", "康定", "康定")))
        assertEquals("广州、深圳", name("2025-08-11", "2025-08-14", listOf("广州", "广州", "佛山", "佛山", "深圳", "深圳", "深圳", "广州", "珠海市区", "珠海市区")))
        assertEquals("乌鲁木齐、伊宁等", name("2025-08-11", "2025-08-20", listOf("乌鲁木齐", "乌鲁木齐", "伊宁", "伊宁", "喀什", "喀什", "和田", "和田")))
        // Stops on the way with a few records each give way to where most of them were.
        assertEquals("五一福州、厦门", name("2026-05-01", "2026-05-04", listOf("宁波", "宁波", "台州", "台州", "温州", "温州", "福州", "福州", "福州", "福州", "厦门", "厦门", "厦门")))
        assertEquals("某地一日", name("2025-11-19", "2025-11-19", emptyList()))
    }

    @Test
    fun `a second home visited over many months is not a trip`() {
        val visits = listOf("2025-02-01", "2025-02-02", "2025-02-03", "2025-05-01", "2025-05-02", "2025-05-03", "2025-08-01", "2025-08-02", "2025-08-03", "2025-11-01", "2025-11-02")
        val sightings = year(visits.toSet()) + visits.map { at(it, family) }

        assertEquals(emptyList(), TripDiscovery.discover(sightings, emptyList()))
    }

    @Test
    fun `a quiet stretch away stays one trip, and a long one parts two`() {
        // Away from 03-01 to 03-20 with a record now and then; then nothing for weeks, still away.
        val away = days("2025-03-01", "2025-04-30").toSet()
        val sightings = year(away) + listOf("2025-03-01", "2025-03-09", "2025-03-20", "2025-04-20", "2025-04-21").map { at(it, hills) }

        assertEquals(
            listOf("2025-04-20..2025-04-21", "2025-03-01..2025-03-20"),
            TripDiscovery.discover(sightings, emptyList()).map { it.key },
        )
    }

    @Test
    fun `one record away, a trip already made or one dismissed is not suggested`() {
        val made = days("2025-06-10", "2025-06-12")
        val dismissed = days("2025-09-05", "2025-09-06")
        val sightings = year((made + dismissed + "2025-07-01").toSet()) +
            (made + dismissed).map { at(it, coast) } + at("2025-07-01", hills)
        val trips = listOf(TripRecord("t", "t", "2025-06-11", "2025-06-11", createdAt = "2025-06-11", updatedAt = "2025-06-11"))

        assertEquals(emptyList(), TripDiscovery.discover(sightings, trips, dismissed = setOf("2025-09-05..2025-09-06")))
    }

    @Test
    fun `before any place is lived at, nothing is away`() {
        assertEquals(emptyList(), TripDiscovery.discover(days("2025-01-01", "2025-01-03").map { at(it, coast) }, emptyList()))
    }

    @Test
    fun `Moments are where their places are, entries where their points are`() {
        val places = mapOf("coast" to coast)
        val moment = MomentRecord("M", "2025-06-10T09:00:00+08:00", listOf("coast"), "", createdAt = "2025-06-10", updatedAt = "2025-06-10")
        val checkin = buildJsonObject {
            put("uid", "C")
            put("at", "2025-06-11T20:00:00+08:00")
            putJsonObject("point") { put("lat", 28.0); put("lng", 112.0) }
            put("place", "coast")
        }
        val sightings = TripDiscovery.sightingsOf(listOf(moment), listOf(checkin, buildJsonObject { put("uid", "N") }), places)

        assertEquals(listOf("2025-06-10" to 35.0, "2025-06-11" to 28.0), sightings.map { it.day to it.lat })
        assertEquals("海边", sightings.last().place?.name)
    }
}
