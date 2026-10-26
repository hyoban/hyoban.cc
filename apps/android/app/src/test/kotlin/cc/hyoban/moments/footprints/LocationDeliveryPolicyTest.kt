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
        assertFalse(LocationDeliveryPolicy.batchStalled(60_000, 0))
        assertFalse(LocationDeliveryPolicy.batchStalled(90_000, 45_000))
        assertTrue(LocationDeliveryPolicy.batchStalled(90_000, 0))
    }
}
