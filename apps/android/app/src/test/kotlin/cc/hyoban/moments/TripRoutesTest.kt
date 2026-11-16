package cc.hyoban.moments

import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.data.trips.parseRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The API's routes come through as each trip's stretches, and anything else is left alone. */
class TripRoutesTest {
    @Test
    fun `each trip's stretches come as they were served`() {
        val routes = parseRoutes(
            """
            {"revision": 12, "trips": {
              "ferry": [
                {"line": [[120.0, 30.0], [120.5, 30.5], [121.0, 31.0]], "straight": false},
                {"line": [[121.0, 31.0], [122.0, 32.0]], "straight": true, "extra": 1}
              ]
            }}
            """,
        )

        assertEquals(
            mapOf(
                "ferry" to listOf(
                    RouteSegment(listOf(120.0 to 30.0, 120.5 to 30.5, 121.0 to 31.0), straight = false),
                    RouteSegment(listOf(121.0 to 31.0, 122.0 to 32.0), straight = true),
                ),
            ),
            routes,
        )
    }

    @Test
    fun `an answer that isn't routes is none`() {
        assertNull(parseRoutes("""{"error": "Not found."}"""))
        assertNull(parseRoutes("not json"))
    }

    // As trip-map's test: a drive that passes its end 200 m off, runs on about 5 km and comes back.
    private val spurred = listOf(118.0 to 31.0, 118.1 to 31.0, 118.2 to 31.0, 118.25 to 31.0, 118.25 to 31.002, 118.2 to 31.0018)

    @Test
    fun cutsTheSpurDrivenOnAndBackToReachAPlace() {
        assertEquals(listOf(118.0 to 31.0, 118.1 to 31.0, 118.2 to 31.0, 118.2 to 31.0018), Routers.trimTurnBacks(spurred))
        assertEquals(listOf(118.2 to 31.0018, 118.2 to 31.0, 118.1 to 31.0, 118.0 to 31.0), Routers.trimTurnBacks(spurred.reversed()))
    }

    @Test
    fun keepsALineThatReachesItsPlacesOrLoopsRoundToThem() {
        val straight = listOf(118.0 to 31.0, 118.1 to 31.0, 118.2 to 31.0)
        assertEquals(straight, Routers.trimTurnBacks(straight))
        val loop = listOf(118.0 to 31.0, 118.1 to 31.0, 118.12 to 31.0, 118.12 to 31.018, 118.1 to 31.018, 118.1 to 31.0025)
        assertEquals(loop, Routers.trimTurnBacks(loop))
    }
}
