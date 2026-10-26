package cc.hyoban.moments.footprints

import java.io.StringWriter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TrackReplayDiagnosticsTest {
    private fun point(seconds: Int, metres: Double = 0.0, accuracy: Double = 10.0) = TrackPoint(
        1_800_000_000_000L + seconds * 1000L, 32.123456 + metres / 111_195, 118.654321,
        accuracy, altitude = 1234.5678, segment = "private-segment-id", speed = 1.3, speedAccuracy = 1.4,
    )
    private fun export(points: List<TrackPoint>): Pair<String, List<JsonObject>> {
        val writer = StringWriter()
        writeTrackReplayDiagnostics(writer, points.asSequence(), "2027-01-15", exportedAt = 1_900_000_000_000L)
        val text = writer.toString()
        return text to text.lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
    }

    @Test fun jumpReportIdentifiesTheCandidateConfirmationAndActualAllowance() {
        val points = listOf(point(0), point(15, 18.0), point(30, 36.0), point(46, 413.0, 27.7), point(60, 54.0), point(75, 72.0))
        val (_, rows) = export(points)
        val suspect = rows.single { it["reason"]?.jsonPrimitive?.content == "suspect_candidate" }
        assertEquals(points[3].time, suspect.getValue("fix_time_ms").jsonPrimitive.long)
        assertEquals(377.0, suspect.getValue("distance_m").jsonPrimitive.double, 0.1)
        assertTrue(suspect.getValue("distance_m").jsonPrimitive.double > suspect.getValue("allowed_distance_m").jsonPrimitive.double)
        val rejected = rows.single { it["reason"]?.jsonPrimitive?.content == "spike_rejected" }
        assertEquals(points[4].time, rejected.getValue("confirmation_fix_ms").jsonPrimitive.long)
        assertEquals(TrackRefiner.VERSION, rows.first().getValue("filter_version").jsonPrimitive.content)
        assertTrue(rows.all { it["mode"]?.jsonPrimitive?.content == "replay" })
    }

    @Test fun qualityGapsWindowExpiryAndUnconfirmedTailAreExplainedWithoutCoordinates() {
        val points = (0..8).map { point(it * 15, if (it % 2 == 0) 0.0 else 3.0, accuracy = 40.0) } +
            listOf(point(135, accuracy = 120.0), point(150), point(165, 12.0))
        val (text, rows) = export(points)
        assertTrue(rows.any { it["reason"]?.jsonPrimitive?.content == "poor_accuracy" })
        assertTrue(rows.any { it["cause"]?.jsonPrimitive?.content == "window_expired" })
        assertTrue(rows.any { it["reason"]?.jsonPrimitive?.content == "unconfirmed_tail" })
        for (privateValue in listOf("32.123456", "118.654321", "1234.5678", "private-segment-id")) assertFalse(text.contains(privateValue))
        val forbidden = setOf("lat", "lon", "latitude", "longitude", "altitude", "coordinates", "segment")
        assertTrue(rows.all { it.keys.intersect(forbidden).isEmpty() })
        assertTrue(rows.any { it["event"]?.jsonPrimitive?.content == "track_link_break" })
    }

    @Test fun replayTotalsMatchTheMapAndObserverLeavesTheTrackUnchanged() {
        val points = listOf(point(0), point(15, 18.0), point(30, 36.0), point(100, 200.0), point(115, 218.0), point(130, 236.0))
        val route = refinedTrack(points.asSequence()).toList()
        val decisions = mutableListOf<TrackDecision>()
        assertEquals(route, replayTrack(points.asSequence(), decisions::add).toList())
        val (_, rows) = export(points)
        val summary = rows.last()
        assertEquals(points.size, summary.getValue("raw_samples").jsonPrimitive.int)
        assertEquals(route.size, summary.getValue("display_points").jsonPrimitive.int)
        assertEquals(trackSegments(route).size, summary.getValue("segments").jsonPrimitive.int)
        assertEquals(trackStats(route).metres, summary.getValue("connected_metres").jsonPrimitive.double, 0.001)
        assertEquals(trackStats(route).recordedMillis, summary.getValue("connected_ms").jsonPrimitive.long)
        assertTrue(rows.any { it["cause"]?.jsonPrimitive?.content == "sample_gap" })
    }

    @Test fun emptyReplayStillDescribesItsScopeAndZeroTotals() {
        val (_, rows) = export(emptyList())
        assertEquals(listOf("track_replay_start", "track_replay_summary"), rows.map { it.getValue("event").jsonPrimitive.content })
        assertEquals("latest_recorded_day", rows.first().getValue("scope").jsonPrimitive.content)
        assertEquals(0, rows.last().getValue("raw_samples").jsonPrimitive.int)
        assertEquals(0, rows.last().getValue("segments").jsonPrimitive.int)
    }
}
