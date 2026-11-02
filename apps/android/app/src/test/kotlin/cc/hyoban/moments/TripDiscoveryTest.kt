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
