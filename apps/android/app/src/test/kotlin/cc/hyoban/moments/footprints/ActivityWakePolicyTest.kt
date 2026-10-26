package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class ActivityWakePolicyTest {
    private val daily = RecordingState(daily = true)
    private val now = 200_000_000_000L

    @Test fun recoveryRequiresAnUnpausedDailySelection() {
        assertTrue(ActivityWakePolicy.shouldRecover(daily, true, now, now))
        for (state in listOf(RecordingState(), daily.copy(paused = true),
                daily.copy(session = "trip"), RecordingState(session = "trip"))) {
            assertFalse(ActivityWakePolicy.shouldRecover(state, true, now, now))
            assertFalse(ActivityWakePolicy.dailyRequested(state))
        }
    }

    @Test fun aManualPauseBetweenBroadcastAndServiceDeliveryWins() {
        assertTrue(ActivityWakePolicy.shouldRecover(daily, true, now, now))
        assertFalse(ActivityWakePolicy.shouldRecover(daily.copy(paused = true), true, now, now + 1))
    }

    @Test fun stillStaleAndFutureEventsCannotRestartTheRecorder() {
        assertFalse(ActivityWakePolicy.shouldRecover(daily, false, now, now))
        assertFalse(ActivityWakePolicy.shouldRecover(daily, true, now - 120_000_000_001, now))
        assertFalse(ActivityWakePolicy.shouldRecover(daily, true, now + 1, now))
        assertFalse(ActivityWakePolicy.shouldRecover(daily, true, -1, 5_000))
    }

    @Test fun aDelayedMovingThenStillBatchDoesNotColdStartGps() {
        val motion = ActivityWakePolicy.latest(listOf(ActivityMotion(now, false, 3),
            ActivityMotion(now - 10_000_000_000, true, 7)), now)!!
        assertFalse(ActivityWakePolicy.shouldRecover(daily, motion.moving, motion.elapsedNanos, now))
        assertNull(ActivityWakePolicy.latest(listOf(ActivityMotion(0, true, 7)), now))
    }
}
