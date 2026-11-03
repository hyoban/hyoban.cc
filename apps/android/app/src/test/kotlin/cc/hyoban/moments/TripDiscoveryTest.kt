package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Division
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // Formatting accepts qualified destinations, including one recorded only once.
        assertEquals("上饶、金华一日", name("2025-11-19", "2025-11-19", listOf("上饶", "金华")))
        assertEquals("成都、雅安等", name("2025-10-20", "2025-10-28", listOf("成都", "成都", "绵阳", "雅安", "雅安", "康定", "康定")))
        assertEquals("广州、深圳等", name("2025-08-11", "2025-08-14", listOf("广州", "广州", "佛山", "佛山", "深圳", "深圳", "深圳", "广州", "珠海市区", "珠海市区")))
        assertEquals("乌鲁木齐、伊宁等", name("2025-08-11", "2025-08-20", listOf("乌鲁木齐", "乌鲁木齐", "伊宁", "伊宁", "喀什", "喀什", "和田", "和田")))
        // Stops on the way with a few records each give way to where most of them were.
        assertEquals("五一福州、厦门等", name("2026-05-01", "2026-05-04", listOf("宁波", "宁波", "台州", "台州", "温州", "温州", "福州", "福州", "福州", "福州", "厦门", "厦门", "厦门")))
        assertEquals("某地一日", name("2025-11-19", "2025-11-19", emptyList()))
    }

    @Test
    fun `a place gone to often is away each time, unless it is a home set by hand`() {
        val visits = listOf("2025-02-01", "2025-02-02", "2025-02-03", "2025-05-01", "2025-05-02", "2025-05-03", "2025-08-01", "2025-08-02", "2025-08-03", "2025-11-01", "2025-11-02")
        val sightings = year(visits.toSet()) + visits.map { at(it, family) }

        assertEquals(
            setOf("2025-02-01..2025-02-03", "2025-05-01..2025-05-03", "2025-08-01..2025-08-03", "2025-11-01..2025-11-02"),
            TripDiscovery.discover(sightings, emptyList()).map { it.key }.toSet(),
        )
        assertEquals(emptyList(), TripDiscovery.discover(sightings, emptyList(), atHome = { lat, _ -> lat < 27 }))
    }

    @Test
    fun `a home set by hand is a city by its id or a whole province by two digits`() {
        assertTrue(TripDiscovery.isHome("350700", setOf("350700")))
        assertTrue(TripDiscovery.isHome("350700", setOf("35")))
        assertFalse(TripDiscovery.isHome("330100", setOf("35", "350700")))
        assertFalse(TripDiscovery.isHome(null, setOf("35")))
    }

    @Test
    fun `where home is now is the area with records on the most days`() {
        assertEquals(home.latitude to home.longitude, TripDiscovery.homeOf(year(emptySet()) + days("2025-06-10", "2025-06-13").map { at(it, coast) }, "2025-07-01"))
        assertEquals(null, TripDiscovery.homeOf(emptyList(), "2025-07-01"))
    }

    @Test
    fun `home follows a move`() {
        // Lived at home through May, then far away at the coast, a record every other day.
        val before = generateSequence(LocalDate.parse("2025-01-01")) { it.plusDays(2) }.takeWhile { it.monthValue <= 5 }.map { at(it.toString(), home) }
        val after = generateSequence(LocalDate.parse("2025-06-01")) { it.plusDays(2) }.takeWhile { it.year == 2025 }.map { at(it.toString(), coast) }

        assertEquals(emptyList(), TripDiscovery.discover((before + after).toList(), emptyList()))
    }

    @Test
    fun `the best come first, by photos kept and days with records up to a week, then the newest`() {
        val short = days("2025-03-01", "2025-03-02")
        val long = days("2025-06-10", "2025-06-15")
        val shots = days("2025-09-05", "2025-09-06")
        val plain = days("2025-11-05", "2025-11-06")
        // Four weeks away with a record on three days alone, and three weeks with one every day.
        val sparse = days("2025-07-01", "2025-07-28")
        val stay = days("2025-12-01", "2025-12-21")
        val sightings = year((short + long + shots + plain + sparse + stay).toSet()) +
            (short + long + plain + stay).map { at(it, coast) } + shots.map { at(it, coast).copy(photos = 12) } +
            listOf("2025-07-01", "2025-07-14", "2025-07-28").map { at(it, hills) }

        // Content ranks supported trips; isolated observations over four weeks do not establish one.
        assertEquals(
            listOf("2025-09-05", "2025-12-01", "2025-06-10", "2025-11-05", "2025-03-01"),
            TripDiscovery.discover(sightings, emptyList()).map { it.start },
        )
        assertEquals(24, TripDiscovery.discover(sightings, emptyList()).first().photos)
    }

    @Test
    fun `a quiet stretch away stays one trip, and a long one parts two`() {
        // Away from 03-01 to 03-20 with a record now and then; then nothing for weeks, still away.
        val away = days("2025-03-01", "2025-04-30").toSet()
        val sightings = year(away) + listOf("2025-03-01", "2025-03-09", "2025-03-20", "2025-04-20", "2025-04-21").map { at(it, hills) } + at("2025-03-01", hills, 15)

        assertEquals(
            listOf("2025-03-01..2025-03-20", "2025-04-20..2025-04-21"),
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
    fun `records in one place alone have nothing away`() {
        assertEquals(emptyList(), TripDiscovery.discover(days("2025-01-01", "2025-01-03").map { at(it, coast) }, emptyList()))
    }

    @Test
    fun `a nearby outing needs a stay, not just a pair of points outside town`() {
        val day = "2025-06-14"
        val nearby = Place("Lakeside", 31.65, 121.00)
        val base = year(setOf(day))
        assertEquals(listOf("$day..$day"), TripDiscovery.discover(base + listOf(at(day, nearby, 10), at(day, nearby, 13)), emptyList()).map { it.key })
        assertEquals(emptyList(), TripDiscovery.discover(base + listOf(at(day, nearby, 10), at(day, nearby, 11)), emptyList()))
        assertEquals(emptyList(), TripDiscovery.discover(base + listOf(at(day, nearby, 10), at(day, nearby, 20)), emptyList()))
    }

    @Test
    fun `moving through a region does not establish a nearby stay`() {
        val day = "2025-06-14"
        val sightings = year(setOf(day)) + listOf(
            at(day, Place("North", 31.60, 121.00), 10),
            at(day, Place("East", 31.00, 121.80), 12),
        )
        assertEquals(emptyList(), TripDiscovery.discover(sightings, emptyList()))
    }

    @Test
    fun `crossing the departure radius during an outing does not split it`() {
        val day = "2025-06-14"
        val nearby = Place("Lakeside", 31.65, 121.00)
        val edge = Place("Outskirts", 31.40, 121.00)
        val sightings = year(setOf(day)) + listOf(at(day, nearby, 10), at(day, nearby, 12), at(day, edge, 13), at(day, nearby, 14))
        val trip = TripDiscovery.discover(sightings, emptyList()).single()
        assertEquals(4, trip.records)
    }

    @Test
    fun `returning home separates two nearby outings`() {
        val day = "2025-06-14"
        val nearby = Place("Lakeside", 31.65, 121.00)
        val sightings = year(setOf(day)) + listOf(at(day, nearby, 8), at(day, nearby, 10), at(day, home, 12), at(day, nearby, 14))
        assertEquals(2, TripDiscovery.discover(sightings, emptyList()).single().records)
    }

    @Test
    fun `a transfer city is omitted when another city has a stay`() {
        val day = "2025-06-14"
        val transfer = Place("Interchange", 33.00, 121.00, division = Division("000001", "Region", "Transfer"))
        val destination = Place("Museum", 35.00, 121.00, division = Division("000002", "Region", "Destination"))
        val sightings = year(setOf(day)) + listOf(
            at(day, transfer, 8), at(day, transfer, 9), at(day, destination, 11), at(day, destination, 14),
            at(day, transfer, 17), at(day, transfer, 18),
        )
        assertEquals("Destination一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `overnight station observations do not establish a destination`() {
        val first = "2025-06-14"
        val second = "2025-06-15"
        val transfer = Place("Interchange", 33.00, 121.00, kind = "transport", division = Division("000001", "Region", "Transfer"))
        val destination = Place("Museum", 35.00, 121.00, division = Division("000002", "Region", "Destination"))
        val sightings = year(setOf(first, second)) + listOf(
            at(first, transfer, 8), at(first, transfer, 9), at(second, transfer, 8),
            at(second, destination, 11), at(second, destination, 14),
        )
        assertEquals("Destination周末", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `isolated distant observations do not establish a trip`() {
        val days = listOf("2025-06-01", "2025-06-09", "2025-06-20")
        assertEquals(emptyList(), TripDiscovery.discover(year(days("2025-06-01", "2025-06-20").toSet()) + days.map { at(it, hills) }, emptyList()))
        val day = "2025-06-14"
        assertEquals(emptyList(), TripDiscovery.discover(year(setOf(day)) + listOf(at(day, hills, 10), at(day, hills, 11)), emptyList()))
    }

    @Test
    fun `several local stops preserve a short visit beside a longer stay`() {
        val day = "2025-06-14"
        val city = Division("000001", "Region", "Brief")
        val brief = listOf(
            at(day, Place("Gallery", 33.00, 121.00, division = city), 9),
            at(day, Place("Garden", 33.01, 121.00, division = city), 9).copy(at = epochOf("${day}T09:20:00+08:00")),
            at(day, Place("Cafe", 33.02, 121.00, division = city), 10),
        )
        val sightings = year(setOf(day)) + brief + listOf(at(day, coast, 13), at(day, coast, 16))
        assertEquals("Brief、海滨一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `an activity recorded once is kept but a known station photo is not a destination`() {
        val day = "2025-06-14"
        val stop = Place("Station", 33.0, 121.0, kind = "transport", division = Division("000001", "Region", "Transfer"))
        val park = Place("Park", 34.0, 121.0, kind = "scenic", division = Division("000002", "Region", "Brief"))
        val sightings = year(setOf(day)) + at(day, stop, 9).copy(photos = 4) + at(day, park, 11) + listOf(at(day, coast, 13), at(day, coast, 16))
        assertEquals("Brief、海滨一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `a missing stay does not restore duplicate platform records as destinations`() {
        val day = "2025-06-14"
        val stop = Place("Interchange", 33.0, 121.0, division = Division("000001", "Region", "Transfer"))
        val visit = Place("Garden", 35.0, 121.0, kind = "scenic", division = Division("000002", "Region", "Brief"))
        val sightings = year(setOf(day)) + listOf(at(day, stop, 9), at(day, stop, 9), at(day, visit, 12).copy(photos = 1))
        assertEquals("Brief一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `sparse overnight presence remains a destination beside a dense visit`() {
        val first = "2025-06-14"
        val second = "2025-06-15"
        val third = "2025-06-16"
        val sightings = year(setOf(first, second, third)) + listOf(at(first, family), at(second, family), at(third, coast, 10), at(third, coast, 13))
        assertEquals("老家、海滨", TripDiscovery.discover(sightings, emptyList(), cityAt = { lat, _ -> "老家".takeIf { lat < 27 } }).single().name)
    }

    @Test
    fun `the home city is omitted beside destinations outside it`() {
        val day = "2025-06-14"
        val outskirts = Place("Outskirts", 31.7, 121.0)
        val sightings = year(setOf(day)) + listOf(at(day, outskirts, 8).copy(photos = 1), at(day, coast, 12), at(day, coast, 15))
        assertEquals("海滨一日", TripDiscovery.discover(sightings, emptyList(), cityAt = { lat, _ -> if (lat < 32) "Home" else "海滨" }).single().name)
        val local = year(setOf(day)) + listOf(at(day, outskirts, 8), at(day, outskirts, 11))
        assertEquals("Home一日", TripDiscovery.discover(local, emptyList(), cityAt = { _, _ -> "Home" }).single().name)
    }

    @Test
    fun `photos at an untyped interchange alone are not a visit`() {
        val day = "2025-06-14"
        val stop = Place("Interchange", 33.0, 121.0, division = Division("000001", "Region", "Transfer"))
        val sightings = year(setOf(day)) + listOf(at(day, stop, 8).copy(photos = 3), at(day, stop, 9).copy(photos = 2), at(day, coast, 12), at(day, coast, 15))
        assertEquals("海滨一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `a nearby wait at a known station is not an outing`() {
        val day = "2025-06-14"
        val stop = Place("Interchange", 31.65, 121.0, kind = "transport")
        assertEquals(emptyList(), TripDiscovery.discover(year(setOf(day)) + listOf(at(day, stop, 8), at(day, stop, 11)), emptyList()))
    }

    @Test
    fun `multi-day visits to distinct parts of a city retain a sparse destination`() {
        val first = "2025-06-14"
        val middle = "2025-06-18"
        val last = "2025-06-22"
        val city = Division("000001", "Region", "Sparse")
        val sparse = listOf(
            at(first, Place("Square", 33.0, 121.0, division = city)),
            at(middle, Place("Park", 33.01, 121.0, division = city)),
            at(last, Place("Garden", 33.02, 121.0, division = city)),
        )
        val sightings = year(days(first, last).toSet()) + sparse + listOf(at(last, coast, 15), at(last, coast, 18))
        assertEquals("Sparse、海滨", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `different local stops across a quiet afternoon establish a visit`() {
        val day = "2025-06-14"
        val division = Division("000001", "Region", "Quiet")
        val before = Place("Park", 33.0, 121.0, division = division)
        val after = Place("Gallery", 33.02, 121.0, division = division)
        val sightings = year(setOf(day)) + listOf(at(day, before, 9), at(day, after, 17))
        assertEquals("Quiet一日", TripDiscovery.discover(sightings, emptyList()).single().name)
    }

    @Test
    fun `crossing midnight alone does not establish overnight presence`() {
        val first = "2025-06-14"
        val second = "2025-06-15"
        val before = at(first, hills).copy(at = epochOf("${first}T23:59:00+08:00"))
        val after = at(second, hills).copy(at = epochOf("${second}T00:01:00+08:00"))
        val base = year(setOf(first, second))
        assertEquals(emptyList(), TripDiscovery.discover(base + listOf(before, after), emptyList()))
        assertEquals(1, TripDiscovery.discover(base + listOf(before, after.copy(at = epochOf("${second}T02:00:00+08:00"))), emptyList()).size)
    }

    @Test
    fun `an observed departure interrupts a stay even at a transport place`() {
        val day = "2025-06-14"
        val here = Place("Square", 33.0, 121.0)
        val elsewhere = Place("Station", 33.5, 121.0, kind = "transport")
        val sightings = year(setOf(day)) + listOf(at(day, here, 9), at(day, elsewhere, 10), at(day, here, 11))
        assertEquals(emptyList(), TripDiscovery.discover(sightings, emptyList()))
    }

    @Test
    fun `an earlier point does not erase a later valid stay window`() {
        val day = "2025-06-14"
        val middle = Place("Square", 31.6, 121.0)
        val east = Place("Garden", 31.6, 121.2)
        val west = Place("Cafe", 31.6, 120.8)
        val visit = listOf(
            at(day, middle).copy(at = epochOf("${day}T09:30:00+08:00")),
            at(day, east, 10), at(day, middle, 11),
        )
        val base = year(setOf(day))
        val before = TripDiscovery.discover(base + visit, emptyList()).single()
        val after = TripDiscovery.discover(base + visit + at(day, west, 9), emptyList()).single()
        assertEquals(before.key, after.key)
    }

    @Test
    fun `a fallback name comes from visit evidence instead of a farther station`() {
        val day = "2025-06-14"
        val garden = Place("Garden", 33.0, 121.0, kind = "scenic")
        val station = Place("Station", 35.0, 121.0, kind = "transport")
        val sightings = year(setOf(day)) + listOf(at(day, garden, 10), at(day, station, 14))
        val trip = TripDiscovery.discover(sightings, emptyList(), cityAt = { lat, _ -> "Transfer".takeIf { lat > 34 } }).single()
        assertEquals("Garden一日", trip.name)
    }

    @Test
    fun `Moments are where their places are, entries where their points are`() {
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
        }
        val sightings = TripDiscovery.sightingsOf(listOf(moment), listOf(checkin, buildJsonObject { put("uid", "N") }), places)

        assertEquals(listOf("2025-06-10" to 35.0, "2025-06-10" to 35.0, "2025-06-11" to 28.0), sightings.map { it.day to it.lat })
        // Its photos and videos counted once, at its first place.
        assertEquals(listOf(2, 0, 0), sightings.map { it.photos })
        assertEquals("海边", sightings.last().place?.name)
    }
}
