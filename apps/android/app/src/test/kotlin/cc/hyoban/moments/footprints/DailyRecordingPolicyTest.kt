package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class DailyRecordingPolicyTest {
    private fun point(metres: Double = 0.0, accuracy: Double = 5.0) = TrackPoint(0, 31.0 + metres / 111_195, 121.0, accuracy)
    private fun restingPolicy(): DailyRecordingPolicy = DailyRecordingPolicy(0).apply {
        for (time in 0L..180_000L step 15_000L) fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.REST, phase)
    }

    @Test fun stationaryFixesStopGpsAfterThreeMinutes() {
        val p = DailyRecordingPolicy(0)
        for (time in 0L..165_000L step 15_000L) p.fix(point(), time)
        p.tick(180_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        p.fix(point(), 180_000)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(DailyRecordingPolicy.RestReason.STILL, p.restReason)
        assertEquals(300_000L, p.nextCheck)
    }

    @Test fun droppedCallbacksWhileWalkingDoNotCauseFortyFiveSecondShutdowns() {
        val p = DailyRecordingPolicy(0)
        p.fix(point(), 0)
        for ((time, metres) in listOf(60_000L to 50.0, 150_000L to 100.0, 240_000L to 160.0)) {
            p.tick(time - 1)
            assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
            p.fix(point(metres), time)
        }
        p.tick(359_999)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        p.tick(360_000)
        assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, p.restReason)
    }

    @Test fun noFixHasBoundedAcquisitionAndBackoff() {
        val p = DailyRecordingPolicy(0)
        var now = 0L
        for (rest in listOf(120_000L, 240_000L, 480_000L, 600_000L, 600_000L)) {
            p.tick(now + 119_999)
            assertNotEquals(DailyRecordingPolicy.Phase.REST, p.phase)
            now += 120_000
            p.tick(now)
            assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, p.restReason)
            assertEquals(now + rest, p.nextCheck)
            now += rest
            p.tick(now)
            assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        }
    }

    @Test fun probeNeedsMultipleObservationsBeforeReturningToRest() {
        val p = restingPolicy()
        p.tick(300_000)
        p.fix(point(), 301_000)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        p.fix(point(), 316_000)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        p.fix(point(), 331_000)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
    }

    @Test fun callbackBurstCannotConfirmAStopDuringProbe() {
        val p = restingPolicy()
        p.tick(300_000)
        for (t in 300_000L..300_020L) p.fix(point(), t)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        p.fix(point(80.0), 315_000)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        p.fix(point(82.0), 320_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
    }

    @Test fun slowWalkingWithFortyMetreAccuracyRemainsActive() {
        val p = DailyRecordingPolicy(0)
        for (t in 0L..600_000L step 15_000L) {
            p.tick(t)
            p.fix(point(t / 2000.0, accuracy = 40.0), t)
            assertEquals("walking at $t", DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        }
    }

    @Test fun reliableSpeedKeepsMovingWithNoisyCoordinates() {
        val p = DailyRecordingPolicy(0)
        for (t in 0L..600_000L step 15_000L) p.fix(point(accuracy = 60.0), t, moving = true)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
    }

    @Test fun inaccurateStationaryJitterStillAllowsRest() {
        val p = DailyRecordingPolicy(0)
        for (t in 0L..180_000L step 15_000L) p.fix(point(if (t % 30_000 == 0L) 0.0 else 30.0, 40.0), t)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
    }

    @Test fun motionWakesImmediatelyAndGetsFullAcquisitionWindow() {
        val p = restingPolicy()
        p.motion(200_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(320_000L, p.nextCheck)
        p.fix(point(), 201_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
    }

    @Test fun aStaysProbesAndUnconfirmedWakesStaySettled() {
        val p = DailyRecordingPolicy(0)
        assertFalse(p.settled)
        for (time in 0L..180_000L step 15_000L) p.fix(point(), time)
        assertTrue(p.settled)
        p.tick(300_000)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
        for (time in 301_000L..346_000L step 15_000L) p.fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertTrue(p.settled)
        p.motion(400_000)
        p.tick(520_000)
        assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, p.restReason)
        assertTrue(p.settled)
    }

    @Test fun aFixThatMovesEndsTheStay() {
        val p = restingPolicy()
        p.motion(200_000)
        p.fix(point(), 201_000)
        assertTrue(p.settled)
        p.fix(point(100.0), 206_000)
        assertTrue(p.settled)
        p.fix(point(105.0), 211_000)
        assertFalse(p.settled)
    }

    private fun DailyRecordingPolicy.stillProbe(start: Long) {
        tick(start)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, phase)
        for (time in start + 1_000..start + 31_000 step 15_000) fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.REST, phase)
    }

    @Test fun aProbesOneStrayFixDoesNotEndTheStay() {
        val p = restingPolicy()
        p.tick(300_000)
        p.fix(point(), 301_000)
        p.fix(point(60.0, accuracy = 20.0), 306_000)
        p.fix(point(), 311_000)
        assertTrue(p.settled)
        p.fix(point(), 326_000)
        p.fix(point(), 341_000)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertTrue(p.settled)
    }

    @Test fun aFixThatUsedNoSatellitesCannotEndTheStay() {
        val p = restingPolicy()
        p.tick(300_000)
        val unaided = point(80.0).copy(extras = FixExtras(satellitesUsed = 0))
        p.fix(unaided, 301_000)
        p.fix(unaided, 306_000)
        assertTrue(p.settled)
        assertEquals(DailyRecordingPolicy.Phase.PROBE, p.phase)
    }

    @Test fun aStaysFixesWanderingUnderFiftyMetresKeepIt() {
        val p = restingPolicy()
        p.tick(300_000)
        for (time in 301_000L..331_000L step 5_000L) p.fix(point(42.0, accuracy = 4.0), time)
        assertTrue(p.settled)
    }

    @Test fun burstCallbacksCannotConfirmAMoveTogether() {
        val p = restingPolicy()
        p.tick(300_000)
        p.fix(point(80.0), 301_000)
        p.fix(point(80.0), 301_100)
        assertTrue(p.settled)
    }

    @Test fun stillProbesBackOffToTenMinutes() {
        val p = restingPolicy()
        assertEquals(300_000L, p.nextCheck)
        var start = 300_000L
        for (wait in listOf(240_000L, 480_000L, 600_000L, 600_000L)) {
            p.stillProbe(start)
            assertEquals(start + 31_000 + wait, p.nextCheck)
            start = p.nextCheck
        }
    }

    @Test fun anUnconfirmedWakeKeepsTheBackoffAndAMoveResetsIt() {
        val p = restingPolicy()
        p.stillProbe(300_000)
        p.stillProbe(571_000)
        p.motion(700_000)
        for (time in 701_000L..881_000L step 15_000L) p.fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(881_000L + 480_000, p.nextCheck)
        p.motion(1_000_000)
        p.fix(point(200.0), 1_001_000)
        p.fix(point(210.0), 1_006_000)
        assertFalse(p.settled)
        for (time in 1_021_000L..1_201_000L step 15_000L) p.fix(point(210.0), time)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(1_186_000L + 120_000, p.nextCheck)
    }

    @Test fun freshPassiveDisplacementCanWakeWithoutTheMotionSensor() {
        val p = restingPolicy()
        assertFalse(p.passiveFix(point(10.0), 190_000))
        assertFalse(p.passiveFix(point(500.0, 90.0), 195_000))
        assertTrue(p.passiveFix(point(100.0, 20.0), 200_000))
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(320_000L, p.nextCheck)
        assertFalse(p.passiveFix(point(200.0), 210_000))
    }

    @Test fun aLateActiveCallbackCannotWakeARestingPolicy() {
        val p = restingPolicy()
        p.fix(point(1000.0), 181_000)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(300_000L, p.nextCheck)
    }

    @Test fun aFirstFixAfterColdStartDoesNotCountAsAConfirmedStop() {
        val p = DailyRecordingPolicy(0)
        p.tick(90_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        p.fix(point(), 100_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(220_000L, p.nextCheck)
    }
    @Test fun batchTailMovementPreventsPrematureRestFromItsEarlierStationarySamples() {
        val p = DailyRecordingPolicy(0)
        for (time in 0L..150_000L step 15_000L) p.fix(point(), time)
        p.fix(point(), 165_000, allowRest = false)
        p.fix(point(), 180_000, allowRest = false)
        p.fix(point(100.0), 195_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(315_000L, p.nextCheck)
    }

    @Test fun aStationaryBatchUsesSampleTimesRatherThanDeliveryTime() {
        val p = DailyRecordingPolicy(0)
        for (time in 0L..180_000L step 15_000L) p.fix(point(), time, allowRest = time == 180_000L)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(300_000L, p.nextCheck)
    }

    @Test fun lowPowerFallbackNeedsSignificantDisplacementAndReasonableAccuracy() {
        val p = restingPolicy()
        assertFalse(p.fallbackFix(point(100.0, 100.0), 190_000))
        assertFalse(p.fallbackFix(point(1_000.0, 500.0), 195_000))
        assertTrue(p.fallbackFix(point(300.0, 100.0), 200_000))
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertFalse(p.fallbackFix(point(600.0), 210_000))
    }

    @Test fun fallbackWithoutAnAnchorCannotInventMovement() {
        val p = DailyRecordingPolicy(0)
        p.tick(120_000)
        assertFalse(p.fallbackFix(point(100.0), 125_000))
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
    }
    @Test fun abandoningBrokenBatchingGrantsFreshAcquisitionTime() {
        val p = DailyRecordingPolicy(0)
        p.fix(point(), 0)
        p.restartAcquisition(90_000)
        p.tick(120_000)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(210_000L, p.nextCheck)
    }

    @Test fun stuckFixesDoNotStopGpsWhileActivitySaysMoving() {
        val p = DailyRecordingPolicy(0)
        p.activity(true, 0)
        for (time in 0L..585_000L step 15_000L) p.fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        // The long wait still bounds battery use if STILL never arrives.
        p.fix(point(), 600_000)
        assertEquals(DailyRecordingPolicy.RestReason.STILL, p.restReason)
        assertEquals(DailyRecordingPolicy.ACTIVITY_MOVING_STILL_TIMEOUT, p.stopTimeout)
        assertFalse(p.activityMoving)
    }

    @Test fun enteringStillRestoresTheThreeMinuteStop() {
        val p = DailyRecordingPolicy(0)
        p.activity(true, 0)
        for (time in 0L..240_000L step 15_000L) p.fix(point(), time)
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        p.activity(false, 250_000)
        p.fix(point(), 255_000)
        assertEquals(DailyRecordingPolicy.Phase.REST, p.phase)
        assertEquals(DailyRecordingPolicy.STILL_TIMEOUT, p.stopTimeout)
    }

    @Test fun lostFixesKeepSearchingWhileActivitySaysMoving() {
        val p = DailyRecordingPolicy(0)
        p.activity(true, 0)
        p.fix(point(), 0)
        // Inside a garage: no fixes arrive, but GPS searches again instead of resting.
        assertTrue(p.tick(120_000))
        assertEquals(DailyRecordingPolicy.Phase.ACTIVE, p.phase)
        assertEquals(240_000L, p.nextCheck)
        for (time in 240_000L..480_000L step 120_000L) assertTrue(p.tick(time))
        // Bounded by the same wait as stuck fixes when STILL never arrives.
        assertFalse(p.tick(600_000))
        assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, p.restReason)
    }

    @Test fun lostFixesRestWithoutMovingActivity() {
        val p = DailyRecordingPolicy(0)
        p.activity(true, 0)
        p.fix(point(), 0)
        p.activity(false, 60_000)
        assertFalse(p.tick(120_000))
        assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, p.restReason)
    }
}
