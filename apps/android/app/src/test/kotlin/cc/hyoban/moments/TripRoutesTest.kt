package cc.hyoban.moments

import cc.hyoban.moments.data.trips.RouteData
import cc.hyoban.moments.data.trips.RouteLeg
import cc.hyoban.moments.data.trips.RoutePoint
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.RouteStop
import cc.hyoban.moments.data.trips.segmentsOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** A route is cut into stretches the way the site's overview cuts it. */
class TripRoutesTest {
    private val a = RoutePoint(30.0, 120.0)
    private val b = RoutePoint(31.0, 121.0)
    private val c = RoutePoint(32.0, 122.0)
    private val d = RoutePoint(33.0, 123.0)

    @Test
    fun `drives run together, and a flight without a line is a straight stretch of its own`() {
        val data = RouteData(
            stops = listOf(RouteStop(listOf(a)), RouteStop(listOf(c), waypoints = listOf(b)), RouteStop(listOf(d))),
            legs = listOf(
                RouteLeg(listOf(listOf(120.0, 30.0), listOf(120.5, 30.5), listOf(121.0, 31.0))),
                RouteLeg(listOf(listOf(121.0, 31.0), listOf(122.0, 32.0))),
                RouteLeg(mode = "plane"),
            ),
        )

        assertEquals(
            listOf(
                RouteSegment(listOf(120.0 to 30.0, 120.5 to 30.5, 121.0 to 31.0, 122.0 to 32.0), straight = false),
                RouteSegment(listOf(122.0 to 32.0, 123.0 to 33.0), straight = true),
            ),
            segmentsOf(data),
        )
    }

    @Test
    fun `a ferry taken both ways is drawn once, and the drive after it starts anew`() {
        val data = RouteData(
            stops = listOf(RouteStop(listOf(a)), RouteStop(listOf(b)), RouteStop(listOf(a))),
            trailingWaypoints = listOf(c),
            legs = listOf(RouteLeg(mode = "ferry"), RouteLeg(mode = "ferry"), RouteLeg()),
        )

        assertEquals(
            listOf(
                RouteSegment(listOf(120.0 to 30.0, 121.0 to 31.0), straight = true),
                RouteSegment(listOf(120.0 to 30.0, 122.0 to 32.0), straight = false),
            ),
            segmentsOf(data),
        )
    }
}
