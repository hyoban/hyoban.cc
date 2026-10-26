package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class LocationDeliveryPolicyTest {
    private fun nanos(ms: Long) = ms * 1_000_000
    private fun accepts(sample: Long, now: Long = 100_000, start: Long = 1_000, previous: Long = 0) =
        LocationDeliveryPolicy.acceptsTime(nanos(sample), nanos(now), nanos(start), nanos(previous), LocationDeliveryPolicy.DAILY_MAX_AGE_MS)

    @Test fun delayedBatchPointsRemainEligibleWithoutAcceptingStaleHistory() {
        for (time in listOf(40_000L, 55_000L, 70_000L, 85_000L, 100_000L)) assertTrue(accepts(time))
        assertFalse(accepts(9_999))
        assertFalse(accepts(100_001))
    }

    @Test fun aNewSegmentRejectsCachedPointsFromBeforeItStarted() {
        assertFalse(accepts(40_000, start = 50_000))
        assertTrue(accepts(50_000, start = 50_000))
    }

    @Test fun duplicateAndOutOfOrderDeliveriesCannotRewriteRecordedHistory() {
        assertFalse(accepts(70_000, previous = 70_000))
        assertFalse(accepts(55_000, previous = 70_000))
        assertTrue(accepts(85_000, previous = 70_000))
    }
    @Test fun batchingHealthAllowsNormalDeliveryButDetectsASilentProvider() {
        val health = BatchDeliveryHealth()
        health.started(0)
        assertFalse(health.stalled(60_000))
        assertTrue(health.stalled(90_000))
        health.freshDelivery(45_000)
        assertFalse(health.stalled(90_000))
    }

    @Test fun weakButFreshFixesDoNotDisableBatchingOrPreventNoFixRest() {
        val health = BatchDeliveryHealth()
        val daily = DailyRecordingPolicy(0)
        health.started(0)
        // Both deliveries fail accuracy filtering, so neither is passed to daily.fix().
        health.freshDelivery(60_000)
        health.freshDelivery(120_000)
        assertFalse(health.staleOnly(120_000))
        assertFalse(health.stalled(120_000))
        assertTrue(health.canBatch(120_000))
        daily.tick(120_000)
        assertEquals(DailyRecordingPolicy.RestReason.NO_FIX, daily.restReason)
    }

    @Test fun staleCallbacksMustNotOverrideARecentFreshDelivery() {
        val health = BatchDeliveryHealth()
        health.started(0)
        assertTrue(health.staleOnly(60_000))
        health.freshDelivery(60_000)
        assertFalse(health.staleOnly(75_000))
        assertTrue(health.staleOnly(120_000))
    }

    @Test fun failuresBackOffButNeverPermanentlyDisableBatching() {
        val health = BatchDeliveryHealth()
        var now = 0L
        for (delay in listOf(600_000L, 1_200_000L, 2_400_000L, 3_600_000L, 3_600_000L)) {
            health.backOff(now)
            assertEquals(now + delay, health.retryAt)
            assertFalse(health.canBatch(health.retryAt - 1))
            assertTrue(health.canBatch(health.retryAt))
            now = health.retryAt
        }
        health.started(now)
        for (offset in listOf(60_000L, 120_000L, 180_000L)) health.freshDelivery(now + offset)
        now += 180_000
        health.backOff(now)
        assertEquals(now + BatchDeliveryHealth.RETRY_MS, health.retryAt)
    }
}
