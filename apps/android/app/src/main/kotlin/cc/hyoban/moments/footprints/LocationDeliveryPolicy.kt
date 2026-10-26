package cc.hyoban.moments.footprints

/** Sampling and delivery clocks are separate: a valid batch contains intentionally older fixes. */
internal object LocationDeliveryPolicy {
    const val DAILY_INTERVAL_MS = 15_000L
    const val BATCH_DELAY_MS = 60_000L
    const val DAILY_MAX_AGE_MS = BATCH_DELAY_MS + 30_000L
    const val BATCH_STALL_MS = 90_000L
    const val FALLBACK_DURATION_MS = 20_000L

    fun batchStalled(now: Long, lastSample: Long) = now - lastSample >= BATCH_STALL_MS

    fun acceptsTime(sampleNanos: Long, nowNanos: Long, segmentStartNanos: Long, previousNanos: Long, maxAgeMs: Long): Boolean =
        sampleNanos >= segmentStartNanos && sampleNanos > previousNanos &&
            nowNanos - sampleNanos in 0..maxAgeMs * 1_000_000
}
