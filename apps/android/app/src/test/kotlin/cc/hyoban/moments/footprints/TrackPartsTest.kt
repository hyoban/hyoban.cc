package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class TrackPartsTest {
    /** A straight eastward track, a fix every five seconds at [kmh], from [metres] along it. */
    private fun leg(start: Long, seconds: Int, kmh: Double, metres: Double = 0.0): List<TrackPoint> =
        (0..seconds / 5).map { i ->
            val along = metres + kmh / 3.6 * i * 5
            TrackPoint(start + i * 5_000L, 31.0, 121.0 + along / 95_000, 5.0, speed = kmh / 3.6, speedAccuracy = 0.5)
        }

    private fun still(start: Long, seconds: Int, metres: Double) = leg(start, seconds, 0.0, metres)

    @Test fun walkThenDriveThenStay() {
        val walk = leg(0, 240, 5.0)
        val drive = leg(245_000, 1_200, 60.0, walk.last().longitudeMetres())
        val stay = still(1_450_000, 600, drive.last().longitudeMetres())
        val parts = trackParts(walk + drive + stay)
        assertEquals(listOf(TravelMode.WALK, TravelMode.DRIVE), parts.filterIsInstance<TrackMove>().map { it.mode })
        val last = parts.last()
        assertTrue(last is TrackStay)
        assertTrue(last.end - last.start >= STAY_MIN_MS)
        val driving = parts.filterIsInstance<TrackMove>().last()
        assertEquals(60.0, driving.averageSpeed!! * 3.6, 1.0)
        assertEquals(60.0, driving.topSpeed!! * 3.6, 0.1)
    }

    @Test fun aRedLightDoesNotSplitTheDrive() {
        val first = leg(0, 600, 50.0)
        val light = still(605_000, 90, first.last().longitudeMetres())
        val second = leg(700_000, 600, 50.0, first.last().longitudeMetres())
        val moves = trackParts(first + light + second).filterIsInstance<TrackMove>()
        assertEquals(listOf(TravelMode.DRIVE), moves.map { it.mode })
    }

    @Test fun aSteadyRideIsCycling() {
        val moves = trackParts(leg(0, 900, 16.0)).filterIsInstance<TrackMove>()
        assertEquals(listOf(TravelMode.CYCLE), moves.map { it.mode })
    }

    @Test fun aGapInsideAStayKeepsOneStay() {
        val before = still(0, 400, 0.0)
        val after = still(3_600_000, 400, 30.0)
        val parts = trackParts(before + after)
        assertEquals(1, parts.size)
        assertEquals(3_600_000L + 400_000, parts.single().end)
    }

    @Test fun stayMarkersTakeTheirBreaksDots() {
        val drive = leg(0, 600, 50.0)
        val stay = still(605_000, 600, drive.last().longitudeMetres())
        val points = drive + stay
        val drawing = footprintDrawing(points, trackParts(points))
        assertEquals(1, drawing.stays.size)
        assertTrue(drawing.ends.none { end -> distanceMetres(end, drawing.stays.single()) <= 2 * STAY_RADIUS_M })
        assertEquals(listOf(TravelMode.DRIVE), drawing.lines.map { it.first }.distinct())
    }

    private fun TrackPoint.longitudeMetres() = (longitude - 121.0) * 95_000

    @Test fun anActivityTellsADriveFromARideAtTheSameSpeed() {
        // 20 km/h reads as a ride by speed alone; in a car it is a slow drive.
        val slow = leg(0, 900, 20.0)
        assertEquals(listOf(TravelMode.CYCLE), trackParts(slow).filterIsInstance<TrackMove>().map { it.mode })
        val inCar = listOf(ActivityChange(-60_000, 0, entering = true))
        assertEquals(listOf(TravelMode.DRIVE), trackParts(slow, inCar).filterIsInstance<TrackMove>().map { it.mode })
    }

    @Test fun speedOverrulesAnImpossibleActivity() {
        val fast = leg(0, 900, 70.0)
        val onFoot = listOf(ActivityChange(-60_000, 7, entering = true))
        assertEquals(listOf(TravelMode.DRIVE), trackParts(fast, onFoot).filterIsInstance<TrackMove>().map { it.mode })
    }
}
