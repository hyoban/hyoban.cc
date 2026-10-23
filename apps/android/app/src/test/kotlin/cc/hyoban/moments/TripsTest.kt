package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.Waypoint
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.daysOf
import cc.hyoban.moments.data.trips.stopsOf
import cc.hyoban.moments.data.trips.tripOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A Moment belongs to the trip it names, else to the one whose days include it. */
class TripsTest {
    private val ferry = trip("ferry", "2026-10-01", "2026-10-03", waypoints = listOf(Waypoint("2026-10-01T08:00:00+08:00", "nanjing")))
    private val later = trip("later", "2026-11-01", "2026-11-02")
    private val trips = listOf(later, ferry)
    private val places = mapOf(
        "nanjing" to Place("南京", 32.06, 118.79),
        "yantai" to Place("烟台", 37.54, 121.39),
        "dalian" to Place("大连", 38.91, 121.60),
    )

    @Test
    fun `a Moment names its trip, or falls within one`() {
        assertEquals("ferry", tripOf(moment("A", "2026-10-02T09:00:00+08:00"), trips)?.id)
        assertEquals("later", tripOf(moment("B", "2026-10-02T09:00:00+08:00", trip = "later"), trips)?.id)
        assertNull(tripOf(moment("C", "2026-10-20"), trips))
    }

    @Test
    fun `its Moments come day by day, and its stops in the order they were reached`() {
        val moments = listOf(
            moment("D", "2026-10-02T20:00:00+08:00", "dalian"),
            moment("Y", "2026-10-01T17:40:00+08:00", "yantai"),
            moment("Y2", "2026-10-02", "yantai"),
            moment("X", "2026-12-01", "dalian"),
        )

        assertEquals(
            listOf("2026-10-01" to listOf("Y"), "2026-10-02" to listOf("Y2", "D")),
            daysOf(ferry, moments, trips).map { (day, list) -> day to list.map { it.uid } },
        )
        assertEquals(listOf("nanjing", "yantai", "dalian"), stopsOf(ferry, moments, trips, places).map { it.placeId })
    }

    @Test
    fun `its covers pass over the photos kept for the record`() {
        val receipt = MomentMedia("receipt", MediaType.IMAGE, utility = true)
        val sea = MomentMedia("sea", MediaType.IMAGE)
        val moments = listOf(
            moment("A", "2026-10-01T09:00:00+08:00").copy(media = listOf(receipt, sea)),
            moment("B", "2026-10-02T09:00:00+08:00").copy(media = listOf(receipt.copy(uid = "screenshot"))),
        )

        assertEquals(listOf("sea"), coversOf(ferry, moments, trips).map { it.uid })
    }

    private fun trip(id: String, start: String, end: String, waypoints: List<Waypoint> = emptyList()) =
        TripRecord(id, id, start, end, waypoints = waypoints, createdAt = "$start", updatedAt = "$start")

    private fun moment(uid: String, occurredAt: String, place: String? = null, trip: String? = null) =
        MomentRecord(uid, occurredAt, listOfNotNull(place), "", trip = trip, createdAt = occurredAt, updatedAt = occurredAt)
}
