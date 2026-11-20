package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.trips.Way
import cc.hyoban.moments.data.trips.inferWay
import cc.hyoban.moments.data.trips.measureWay
import cc.hyoban.moments.footprints.ActivityChange
import cc.hyoban.moments.footprints.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a way between two stops was gone, from made-up footprints along it. */
class WayInferenceTest {
    private val start = Fix(35.0, 139.0)

    /** [count] points heading east from [start] at [speed] metres a second, one every [every] seconds. */
    private fun track(speed: Double, count: Int = 30, every: Long = 60): List<TrackPoint> = List(count) { i ->
        // About 91 km to a degree of longitude at 35°N.
        val metres = speed * every * i
        TrackPoint(i * every * 1000, start.latitude, start.longitude + metres / 91_300.0, accuracy = 10.0)
    }

    private fun end(points: List<TrackPoint>) = points.last().let { Fix(it.latitude, it.longitude) }

    @Test
    fun walkingIsSlow() {
        val walk = track(1.3)
        assertEquals(Way.WALK, inferWay(walk, emptyList(), start, end(walk)))
    }

    @Test
    fun drivingIsHighwaySpeed() {
        val drive = track(25.0)
        assertEquals(Way.DRIVE, inferWay(drive, emptyList(), start, end(drive)))
    }

    @Test
    fun aTrainOutrunsTheHighway() {
        val train = track(70.0)
        assertEquals(Way.TRAIN, inferWay(train, emptyList(), start, end(train)))
    }

    @Test
    fun slowInTownIsCyclingOrDrivingByWhatTheDeviceSaid() {
        val slow = track(5.0)
        assertEquals(Way.BIKE, inferWay(slow, listOf(ActivityChange(0, 1, true)), start, end(slow)))
        assertEquals(Way.DRIVE, inferWay(slow, listOf(ActivityChange(0, 0, true)), start, end(slow)))
    }

    @Test
    fun aLongJumpWithNothingBetweenIsAFlight() {
        val far = Fix(35.0, 145.0)
        val points = listOf(
            TrackPoint(0, 35.0, 139.0, 10.0),
            TrackPoint(60_000, 35.0, 139.001, 10.0),
            TrackPoint(120_000, 35.0, 139.002, 10.0),
            TrackPoint(7_200_000, 35.0, 144.999, 10.0),
        )
        assertEquals(Way.PLANE, inferWay(points, emptyList(), start, far))
    }

    @Test
    fun tooLittleKeptSaysNothing() {
        assertNull(inferWay(track(25.0, count = 3), emptyList(), start, Fix(35.0, 139.5)))
        // Kept only near the start of a long way.
        val stub = track(25.0, count = 6)
        assertNull(inferWay(stub, emptyList(), start, Fix(35.0, 140.0)))
    }

    @Test
    fun aWayGoneIsMeasuredByItsTrack() {
        // Ten minutes still, then 29 minutes at 25 m/s, then still again.
        val still = List(10) { TrackPoint(it * 60_000L, 35.0, 139.0, 10.0) }
        val moving = track(25.0).map { it.copy(time = it.time + 600_000L) }
        val travel = measureWay(still + moving)!!
        assertEquals(29 * 60L, travel.seconds)
        // 29 minutes at 25 m/s, within a few metres of the made-up degree.
        assertEquals(true, kotlin.math.abs(travel.metres - 43_500) < 500)
        assertNull(measureWay(still))
    }
}
