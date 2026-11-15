package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Division
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.Sighting
import cc.hyoban.moments.data.trips.TripDiscovery
import cc.hyoban.moments.data.trips.epochOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trips found between check-ins at home: leaving one, coming back to one. */
class TripDiscoveryTest {
    // Made-up places: two homes, somewhere in the same city, one nearby and one far from both.
    private val home = Place("家", 31.00, 121.00, division = Division("000001", "某省", "家城市"))
    private val family = Place("老家", 26.00, 118.00)
    private val office = Place("公司", 31.05, 121.05)
    private val town = Place("小镇", 31.30, 121.00)
    private val coast = Place("海边", 35.00, 117.00, division = Division("000000", "某省", "海滨市"))
    private val homes = mapOf("home" to home, "family" to family)

    private fun time(day: String, hour: Int) = "${day}T%02d:00:00+08:00".format(hour)

    private fun at(day: String, place: Place, hour: Int = 12, photos: Int = 0) =
        Sighting(epochOf(time(day, hour)), day, place.latitude, place.longitude, place, photos = photos)

    /** A check-in [uid] at a home, by its id. */
    private fun checkin(uid: String, day: String, id: String, hour: Int = 9): Sighting {
        val place = homes.getValue(id)
        return Sighting(epochOf(time(day, hour)), day, place.latitude, place.longitude, place, placeId = id, checkin = uid, time = time(day, hour))
    }

    private fun discover(sightings: List<Sighting>, trips: List<TripRecord> = emptyList(), dismissed: Set<String> = emptySet()) =
        TripDiscovery.discover(sightings, trips, homes, dismissed, today = "2025-12-31")

    @Test
    fun `the time between leaving home and coming back is a trip, at the check-ins' times`() {
        val sightings = listOf(
            checkin("A", "2025-06-10", "home", 8),
            at("2025-06-10", coast, 15, photos = 3),
            at("2025-06-11", coast),
            at("2025-06-12", coast),
            checkin("B", "2025-06-12", "home", 22),
        )

        val trip = discover(sightings).single()
        assertEquals("2025-06-10" to "2025-06-12", trip.start to trip.end)
        assertEquals("A" to "B", trip.from to trip.to)
        assertEquals(time("2025-06-10", 8) to time("2025-06-12", 22), trip.startAt to trip.endAt)
        assertEquals("海滨", trip.name)
        assertEquals(3, trip.records)
        assertEquals(3, trip.photos)
        assertEquals(false, trip.ongoing)
    }

    @Test
    fun `without a home, or a check-in at one, there is none`() {
        val away = listOf(at("2025-06-10", coast), at("2025-06-11", coast))

        assertEquals(emptyList(), TripDiscovery.discover(away + checkin("A", "2025-06-09", "home"), emptyList(), emptyMap()))
        assertEquals(emptyList(), discover(away))
        // A Moment at home is no check-in.
        assertEquals(emptyList(), discover(listOf(at("2025-06-09", home)) + away + at("2025-06-12", home)))
    }

    @Test
    fun `a day at work or nearby is no trip, a night away or a day far away is`() {
        val commute = listOf(checkin("A", "2025-03-03", "home", 8), at("2025-03-03", office), checkin("B", "2025-03-04", "home", 20))
        assertEquals(emptyList(), discover(commute))

        val nearbyDay = listOf(checkin("A", "2025-03-08", "home", 8), at("2025-03-08", town), checkin("B", "2025-03-08", "home", 20))
        assertEquals(emptyList(), discover(nearbyDay))

        val nearbyNight = listOf(checkin("A", "2025-03-08", "home", 8), at("2025-03-08", town), checkin("B", "2025-03-09", "home", 18))
        assertEquals(listOf("2025-03-08..2025-03-09"), discover(nearbyNight).map { "${it.start}..${it.end}" })

        val farDay = listOf(checkin("A", "2025-03-15", "home", 7), at("2025-03-15", coast), checkin("B", "2025-03-15", "home", 23))
        assertEquals(1, discover(farDay).single().days)
    }

    @Test
    fun `checking in at home again before leaving starts the trip from the last one`() {
        val sightings = listOf(
            checkin("A", "2025-06-10", "home", 7),
            checkin("B", "2025-06-10", "home", 8),
            at("2025-06-11", coast),
            checkin("C", "2025-06-12", "home", 20),
        )

        assertEquals("B" to "C", discover(sightings).single().let { it.from to it.to })
    }

    @Test
    fun `any home ends it, the family's too`() {
        val sightings = listOf(checkin("A", "2025-06-10", "home"), at("2025-06-11", coast), checkin("B", "2025-06-12", "family", 20))

        assertEquals("A" to "B", discover(sightings).single().let { it.from to it.to })
    }

    @Test
    fun `a trip not back yet is under way, first, until today, kept by where it left from`() {
        val sightings = listOf(
            checkin("A", "2025-06-10", "home"),
            at("2025-06-11", coast, photos = 20),
            checkin("B", "2025-06-12", "home", 20),
            checkin("C", "2025-12-29", "home"),
            at("2025-12-30", coast),
        )

        val found = discover(sightings)
        assertEquals(listOf(true, false), found.map { it.ongoing })
        val ongoing = found.first()
        assertEquals("2025-12-29" to "2025-12-31", ongoing.start to ongoing.end)
        assertEquals("C" to null, ongoing.from to ongoing.to)
        assertEquals("C", ongoing.key)
        // Dismissed under way, it doesn't come back once home.
        assertEquals(listOf("A"), discover(sightings, dismissed = setOf("C")).map { it.key })
    }

    @Test
    fun `days a trip holds already are left out`() {
        val sightings = listOf(checkin("A", "2025-09-05", "home"), at("2025-09-05", coast), checkin("B", "2025-09-06", "home", 20))
        val trip = TripRecord("t", "某地", "2025-09-06", "2025-09-08", createdAt = "2025-09-09", updatedAt = "2025-09-09")

        assertEquals(emptyList(), discover(sightings, trips = listOf(trip)))
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
        assertEquals("上饶、金华一日", name("2025-11-19", "2025-11-19", listOf("上饶", "金华")))
        assertEquals("成都、雅安等", name("2025-10-20", "2025-10-28", listOf("成都", "成都", "绵阳", "雅安", "雅安", "康定", "康定")))
        assertEquals("某地一日", name("2025-11-19", "2025-11-19", emptyList()))
        assertEquals("香港", TripDiscovery.shortCity("香港特别行政区"))
        assertEquals("沙市", TripDiscovery.shortCity("沙市"))
    }

    @Test
    fun `named after the cities away from home, else the farthest record`() {
        val hills = Place("山里", 28.00, 112.00)
        val sightings = listOf(checkin("A", "2025-06-10", "home"), at("2025-06-10", hills), checkin("B", "2025-06-11", "home", 20))

        assertEquals("山里", discover(sightings).single().name)
        val named = TripDiscovery.discover(sightings, emptyList(), homes, cityAt = { lat, _ -> "山城市".takeIf { lat < 29 } }, today = "2025-12-31")
        assertEquals("山城", named.single().name)
    }

    @Test
    fun `Moments are where their places are, entries where their points are, check-ins with their uid`() {
        val places = mapOf("coast" to coast)
        val moment = MomentRecord(
            "M", "2025-06-10T09:00:00+08:00", listOf("coast", "coast"), "",
            media = listOf(MomentMedia("a", MediaType.IMAGE), MomentMedia("b", MediaType.VIDEO)),
            createdAt = "2025-06-10", updatedAt = "2025-06-10",
        )
        val checkin = buildJsonObject {
            put("uid", "C")
            put("at", "2025-06-11T20:00:00+08:00")
            putJsonObject("point") { put("lat", 28.0); put("lng", 112.0) }
            put("place", "coast")
            putJsonObject("parts") { putJsonObject("checkin") { put("venue", "海边") } }
        }
        val sightings = TripDiscovery.sightingsOf(listOf(moment), listOf(checkin, buildJsonObject { put("uid", "N") }), places)

        assertEquals(listOf("2025-06-10" to 35.0, "2025-06-10" to 35.0, "2025-06-11" to 28.0), sightings.map { it.day to it.lat })
        // Its photos and videos counted once, at its first place.
        assertEquals(listOf(2, 0, 0), sightings.map { it.photos })
        assertEquals("海边", sightings.last().place?.name)
        assertEquals(listOf(null, null, "C"), sightings.map { it.checkin })
        assertEquals("coast", sightings.last().placeId)
        assertTrue(sightings.last().time == "2025-06-11T20:00:00+08:00")
    }
}
