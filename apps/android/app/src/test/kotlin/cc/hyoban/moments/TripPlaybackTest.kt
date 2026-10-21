package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.StraightLeg
import cc.hyoban.moments.data.spec.TravelMode
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.Waypoint
import cc.hyoban.moments.data.trips.PlaybackClock
import cc.hyoban.moments.data.trips.PlaybackRoute
import cc.hyoban.moments.data.trips.PlaybackSlide
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Travel
import cc.hyoban.moments.data.trips.TripPlayback
import cc.hyoban.moments.data.trips.shortPlaceName
import cc.hyoban.moments.data.trips.tripPlayback
import cc.hyoban.moments.ui.trips.PlaybackController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A trip is played back as the site plays it: its cards, its pacing, and a route that never stops. */
class TripPlaybackTest {
    private val places = mapOf(
        "a" to Place("A", 30.0, 120.0),
        "w" to Place("W（服务区）", 30.0, 120.5),
        "b" to Place("B", 30.0, 121.0),
        "c" to Place("C", 30.0, 122.0),
    )

    // Out from A to C by road, then back to A the same way by ferry.
    private val out = RouteSegment(listOf(120.0 to 30.0, 120.5 to 30.0, 121.0 to 30.0, 121.5 to 30.0, 122.0 to 30.0), straight = false)
    private val back = RouteSegment(listOf(122.0 to 30.0, 120.0 to 30.0), straight = true)

    private val trip = TripRecord(
        "t", "T", "2026-10-01", "2026-10-03",
        waypoints = listOf(Waypoint("2026-10-02T08:00:00+08:00", "w")),
        straightLegs = listOf(StraightLeg("c", "a", TravelMode.FERRY)),
        createdAt = "2026-10-01", updatedAt = "2026-10-01",
    )
    private val moments = listOf(
        moment("1", "2026-10-01T09:00:00+08:00", "a", text = "x".repeat(40)),
        moment("2", "2026-10-02T10:00:00+08:00", "b", photos = 3),
        moment("n", "2026-10-02T11:00:00+08:00", null),
        moment("3", "2026-10-02T18:00:00+08:00", "c"),
        moment("4", "2026-10-03T09:00:00+08:00", "a"),
    )
    private val playback = tripPlayback(trip, moments, places, listOf(out, back))

    @Test
    fun `a place is reached where the route first comes to it`() {
        val route = PlaybackRoute(listOf(out, back))
        val b = route.reach(121.0 to 30.0, 0.0)
        assertEquals(1.0, b, 1e-9)
        val c = route.reach(122.0 to 30.0, b)
        assertEquals(2.0, c, 1e-9)
        // Back at A on the way home, not where the trip set out.
        assertEquals(4.0, route.reach(120.0 to 30.0, c), 1e-9)
        assertEquals(listOf(121.75 to 30.0, 122.0 to 30.0, 121.0 to 30.0), route.line(1.75, 3.0))
    }

    @Test
    fun `only Moments with a place are stops, each after the waypoints passed before it`() {
        assertEquals(listOf("1", "2", "3", "4"), playback.stops.map { it.moment.uid })
        assertEquals(listOf("a", "w", "b", "c", "a"), playback.points.map { it.id })
        assertEquals(listOf(false, true, false, false, false), playback.points.map { it.waypoint })
        assertEquals(listOf(Travel.CAR, Travel.CAR, Travel.CAR, Travel.FERRY), (0..3).map(playback::travel))
    }

    @Test
    fun `each day opens with a card of its places ahead, and the look back ends it`() {
        val slides = playback.slides
        assertEquals(PlaybackSlide.Intro, slides.first())
        assertEquals(PlaybackSlide.Summary, slides.last())
        val days = slides.filterIsInstance<PlaybackSlide.Day>()
        assertEquals(listOf(1, 2, 3), days.map { it.day })
        assertEquals(listOf("W（服务区）", "B", "C"), days[1].places)
        // The second day passes its waypoint as the step on to its first Moment.
        assertEquals(1 to 1, playback.waypointsBefore(slides[slides.indexOf(days[1]) + 1]))
        assertNull(TripPlayback.duration(PlaybackSlide.Summary))
    }

    @Test
    fun `a card lasts long enough for its photos and its words, as the site paces them`() {
        val stops = playback.slides.filterIsInstance<PlaybackSlide.Stop>()
        assertEquals(4_800L, TripPlayback.duration(stops[0]))
        assertEquals(4_500L, TripPlayback.duration(stops[1]))
        assertEquals(3_000L, TripPlayback.duration(stops[2]))
        assertEquals(3_000L, TripPlayback.duration(PlaybackSlide.Intro))
        assertEquals(1_600L + 30 * "W（服务区）BC".length, TripPlayback.duration(playback.slides.filterIsInstance<PlaybackSlide.Day>()[1]))
    }

    @Test
    fun `the clock runs on with the route between two times`() {
        assertEquals(PlaybackClock("2026-10-02", 2, "9:00"), playback.clock(1, 2, 0.5))
        assertEquals("08:00", playback.clock(1)?.time)
        assertEquals("眼镜烤肉", shortPlaceName("眼镜烤肉（老街店）"))
    }

    @Test
    fun `on a Moment's card the route drives on, arriving at the next place as the card ends`() {
        val controller = PlaybackController(playback, reduced = false)
        controller.begin()
        assertTrue(controller.playing)
        // Through the opening and the first day's opener to the first Moment.
        repeat(3_000 / 16 + 1) { controller.tick(16) }
        repeat(1_700 / 16 + 1) { controller.tick(16) }
        assertTrue(controller.slide is PlaybackSlide.Stop)
        controller.tick(2_400)
        val halfway = controller.drawnTo
        assertTrue(halfway > 0.0 && halfway < 0.5, "drawn to $halfway")
        assertTrue(controller.tip != null)
        controller.tick(2_400)
        // The next card is the second day's opener, the route at the waypoint before it.
        assertTrue(controller.slide is PlaybackSlide.Day)
        assertEquals(0.5, controller.drawnTo, 1e-9)
    }

    private fun moment(uid: String, occurredAt: String, place: String?, text: String = "", photos: Int = 0) = MomentRecord(
        uid,
        occurredAt,
        listOfNotNull(place),
        text,
        media = List(photos) { MomentMedia("$uid-$it", MediaType.IMAGE) },
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )
}
