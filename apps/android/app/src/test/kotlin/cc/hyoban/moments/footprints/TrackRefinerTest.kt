package cc.hyoban.moments.footprints

import java.io.File
import java.io.StringWriter
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TrackRefinerTest {
    private fun p(seconds: Int, x: Double = 0.0, y: Double = 0.0, accuracy: Double = 40.0) =
        TrackPoint(1_800_000_000_000L + seconds * 1000L, y / 111_195, x / 111_195,
            accuracy, segment = "walk", speed = 1.3, speedAccuracy = 1.4)
    private fun refine(points: List<TrackPoint>) = refinedTrack(points.asSequence()).toList()

    @Test fun preservesSmallWalkingStepsAndCornerRatherThanCuttingAcrossIt() {
        val points = listOf(p(0), p(15, 18.0), p(30, 36.0), p(45, 36.0, 18.0),
            p(60, 36.0, 36.0), p(75, 36.0, 54.0))
        val result = refine(points)
        assertEquals(points.dropLast(1).map { it.time }, result.map { it.time })
        assertEquals(1, trackSegments(result).size)
        assertEquals(72.0, trackStats(result).metres, 0.1)
    }

    @Test fun stationaryCloudDoesNotBecomeDistanceOrARoute() {
        val points = (0..80).map { p(it * 15, if (it % 2 == 0) 3.0 else -3.0, if (it % 3 == 0) 4.0 else -4.0) }
        val result = refine(points)
        assertEquals(1, result.size)
        assertEquals(0.0, trackStats(result).metres, 0.0)
    }

    @Test fun holdsAnUnconfirmedJumpAndRejectsItWhenTheNextFixReturns() {
        val points = listOf(p(0, accuracy = 10.0), p(15, 18.0, accuracy = 10.0),
            p(30, 36.0, accuracy = 10.0), p(46, 413.0, accuracy = 27.7),
            p(60, 54.0, accuracy = 10.0), p(75, 72.0, accuracy = 10.0))
        assertFalse(refine(points.take(4)).any { it.time == points[3].time })
        val result = refine(points)
        assertFalse(result.any { it.time == points[3].time })
        assertTrue(trackStats(result).metres < 80)
    }

    @Test fun sustainedRelocationStartsANewSegmentInsteadOfConnectingAcrossBuildings() {
        val points = listOf(p(0, accuracy = 10.0), p(15, 18.0, accuracy = 10.0),
            p(30, 395.0, accuracy = 27.7), p(45, 413.0, accuracy = 10.0), p(60, 431.0, accuracy = 10.0))
        val result = refine(points)
        assertTrue(trackSegments(result).size >= 2)
        assertTrue(trackStats(result).metres < 80)
    }

    @Test fun poorFixAndMissingTimeBothBreakContinuity() {
        val points = listOf(p(0, accuracy = 10.0), p(15, 18.0, accuracy = 10.0), p(30, 36.0, accuracy = 10.0),
            p(45, 54.0, accuracy = 150.0), p(60, 72.0, accuracy = 10.0), p(75, 90.0, accuracy = 10.0),
            p(90, 108.0, accuracy = 10.0), p(180, 200.0, accuracy = 10.0), p(195, 218.0, accuracy = 10.0),
            p(210, 236.0, accuracy = 10.0))
        val result = refine(points)
        assertEquals(3, trackSegments(result).size)
        assertFalse(result.any { it.accuracy > 50 })
        assertEquals(54.0, trackStats(result).metres, 0.1)
    }

    @Test fun measuredVehicleSpeedDoesNotGetTreatedAsWalking() {
        val points = (0..5).map { p(it * 15, it * 450.0, accuracy = 8.0).copy(speed = 30.0, speedAccuracy = 0.5) }
        assertEquals(1, trackSegments(refine(points)).size)
        assertEquals(1800.0, trackStats(refine(points)).metres, 0.1)
    }

    @Test fun restAndRecordingChangesDoNotBridgePendingCandidates() {
        val first = listOf(p(0), p(15, 18.0), p(30, 36.0))
        val second = listOf(p(45, 100.0), p(60, 118.0), p(75, 136.0)).map { it.copy(segment = "resume") }
        assertEquals(2, trackSegments(refine(first + second)).size)
        assertFalse(refine(first + second).any { it.time == first.last().time })
    }

    @Test fun rawExportPreservesUncertainSamplesAndMeasurementMetadataWithoutJoiningThem() {
        val point = p(0, accuracy = 150.0)
        assertEquals(FixDecision.ACCEPT, evaluateRawFix(point, point.time))
        val writer = StringWriter()
        writeGpx(writer, sequenceOf(point, p(15)), raw = true)
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(writer.toString().byteInputStream())
        assertEquals(2, xml.getElementsByTagName("trkseg").length)
        assertEquals("150.0", xml.getElementsByTagNameNS("https://hyoban.cc/ns/footprints/1", "accuracy").item(0).textContent)
        assertEquals("1.3", xml.getElementsByTagNameNS("https://hyoban.cc/ns/footprints/1", "speed").item(0).textContent)
    }

    @Test fun replayPrivateRecordingWhenProvided() {
        val directory = System.getenv("FOOTPRINT_REPLAY")?.let(::File)
        assumeTrue("Optional private recording stays outside the repository", directory != null)
        val fixes = File(directory, "diagnostics.jsonl").readLines().map { JsonParser.parseString(it).asJsonObject }
            .filter { it["event"].asString == "fix" }.associateBy { it["fix_time_ms"].asLong }
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(directory, "footprints-2026-10-26.gpx"))
        val segments = xml.getElementsByTagName("trkseg")
        val points = buildList {
            for (index in 0 until segments.length) {
                val children = (segments.item(index) as org.w3c.dom.Element).getElementsByTagName("trkpt")
                for (i in 0 until children.length) {
                    val node = children.item(i) as org.w3c.dom.Element
                    val time = Instant.parse(node.getElementsByTagName("time").item(0).textContent).toEpochMilli()
                    if (time < Instant.parse("2026-10-26T11:30:00Z").toEpochMilli()) continue
                    val fix = fixes[time]
                    add(TrackPoint(time, node.getAttribute("lat").toDouble(), node.getAttribute("lon").toDouble(),
                        fix?.get("accuracy_m")?.asDouble ?: 100.0, segment = index.toString(),
                        speed = fix?.get("speed_mps")?.asDouble, speedAccuracy = fix?.get("speed_accuracy_mps")?.asDouble))
                }
            }
        }
        assertEquals(34, points.size)
        val result = refine(points)
        assertTrue(result.zipWithNext().none { (a,b) -> connected(a,b) && distanceMetres(a,b) > 300 })
        val legacy = refine(points.map { it.copy(speed = null, speedAccuracy = null) })
        assertTrue(legacy.zipWithNext().none { (a,b) -> connected(a,b) && distanceMetres(a,b) > 300 })
        File(directory, "replay-refined.gpx").bufferedWriter().use { writeGpx(it, result.asSequence()) }
        println("Private replay: ${points.size} retained originals -> ${result.size} displayed positions, ${trackSegments(result).size} segments, ${trackStats(result).metres.toInt()} connected metres. Missing raw fixes cannot be replayed.")
        println("Legacy database without speed: ${legacy.size} positions, ${trackSegments(legacy).size} segments, ${trackStats(legacy).metres.toInt()} connected metres.")
    }
}
