package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.StraightLeg
import cc.hyoban.moments.data.spec.TravelMode
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.LegRequest
import cc.hyoban.moments.data.trips.PhoneRoutes
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.data.trips.legKey
import cc.hyoban.moments.data.trips.legsOf
import cc.hyoban.moments.data.trips.requestOf
import cc.hyoban.moments.data.trips.segmentsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone routes a trip the API hasn't as the API would, and draws it as the site does. */
class PhoneRoutesTest {
    private val a = Stop("a", Place("甲", 30.0, 120.0), 0)
    private val b = Stop("b", Place("乙", 30.02, 120.01), 1)
    private val c = Stop("c", Place("丙", 30.5, 121.0), 2)
    private val d = Stop("d", Place("丁", 31.0, 121.5), 3)

    private fun trip(vararg straight: StraightLeg) =
        TripRecord("t", "测试", "2026-10-01", "2026-10-03", straightLegs = straight.toList(), createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")

    @Test
    fun `a Valhalla shape decodes as the API decodes it`() {
        assertEquals(listOf(120.0 to 30.0, 120.01 to 30.02), Routers.decodePolyline("_w`fx@__f{cF_af@_pR"))
    }

    @Test
    fun `a leg's key is the API's`() {
        assertEquals("120.00000,30.00000;120.01000,30.02000", legKey(a.place, b.place))
    }

    @Test
    fun `drives join into one stretch, a ferry is a dashed one of its own, and taken back it is drawn once`() {
        val legs = legsOf(trip(StraightLeg("b", "c", TravelMode.FERRY)), listOf(a, b, c, b, d))
        assertEquals(listOf(null, TravelMode.FERRY, TravelMode.FERRY, null), legs.map { it.by })
        assertEquals(null, requestOf(legs[1]), "a ferry is never asked for")

        val road = listOf(120.0 to 30.0, 120.005 to 30.01, 120.01 to 30.02)
        val segments = segmentsOf(legs, mapOf(legKey(a.place, b.place) to road))
        assertEquals(
            listOf(
                RouteSegment(road, straight = false),
                RouteSegment(listOf(120.01 to 30.02, 121.0 to 30.5), straight = true),
                // The ferry back is left out, and the drive after it starts anew, straight with no route kept.
                RouteSegment(listOf(120.01 to 30.02, 121.5 to 31.0), straight = false),
            ),
            segments,
        )
    }

    @Test
    fun `each leg is asked for once, a line kept, no way kept as none, and a failure asked again`() = runBlocking {
        val file = File(Files.createTempDirectory("routes").toFile(), "phone-routes.json")
        val asked = mutableListOf<String>()
        var failing = true
        val routes = PhoneRoutes(file, Dispatchers.Unconfined, pause = 0, route = { leg ->
            asked += leg.key
            when (leg.key) {
                "road" -> listOf(1.0 to 2.0, 3.0 to 4.0)
                "sea" -> null
                else -> if (failing) throw IOException("dropped") else listOf(5.0 to 6.0, 7.0 to 8.0)
            }
        })
        fun leg(key: String) = LegRequest(key, false, 0.0 to 0.0, 1.0 to 1.0)

        routes.fill(listOf(leg("road"), leg("sea"), leg("flaky"), leg("road")))
        assertEquals(mapOf("road" to listOf(1.0 to 2.0, 3.0 to 4.0), "sea" to emptyList()), routes.lines.value)

        failing = false
        asked.clear()
        routes.fill(listOf(leg("road"), leg("sea"), leg("flaky")))
        assertEquals(listOf("flaky"), asked)

        val again = PhoneRoutes(file, Dispatchers.Unconfined)
        again.load()
        assertEquals(routes.lines.value, again.lines.value, "kept past a restart")
    }
}
