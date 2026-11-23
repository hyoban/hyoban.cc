package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.map.MapPlace
import cc.hyoban.moments.ui.map.MapTime
import cc.hyoban.moments.ui.map.TimedLayer
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.ui.map.footprintSelection
import cc.hyoban.moments.ui.map.keptIn
import cc.hyoban.moments.ui.map.layerPlaces
import java.time.ZoneOffset
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each map layer keeps what falls in its own time, or every layer's, of made-up records. */
class MapTimeTest {
    private val today = LocalDate.of(2026, 3, 10)

    private fun place(id: String, moments: List<String> = emptyList(), checkins: List<String> = emptyList()) =
        MapPlace(id, Place(id, 0.0, 0.0), moments.size + checkins.size, 2020,
            momentDays = moments.map { LocalDate.parse(it) }, stopDays = checkins.map { LocalDate.parse(it) })

    private val places = listOf(
        place("lake", moments = listOf("2024-05-01")),
        place("cafe", checkins = listOf("2026-03-01")),
        place("hill", moments = listOf("2021-07-04"), checkins = listOf("2024-12-30")),
    )
    private val trips = listOf(
        TripRecord("spring", "Spring", "2024-04-28", "2024-05-03", createdAt = "2024-05-04", updatedAt = "2024-05-04"),
        TripRecord("newyear", "New year", "2025-12-30", "2026-01-02", createdAt = "2026-01-03", updatedAt = "2026-01-03"),
    )

    @Test
    fun timesKeepTheirWordsAndDays() {
        listOf(
            MapTime.All, MapTime.ThisYear, MapTime.LastYear, MapTime.Recent,
            MapTime.Year(2024), MapTime.Month(2024, 5), MapTime.Days(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 5, 20)),
        ).forEach { assertEquals(it, MapTime.decode(it.encode())) }
        assertNull(MapTime.decode("nonsense"))
        assertNull(MapTime.All.days(today))
        assertEquals(LocalDate.of(2026, 2, 9)..today, MapTime.Recent.days(today))
        assertEquals(LocalDate.of(2025, 1, 1)..LocalDate.of(2025, 12, 31), MapTime.LastYear.days(today))
        assertEquals(LocalDate.of(2024, 5, 1)..LocalDate.of(2024, 5, 31), MapTime.Month(2024, 5).days(today))
    }

    @Test
    fun everyTimeKeepsEverything() {
        val kept = keptIn({ MapTime.All }, places, trips, stops = true, today)
        assertNull(kept.places)
        assertNull(kept.trips)
        assertEquals(places, kept.fogPlaces)
    }

    @Test
    fun eachLayerKeepsByItsOwnTime() {
        val times = mapOf(TimedLayer.PLACES to MapTime.Year(2024), TimedLayer.STOPS to MapTime.ThisYear, TimedLayer.ROUTES to MapTime.Year(2026))
        val kept = keptIn({ times[it] ?: MapTime.All }, places, trips, stops = true, today)
        // A Moment in 2024 keeps the lake; a check-in this year the cafe; the hill's are neither.
        assertEquals(setOf("lake", "cafe"), kept.places)
        // A trip under way at any time in 2026, the one across the new year among them.
        assertEquals(setOf("newyear"), kept.trips)
        assertEquals(places, kept.fogPlaces)
    }

    @Test
    fun checkinsNotCountedKeepNothingByThem() {
        val kept = keptIn({ if (it == TimedLayer.PLACES) MapTime.Year(2024) else MapTime.All }, places, trips, stops = false, today)
        assertEquals(setOf("lake"), kept.places)
    }

    @Test
    fun checkinsAloneDrawTheirPlaces() {
        // 地点 off: the places with a check-in, and the one open, are drawn; the lake has only Moments.
        assertEquals(listOf("cafe", "hill"), layerPlaces(places, moments = false, stops = true, open = null)!!.map { it.id })
        assertEquals(listOf("lake"), layerPlaces(places, moments = false, stops = false, open = "lake")!!.map { it.id })
        assertNull(layerPlaces(places, moments = true, stops = true, open = null))
        // In 2024 by check-ins alone, the hill's; a Moment then keeps nothing while 地点 is off.
        val kept = keptIn({ MapTime.Year(2024) }, places, trips, stops = true, today, moments = false)
        assertEquals(setOf("hill"), kept.places)
    }

    @Test
    fun footprintsReadTheirTime() {
        assertEquals(TrackSelection(day = "2026-03-10"), footprintSelection(MapTime.Today, today, ZoneOffset.UTC))
        assertEquals(TrackSelection(session = "s1"), footprintSelection(MapTime.Trip("s1"), today, ZoneOffset.UTC))
        assertEquals(TrackSelection(), footprintSelection(MapTime.All, today, ZoneOffset.UTC))
        val month = footprintSelection(MapTime.Month(2026, 2), today, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), month.from)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1, month.to)
        assertEquals(MapTime.Trip("s1"), MapTime.decode(MapTime.Trip("s1").encode()))
    }
}
