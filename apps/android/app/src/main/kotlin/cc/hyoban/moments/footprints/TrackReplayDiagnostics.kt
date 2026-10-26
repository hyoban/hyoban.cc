package cc.hyoban.moments.footprints

import java.io.Writer
import kotlinx.serialization.json.*

/** Explicit export only. Streaming and coordinate-free; never appended to the rotating live log. */
internal fun writeTrackReplayDiagnostics(
    writer: Writer,
    samples: Sequence<TrackPoint>,
    recordedDay: String?,
    exportedAt: Long = System.currentTimeMillis(),
) {
    fun record(event: String, fields: Map<String, Any?>) {
        val row = buildJsonObject {
            put("schema", 1)
            put("time_ms", exportedAt)
            put("event", event)
            put("mode", "replay") // time_ms is export time, never the original live decision time.
            put("filter_version", TrackRefiner.VERSION)
            for ((key, value) in fields) when (value) {
                is Boolean -> put(key, value)
                is Number -> if (value.toDouble().isFinite()) put(key, value)
                is String -> put(key, value)
                null -> put(key, JsonNull)
            }
        }
        writer.write(row.toString())
        writer.write("\n")
    }
    record("track_replay_start", mapOf(
        "recorded_day" to recordedDay, "scope" to "latest_recorded_day",
        "accuracy_limit_m" to MAX_TRACK_ACCURACY, "gap_limit_ms" to TRACK_GAP_MS,
        "window_ms" to TrackRefiner.WINDOW_MS, "max_pending" to TrackRefiner.MAX_PENDING,
        "min_displacement_m" to TrackRefiner.MIN_DISPLACEMENT_M,
        "uncertainty_scale" to TrackRefiner.UNCERTAINTY_SCALE,
        "min_path_consistency" to TrackRefiner.MIN_PATH_CONSISTENCY,
    ))
    var rawCount = 0L
    var displayCount = 0L
    var segments = 0L
    var metres = 0.0
    var connectedMillis = 0L
    var firstTime: Long? = null
    var lastTime: Long? = null
    var previous: TrackPoint? = null
    val reasons = mutableMapOf<String, Long>()
    val observed = samples.onEach { point ->
        rawCount++
        if (firstTime == null) firstTime = point.time
        lastTime = point.time
    }
    val trace: (TrackDecision) -> Unit = { decision ->
        reasons[decision.reason] = (reasons[decision.reason] ?: 0) + 1
        record("track_decision", mapOf("reason" to decision.reason) + decision.fields)
    }
    for (point in replayTrack(observed, trace)) {
        displayCount++
        val last = previous
        if (last == null) segments++ else {
            val reason = trackBreakReason(last, point)
            if (reason == null) {
                metres += distanceMetres(last, point)
                connectedMillis += point.time - last.time
            } else {
                segments++
                record("track_link_break", mapOf(
                    "reason" to reason, "from_fix_ms" to last.time, "fix_time_ms" to point.time,
                    "gap_ms" to point.time - last.time, "distance_m" to distanceMetres(last, point),
                    "allowed_distance_m" to movementAllowanceMetres(last, point),
                    "from_accuracy_m" to last.accuracy, "accuracy_m" to point.accuracy,
                ))
            }
        }
        previous = point
    }
    record("track_replay_summary", mapOf(
        "recorded_day" to recordedDay, "first_fix_ms" to firstTime, "last_fix_ms" to lastTime,
        "raw_samples" to rawCount, "display_points" to displayCount, "segments" to segments,
        "connected_metres" to metres, "connected_ms" to connectedMillis,
    ) + reasons.mapKeys { (reason, _) -> "events_$reason" })
}
