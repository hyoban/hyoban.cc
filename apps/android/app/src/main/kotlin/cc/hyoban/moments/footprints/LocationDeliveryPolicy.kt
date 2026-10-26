package cc.hyoban.moments.footprints

/** Sampling and delivery clocks are separate: a valid batch contains intentionally older fixes. */
internal object LocationDeliveryPolicy {
    const val DAILY_INTERVAL_MS = 15_000L
    const val BATCH_DELAY_MS = 60_000L
    const val DAILY_MAX_AGE_MS = BATCH_DELAY_MS + 30_000L
    const val BATCH_STALL_MS = 90_000L
    const val FALLBACK_DURATION_MS = 20_000L

    fun acceptsTime(sampleNanos: Long, nowNanos: Long, segmentStartNanos: Long, previousNanos: Long, maxAgeMs: Long): Boolean =
        sampleNanos >= segmentStartNanos && sampleNanos > previousNanos &&
            nowNanos - sampleNanos in 0..maxAgeMs * 1_000_000
}

/** Delivery health is independent of accuracy. A weak fix still proves the provider is responding. */
internal class BatchDeliveryHealth {
    private var started = 0L
    private var lastFreshDelivery = 0L
    private var failures = 0
    var retryAt = 0L
        private set
    val deadline get() = lastFreshDelivery + LocationDeliveryPolicy.BATCH_STALL_MS

    fun canBatch(now: Long) = now >= retryAt

    fun started(now: Long) {
        started = now
        lastFreshDelivery = now
    }

    fun freshDelivery(now: Long) {
        lastFreshDelivery = now
        // Successful delivery over several windows clears an earlier provider failure.
        if (now - started >= 3 * LocationDeliveryPolicy.BATCH_DELAY_MS) failures = 0
    }

    fun staleOnly(now: Long) = now - lastFreshDelivery >= LocationDeliveryPolicy.BATCH_DELAY_MS
    fun stalled(now: Long) = now >= deadline

    fun backOff(now: Long) {
        val delay = (RETRY_MS * (1L shl failures)).coerceAtMost(MAX_RETRY_MS)
        failures = (failures + 1).coerceAtMost(3)
        retryAt = now + delay
    }

    companion object {
        const val RETRY_MS = 10 * 60_000L
        const val MAX_RETRY_MS = 60 * 60_000L
    }
}
