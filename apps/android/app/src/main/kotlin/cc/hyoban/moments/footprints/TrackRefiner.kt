package cc.hyoban.moments.footprints

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A bounded, deterministic view over the original observations. Nothing rewrites the source.
 * Keep up to 30 seconds of candidates and require a following fix before drawing them. This
 * retains small, consistent walking steps (including corners) without accepting every jitter.
 * A poor fix or an unobserved interval breaks the route; no road snapping or invented positions.
 */
fun refinedTrack(samples: Sequence<TrackPoint>): Sequence<TrackPoint> = replayTrack(samples)

/** Only scalar evidence leaves the filter; never coordinates, altitude or a Location/TrackPoint. */
internal data class TrackDecision(val reason: String, val fields: Map<String, Any?>)

internal fun replayTrack(samples: Sequence<TrackPoint>, trace: ((TrackDecision) -> Unit)? = null): Sequence<TrackPoint> = sequence {
    val refiner = TrackRefiner(trace)
    for (sample in samples) yieldAll(refiner.add(sample))
    // The unconfirmed tail intentionally stays in raw storage, not in the displayed route.
    refiner.finish()
}

internal class TrackRefiner(private val trace: ((TrackDecision) -> Unit)? = null) {
    companion object {
        const val VERSION = "window-v1"
        const val WINDOW_MS = 30_000L
        const val MAX_PENDING = 32
        const val MIN_DISPLACEMENT_M = 6.0
        const val UNCERTAINTY_SCALE = 0.5
        const val MIN_PATH_CONSISTENCY = 0.55
    }
    private var anchor: TrackPoint? = null
    private var previous: TrackPoint? = null
    private val pending = mutableListOf<TrackPoint>()
    private var part = 0L

    private fun report(reason: String, point: TrackPoint, vararg fields: Pair<String, Any?>) {
        val observer = trace ?: return
        observer(TrackDecision(reason, mapOf(
            "fix_time_ms" to point.time, "decided_at_fix_ms" to previous?.time,
            "accuracy_m" to point.accuracy, "speed_mps" to point.speed,
            "speed_accuracy_mps" to point.speedAccuracy,
        ) + fields.toMap()))
    }

    private fun discarded(reason: String) {
        if (trace != null) pending.forEach { report("pending_discarded", it, "cause" to reason) }
    }

    fun finish() {
        if (trace != null) pending.forEach { report("unconfirmed_tail", it) }
    }

    private fun displayed(point: TrackPoint) = point.copy(segment = "${point.segment}:refined:$part")

    private fun restart(point: TrackPoint, reason: String): List<TrackPoint> {
        part++
        // A relocated candidate becomes the new anchor, rather than being discarded with the rest.
        if (trace != null) pending.filter { it.time != point.time }.forEach { report("pending_discarded", it, "cause" to reason) }
        pending.clear()
        anchor = point
        report("anchor", point, "cause" to reason)
        return listOf(displayed(point)) // An isolated position until movement is confirmed.
    }

    fun add(point: TrackPoint): List<TrackPoint> {
        val last = previous
        if (last != null && point.time <= last.time) {
            report("out_of_order", point, "previous_fix_ms" to last.time)
            return emptyList()
        }
        previous = point
        val validity = evaluateRawFix(point, point.time)
        if (validity != FixDecision.ACCEPT || point.accuracy > MAX_TRACK_ACCURACY) {
            report(if (validity == FixDecision.ACCEPT) "poor_accuracy" else "invalid_fix", point,
                "validity" to validity.name, "accuracy_limit_m" to MAX_TRACK_ACCURACY)
            anchor = null
            discarded("quality_gap")
            pending.clear()
            part++
            return emptyList()
        }
        val start = anchor
        if (start == null || last == null || last.segment != point.segment || point.time - last.time > TRACK_GAP_MS) {
            val reason = when {
                last == null -> "first_sample"
                last.segment != point.segment -> "recording_segment_change"
                point.time - last.time > TRACK_GAP_MS -> "sample_gap"
                else -> "after_quality_gap"
            }
            if (last != null) report("continuity_reset", point, "cause" to reason,
                "previous_fix_ms" to last.time, "gap_ms" to point.time - last.time)
            return restart(point, reason)
        }

        val candidate = pending.lastOrNull()
        if (candidate != null) {
            val before = pending.getOrNull(pending.lastIndex - 1) ?: start
            val direct = distanceMetres(before, point)
            val via = distanceMetres(before, candidate) + distanceMetres(candidate, point)
            // A sharp return through an uncertain point is a spike, unless measured speed supports it.
            val supported = candidate.speed?.let { speed ->
                val error = candidate.speedAccuracy
                error != null && error in 0.0..2.5 && speed - error > 2
            } == true
            val excursion = candidate.accuracy > 15 && !supported &&
                via > max(60.0, direct * 3) && distanceMetres(before, candidate) > 2 * candidate.accuracy
            if (!plausibleMovement(before, candidate) || excursion) {
                report("suspect_candidate", candidate, "previous_fix_ms" to before.time,
                    "distance_m" to distanceMetres(before, candidate), "allowed_distance_m" to movementAllowanceMetres(before, candidate),
                    "return_distance_m" to direct, "via_distance_m" to via, "excursion" to excursion)
                val returned = direct <= max(10.0, before.accuracy + point.accuracy)
                if (plausibleMovement(before, point) && (excursion || returned)) {
                    report("spike_rejected", candidate, "confirmation_fix_ms" to point.time)
                    pending.removeAt(pending.lastIndex)
                } else if (plausibleMovement(candidate, point)) {
                    // Sustained relocation is a new segment, never a line across the missing path.
                    val output = restart(candidate, "confirmed_relocation")
                    pending += point
                    report("pending", point, "cause" to "after_relocation")
                    return output
                } else {
                    return restart(point, "unresolved_jump")
                }
            }
        }
        pending += point
        val path = listOf(start) + pending
        val distance = distanceMetres(start, point)
        val travelled = path.zipWithNext().sumOf { (a, b) -> distanceMetres(a, b) }
        val threshold = max(MIN_DISPLACEMENT_M, UNCERTAINTY_SCALE * sqrt(start.accuracy * start.accuracy + point.accuracy * point.accuracy))
        val moving = distance >= threshold && distance >= travelled * MIN_PATH_CONSISTENCY
        val plausible = if (pending.size >= 2 && moving) path.zipWithNext().all { (a, b) -> plausibleMovement(a, b) } else null
        report("window", point, "anchor_fix_ms" to start.time, "displacement_m" to distance,
            "threshold_m" to threshold, "path_m" to travelled, "moving" to moving,
            "plausible" to plausible, "pending_count" to pending.size)
        if (pending.size >= 2 && moving && plausible == true) {
            val confirmed = pending.dropLast(1)
            if (trace != null) confirmed.forEach { report("confirmed", it, "confirmation_fix_ms" to point.time) }
            anchor = confirmed.last()
            pending.clear()
            pending += point
            return confirmed.map(::displayed)
        }
        // Bound both time and memory even when providers deliver bursts. The anchor stays put while still.
        while (pending.size > MAX_PENDING || (pending.size > 1 && point.time - pending.first().time > WINDOW_MS)) {
            report("pending_discarded", pending.first(), "cause" to if (pending.size > MAX_PENDING) "window_capacity" else "window_expired")
            pending.removeAt(0)
        }
        return emptyList()
    }
}
