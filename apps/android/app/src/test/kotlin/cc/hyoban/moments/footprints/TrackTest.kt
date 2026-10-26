package cc.hyoban.moments.footprints

import org.junit.Test
import org.junit.Assert.*
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

class TrackTest {
    private val now = 1_800_000_000_000L
    private fun point(time: Long = now, lat: Double = 31.0, lon: Double = 121.0, accuracy: Double = 5.0, segment: String = "a") =
        TrackPoint(time, lat, lon, accuracy, segment = segment)

    @Test fun rejectsMalformedAndExpiredObservationsButPreservesPoorAccuracy() {
        assertEquals(FixDecision.INVALID_COORDINATES, evaluateRawFix(point(lat = Double.NaN), now))
        assertEquals(FixDecision.INVALID_COORDINATES, evaluateRawFix(point(lon = 181.0), now))
        assertEquals(FixDecision.INACCURATE, evaluateRawFix(point(accuracy = -1.0), now))
        assertEquals(FixDecision.STALE, evaluateRawFix(point(time = now - 120_001), now))
        assertEquals(FixDecision.FUTURE, evaluateRawFix(point(time = now + 10_001), now))
        assertEquals(FixDecision.INVALID_ALTITUDE, evaluateRawFix(point().copy(altitude = Double.NaN), now))
        assertEquals(FixDecision.ACCEPT, evaluateRawFix(point(accuracy = 250.0), now))
        assertEquals(FixDecision.INACCURATE, evaluatePolicyFix(point(accuracy = 250.0), now))
        assertEquals(FixDecision.ACCEPT, evaluatePolicyFix(point(accuracy = 80.0), now))
    }

    @Test fun connectionsRejectTeleportsButPermitMeasuredFlight() {
        assertFalse(connected(point(time = now - 5_000), point(lat = 35.0)))
        assertTrue(connected(point(time = now - 5_000).copy(speed = 230.0, speedAccuracy = 1.0),
            point(lat = 31.01).copy(speed = 230.0, speedAccuracy = 1.0)))
        assertFalse(connected(point(time = now - TRACK_GAP_MS - 1), point(lat = 35.0)))
        assertFalse(connected(point(), point()))
    }

    @Test fun calculatesDistanceAcrossTheDateLine() {
        val distance = distanceMetres(point(lat = 0.0, lon = 179.999), point(lat = 0.0, lon = -179.999))
        assertEquals(222.39, distance, 0.1)
    }

    @Test fun excludesPausesAndOutagesFromDistanceAndRecordedTime() {
        val points = listOf(
            point(time = now - 700_000),
            point(time = now - 690_000, lat = 31.001),
            point(time = now - 680_000, lat = 40.0, segment = "restart"),
            point(time = now, lat = 50.0, segment = "restart"),
        )
        val stats = trackStats(points)
        assertEquals(111.195, stats.metres, 0.1)
        assertEquals(10_000L, stats.recordedMillis)
        assertEquals(listOf(2, 1, 1), trackSegments(points).map { it.size })
    }

    @Test fun decimationKeepsBreaksAndEndpoints() {
        val points = (0..100).map { point(time = now + it * 1_000, lat = 31 + it * 0.00001, segment = if (it < 50) "a" else "b") }
        val segments = trackSegments(points, budget = 10)
        assertEquals(2, segments.size)
        assertEquals(points[0], segments[0].first())
        assertEquals(points[49], segments[0].last())
        assertEquals(points[50], segments[1].first())
        assertEquals(points[100], segments[1].last())
        assertTrue(segments.sumOf { it.size } < 20)
    }

    @Test fun exportIsValidGpxWithUtcTimesAndSeparateSegments() {
        val points = listOf(point().copy(altitude = 12.5), point(time = now + 1_000), point(time = now + 2_000, segment = "b"))
        val writer = StringWriter()
        writeGpx(writer, points.asSequence())
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(InputSource(StringReader(writer.toString())))
        assertEquals("http://www.topografix.com/GPX/1/1", xml.documentElement.namespaceURI)
        assertEquals(2, xml.getElementsByTagName("trkseg").length)
        assertEquals(3, xml.getElementsByTagName("trkpt").length)
        assertEquals("12.5", xml.getElementsByTagName("ele").item(0).textContent)
        assertTrue(xml.getElementsByTagName("time").item(0).textContent.endsWith("Z"))
    }

    @Test fun emptyTrackCanBeDisplayedAndExported() {
        assertEquals(TrackStats(0.0, 0), trackStats(emptyList()))
        assertTrue(trackSegments(emptyList()).isEmpty())
        val writer = StringWriter()
        writeGpx(writer, emptySequence())
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(writer.toString())))
    }
}
