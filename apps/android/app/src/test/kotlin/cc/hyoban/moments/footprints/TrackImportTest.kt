package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class TrackImportTest {
    private val now = 1_800_000_000_000L

    private fun gpx(points: List<TrackPoint>, raw: Boolean) =
        StringWriter().also { writeGpx(it, points.asSequence(), raw) }.toString().byteInputStream()

    @Test fun rawSamplesComeBackWithEverythingTheyCarried() {
        val points = listOf(
            TrackPoint(now, 31.0, 121.0, 4.0, 12.5, "a", 3.2, 0.4,
                FixExtras(87.0, 5.0, 2.0, 10.0, "gps", 9, 30, 28.5)),
            TrackPoint(now + 1_000, 31.0001, 121.0001, 6.0, segment = "b"),
        )
        assertEquals(points, readGpx(gpx(points, raw = true)))
    }

    @Test fun eachSegmentOfALineStaysApart() {
        val points = listOf(
            TrackPoint(now, 31.0, 121.0, 4.0, segment = "a"),
            TrackPoint(now + 1_000, 31.0001, 121.0, 4.0, segment = "a"),
            TrackPoint(now + TRACK_GAP_MS + 2_000, 31.0002, 121.0, 4.0, segment = "a"),
        )
        val read = readGpx(gpx(points, raw = false))
        assertEquals(points.map { it.time }, read.map { it.time })
        assertEquals(read[0].segment, read[1].segment)
        assertNotEquals(read[1].segment, read[2].segment)
        assertEquals(4.0, read[0].accuracy, 0.0)
    }

    @Test fun leavesOutPointsItCannotPlace() {
        val file = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="31" lon="121"><ele>8</ele></trkpt>
            <trkpt lat="x" lon="121"><time>2027-01-15T08:00:00Z</time></trkpt>
            <trkpt lat="31" lon="121"><time>2027-01-15T08:00:01Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent().byteInputStream()
        val read = readGpx(file)
        assertEquals(1, read.size)
        assertNull(read.single().altitude)
    }

    @Test fun keepsOldFixesButNotFutureOrBrokenOnes() {
        assertTrue(importable(TrackPoint(now - 365 * 86_400_000L, 31.0, 121.0, 5.0), now))
        assertFalse(importable(TrackPoint(now + 60_000, 31.0, 121.0, 5.0), now))
        assertFalse(importable(TrackPoint(now, 91.0, 121.0, 5.0), now))
    }
}
