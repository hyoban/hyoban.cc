package cc.hyoban.moments.footprints

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Monotonic-time policy for daily recording. A manual trip never uses this policy. */
internal class DailyRecordingPolicy(now: Long) {
    enum class Phase { ACTIVE, REST, PROBE }
    enum class RestReason { STILL, NO_FIX }
    var phase = Phase.ACTIVE
        private set
    var restReason: RestReason? = null
        private set
    private var anchor: TrackPoint? = null
    private var lastMovement = now
    private var lastFix = now
    private var retryAt = now
    private var misses = 0
    private var stationarySamples = 0
    private var lastSample = Long.MIN_VALUE
    private var probeStarted = now

    // Missing fixes are not evidence of being stationary. Only fix() can confirm a stop.
    val nextCheck: Long
        get() = if (phase == Phase.REST) retryAt else lastFix + FIX_TIMEOUT

    fun fix(point: TrackPoint, now: Long, moving: Boolean = false, allowRest: Boolean = true) {
        if (phase == Phase.REST) return
        lastFix = now
        misses = 0
        val moved = moving || movedFromAnchor(point)
        if (moved) {
            anchor = point
            lastMovement = now
            stationarySamples = 0
            lastSample = now
            phase = Phase.ACTIVE
        } else {
            // Do not count duplicate/burst callbacks as independent stationary observations.
            if (lastSample == Long.MIN_VALUE || now - lastSample >= SAMPLE_SPACING) {
                stationarySamples++
                lastSample = now
            }
            val observedStop = now - lastMovement >= STILL_TIMEOUT && stationarySamples >= 3
            // A probe must observe the location over time, not go back to sleep on its first fix.
            val confirmedProbe = phase == Phase.PROBE && stationarySamples >= 3 && now - probeStarted >= PROBE_OBSERVATION
            if (allowRest && (observedStop || confirmedProbe)) rest(now, RestReason.STILL)
        }
    }

    /** Passive locations never start a fix themselves. Only a fresh displacement wakes GPS. */
    fun passiveFix(point: TrackPoint, now: Long): Boolean {
        if (phase != Phase.REST || point.accuracy > 50 || !movedFromAnchor(point)) return false
        anchor = point
        motion(now)
        return true
    }

    fun fallbackFix(point: TrackPoint, now: Long): Boolean {
        val previous = anchor ?: return false
        if (phase != Phase.REST || point.accuracy !in 0.0..200.0) return false
        val threshold = max(50.0, 2 * sqrt(previous.accuracy * previous.accuracy + point.accuracy * point.accuracy))
        if (distanceMetres(previous, point) <= threshold) return false
        motion(now)
        return true
    }

    private fun movedFromAnchor(point: TrackPoint): Boolean {
        val previous = anchor ?: return true
        // Compare with an anchor so slow walking accumulates instead of being discarded per fix.
        return distanceMetres(previous, point) > max(
            25.0, 1.5 * sqrt(previous.accuracy * previous.accuracy + point.accuracy * point.accuracy),
        )
    }

    fun tick(now: Long) {
        if (now < nextCheck) return
        when (phase) {
            Phase.REST -> {
                phase = Phase.PROBE
                restReason = null
                lastFix = now
                probeStarted = now
                stationarySamples = 0
                lastSample = Long.MIN_VALUE
            }
            Phase.ACTIVE, Phase.PROBE -> rest(now, RestReason.NO_FIX)
        }
    }

    fun restartAcquisition(now: Long) {
        phase = Phase.ACTIVE
        restReason = null
        lastFix = now
        stationarySamples = 0
        lastSample = Long.MIN_VALUE
    }

    fun motion(now: Long) {
        phase = Phase.ACTIVE
        restReason = null
        lastMovement = now
        lastFix = now
        stationarySamples = 0
        lastSample = Long.MIN_VALUE
        misses = 0
    }

    private fun rest(now: Long, reason: RestReason) {
        phase = Phase.REST
        restReason = reason
        misses = if (reason == RestReason.NO_FIX) min(misses + 1, 4) else 0
        retryAt = now + min(MAX_RETRY, REST_INTERVAL * (1L shl max(0, misses - 1)))
    }

    companion object {
        const val FIX_TIMEOUT = 120_000L
        const val STILL_TIMEOUT = 180_000L
        const val REST_INTERVAL = 120_000L
        const val MAX_RETRY = 600_000L
        private const val SAMPLE_SPACING = 15_000L
        private const val PROBE_OBSERVATION = 30_000L
    }
}
