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

    @Test fun readsFootprintCsvByItsHeader() {
        val file = """
            \uFEFFdataTime,locType,longitude,latitude,heading,accuracy,speed,distance,isBackForeground,stepType,altitude
            1800000000,1,121.1,31.2,0.0,12.5,3.5,20.0,1,0,8.0
            1800000002,1,x,31.2,0.0,12.5,3.5,20.0,1,0,8.0
            1800000001,0,121.2,31.3,0.0,0.0,-1.0,20.0,0,0,-25.0
        """.trimIndent().replace("\\uFEFF", "\uFEFF").replace("\n", "\r\n").byteInputStream()
        val read = readFootprintCsv(file)
        assertEquals(listOf(now, now + 1_000), read.map { it.time })
        assertEquals(31.2, read[0].latitude, 0.0)
        assertEquals(121.1, read[0].longitude, 0.0)
        assertEquals(12.5, read[0].accuracy, 0.0)
        assertEquals(3.5, read[0].speed!!, 0.0)
        assertEquals(8.0, read[0].altitude!!, 0.0)
        assertEquals(10.0, read[1].accuracy, 0.0)
        assertNull(read[1].speed)
        assertEquals(read[0].segment, read[1].segment)
    }

    @Test fun readsOnlyTheColumnsItNeeds() {
        val point = readFootprintCsv("latitude,dataTime,longitude\n31.2,1800000000,121.1\n".byteInputStream()).single()
        assertEquals(TrackPoint(now, 31.2, 121.1, 10.0, segment = point.segment), point)
        assertTrue(readFootprintCsv("time,lat,lon\n1,2,3\n".byteInputStream()).isEmpty())
    }

    /** A real export, read where it lies and never committed: `FOOTPRINT_CSV=<file>`. */
    @Test fun readsARealFootprintExport() {
        val path = System.getenv("FOOTPRINT_CSV") ?: return
        val file = java.io.File(path)
        val rows = file.useLines { it.count() } - 1
        val read = file.inputStream().use(::readFootprintCsv)
        assertEquals(rows, read.size)
        assertTrue(read.all { importable(it, System.currentTimeMillis()) })
        println("footprint csv: ${read.size} points, ${read.count { it.speed != null }} with speed")
    }
}
