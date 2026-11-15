package cc.hyoban.moments

import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.FootprintRoutes
import cc.hyoban.moments.data.trips.spanOf
import cc.hyoban.moments.footprints.Footprints
import cc.hyoban.moments.footprints.RecordingState
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackSelection
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FootprintRoutesTest {
    private val zone = ZoneOffset.ofHours(8)
    private fun at(stamp: String) = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

    private fun trip(id: String, start: String, end: String, startAt: String? = null, endAt: String? = null) =
        TripRecord(id, "测试", start, end, startAt = startAt, endAt = endAt, createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")

    private class Kept(val points: List<TrackPoint>) : Footprints {
        var reads = 0
        override val revision = MutableStateFlow(0L)
        override fun state() = RecordingState()
        override fun days() = points.map { java.time.Instant.ofEpochMilli(it.time).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toString() }.distinct().sortedDescending()
        override fun points(selection: TrackSelection): List<TrackPoint> {
            reads++
            return points.filter { it.time in selection.from!!..selection.to!! }
        }
    }

    private fun point(stamp: String, lat: Double, lng: Double, segment: String) = TrackPoint(at(stamp), lat, lng, 5.0, segment = segment)

    @Test
    fun `a trip's span runs from its times or else across its whole days`() {
        assertEquals(at("2026-10-01T00:00:00+08:00")..at("2026-10-03T00:00:00+08:00") - 1, spanOf(trip("t", "2026-10-01", "2026-10-02"), zone))
        assertEquals(
            at("2026-10-01T09:30:00+08:00")..at("2026-10-02T18:00:00+08:00"),
            spanOf(trip("t", "2026-10-01", "2026-10-02", "2026-10-01T09:30:00+08:00", "2026-10-02T18:00:00+08:00"), zone),
        )
    }

    @Test
    fun `a trip with footprints over its time is drawn by them, a stretch at a time`() {
        val kept = Kept(
            listOf(
                point("2026-10-01T08:00:00+08:00", 30.0, 120.0, "before"),
                point("2026-10-01T10:00:00+08:00", 30.0, 120.0, "a"),
                point("2026-10-01T10:10:00+08:00", 30.01, 120.01, "a"),
                point("2026-10-01T10:20:00+08:00", 30.02, 120.0, "a"),
                point("2026-10-01T12:00:00+08:00", 30.1, 120.1, "b"),
                point("2026-10-01T12:30:00+08:00", 30.2, 120.2, "b"),
                point("2026-10-01T13:00:00+08:00", 30.3, 120.3, "lone"),
            ),
        )
        val reader = FootprintRoutes(kept)
        val trips = listOf(
            trip("in", "2026-10-01", "2026-10-01", "2026-10-01T09:00:00+08:00", "2026-10-01T23:00:00+08:00"),
            trip("none", "2026-11-01", "2026-11-02"),
        )
        val now = at("2026-11-15T00:00:00+08:00")
        val routes = reader.routes(trips, fresh = false, now = now)
        assertEquals(setOf("in"), routes.keys)
        val segments = routes.getValue("in")
        assertEquals(2, segments.size)
        assertTrue(segments.none { it.straight })
        assertEquals(120.0 to 30.0, segments[0].line.first())
        assertEquals(120.2 to 30.2, segments[1].line.last())
        assertFalse(segments.any { line -> line.line.contains(120.3 to 30.3) })

        // A trip long over isn't read again until what is kept changes otherwise.
        val reads = kept.reads
        reader.routes(trips, fresh = false, now = now)
        assertEquals(reads, kept.reads)
        reader.routes(trips, fresh = true, now = now)
        assertEquals(reads + 1, kept.reads)
    }
}
