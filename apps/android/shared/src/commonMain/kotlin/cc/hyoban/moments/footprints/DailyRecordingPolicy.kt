package cc.hyoban.moments.footprints

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Monotonic-time policy for daily recording. A manual trip never uses this policy. */
class DailyRecordingPolicy(now: Long) {
    enum class Phase { ACTIVE, REST, PROBE }
    enum class RestReason { STILL, NO_FIX }
    var phase = Phase.ACTIVE
        private set
    var restReason: RestReason? = null
        private set
    private var anchor: TrackPoint? = null
    private var lastMovement = now
    val lastMovementAt get() = lastMovement
    /** The unmoving wait that confirmed the latest STILL rest, for diagnostics. */
    var stopTimeout: Long? = null
        private set
    private var lastFix = now
    private var retryAt = now
    private var misses = 0
    private var stationarySamples = 0
    private var lastSample = Long.MIN_VALUE
    private var probeStarted = now
    /** A settled stay's first displaced fix, waiting for the next one to agree before it counts. */
    private var candidate: Long? = null
    /** Probes in a row that found the stay unmoved, lengthening the wait before the next. */
    private var stillProbes = 0
    /** Activity recognition's latest word: moving until it reports entering STILL. */
    var activityMoving = false
        private set
    /** No fix has moved since the latest rest: its probes and unconfirmed wakes keep the rest's look. */
    var settled = false
        private set

    // Missing fixes are not evidence of being stationary. Only fix() can confirm a stop.
    val nextCheck: Long
        get() = if (phase == Phase.REST) retryAt else lastFix + FIX_TIMEOUT

    fun fix(point: TrackPoint, now: Long, moving: Boolean = false, allowRest: Boolean = true) {
        if (phase == Phase.REST) return
        lastFix = now
        misses = 0
        val moved = moving || displacedFix(point, now)
        if (moved) {
            settled = false
            candidate = null
            stillProbes = 0
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
            // Duty-cycled GNSS can hold one position for minutes while walking, so trust the
            // fixes over a moving activity only after a much longer, battery-bounding wait.
            val stillFor = if (activityMoving) ACTIVITY_MOVING_STILL_TIMEOUT else STILL_TIMEOUT
            val observedStop = now - lastMovement >= stillFor && stationarySamples >= 3
            // A probe must observe the location over time, not go back to sleep on its first fix.
            val confirmedProbe = phase == Phase.PROBE && stationarySamples >= 3 && now - probeStarted >= PROBE_OBSERVATION
            if (allowRest && candidate == null && (observedStop || confirmedProbe)) {
                stopTimeout = if (observedStop) stillFor else PROBE_OBSERVATION
                rest(now, RestReason.STILL)
            }
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

    /** A probe's first fixes after a cold start can sit tens of metres off, so while a stay holds
     *  a move needs a fix that used satellites and the next fix to agree, before it ends the stay. */
    private fun displacedFix(point: TrackPoint, now: Long): Boolean {
        if (!movedFromAnchor(point)) { candidate = null; return false }
        if (!settled) return true
        if (point.extras.satellitesUsed == 0) return false
        val first = candidate
        if (first != null && now - first >= CONFIRM_SPACING) return true
        if (first == null) candidate = now
        return false
    }

    private fun movedFromAnchor(point: TrackPoint): Boolean {
        val previous = anchor ?: return true
        // Compare with an anchor so slow walking accumulates instead of being discarded per fix.
        // A stay's fixes wander indoors further than they say, so leaving it takes a wider margin.
        return distanceMetres(previous, point) > max(
            if (settled) SETTLED_MOVE else 25.0,
            1.5 * sqrt(previous.accuracy * previous.accuracy + point.accuracy * point.accuracy),
        )
    }

    /** Returns true when GPS should search again instead of resting, so the caller restarts the request. */
    fun tick(now: Long): Boolean {
        if (now < nextCheck) return false
        // A garage or tunnel can hide the sky while activity still says moving: keep searching,
        // within the same battery bound as stuck fixes, instead of waiting for a wake-up.
        if (phase == Phase.ACTIVE && activityMoving && now - lastMovement < ACTIVITY_MOVING_STILL_TIMEOUT) {
            lastFix = now
            return true
        }
        when (phase) {
            Phase.REST -> {
                phase = Phase.PROBE
                restReason = null
                lastFix = now
                probeStarted = now
                candidate = null
                stationarySamples = 0
                lastSample = Long.MIN_VALUE
            }
            Phase.ACTIVE, Phase.PROBE -> rest(now, RestReason.NO_FIX)
        }
        return false
    }

    fun restartAcquisition(now: Long) {
        phase = Phase.ACTIVE
        restReason = null
        lastFix = now
        stationarySamples = 0
        lastSample = Long.MIN_VALUE
    }

    /** Activity transitions: movement wakes the recorder at once, STILL only lifts the longer wait. */
    fun activity(moving: Boolean, now: Long) {
        activityMoving = moving
        if (moving) motion(now)
    }

    fun motion(now: Long) {
        phase = Phase.ACTIVE
        restReason = null
        lastMovement = now
        lastFix = now
        stationarySamples = 0
        lastSample = Long.MIN_VALUE
        misses = 0
        candidate = null
    }

    private fun rest(now: Long, reason: RestReason) {
        // A probe that finds the stay unmoved waits longer before the next: the motion sensor,
        // passive positions and activity transitions still wake it as soon as it moves.
        if (reason == RestReason.STILL) stillProbes = when {
            phase == Phase.PROBE -> min(stillProbes + 1, 3)
            settled -> stillProbes
            else -> 0
        }
        candidate = null
        phase = Phase.REST
        restReason = reason
        settled = true
        // A rest the long wait forced must not keep requesting walking-rate fixes on the next probe.
        activityMoving = false
        misses = if (reason == RestReason.NO_FIX) min(misses + 1, 4) else 0
        val steps = if (reason == RestReason.NO_FIX) max(0, misses - 1) else stillProbes
        retryAt = now + min(MAX_RETRY, REST_INTERVAL * (1L shl steps))
    }

    companion object {
        const val FIX_TIMEOUT = 120_000L
        const val STILL_TIMEOUT = 180_000L
        const val ACTIVITY_MOVING_STILL_TIMEOUT = 600_000L
        const val REST_INTERVAL = 120_000L
        const val MAX_RETRY = 600_000L
        private const val SAMPLE_SPACING = 15_000L
        private const val PROBE_OBSERVATION = 30_000L
        private const val CONFIRM_SPACING = 4_000L
        const val SETTLED_MOVE = 50.0
    }
}
