package cc.hyoban.moments.footprints

internal data class ActivityMotion(val elapsedNanos: Long, val moving: Boolean, val activity: Int)

/** Activity events may resume the service, but must never change the user's recording selection. */
internal object ActivityWakePolicy {
    fun dailyRequested(state: RecordingState) = state.daily && state.session == null && !state.paused

    fun fresh(elapsedNanos: Long, nowNanos: Long) = elapsedNanos >= 0 && nowNanos - elapsedNanos in 0..120_000_000_000L

    fun latest(events: List<ActivityMotion>, nowNanos: Long) =
        events.filter { fresh(it.elapsedNanos, nowNanos) }.maxByOrNull { it.elapsedNanos }

    fun shouldRecover(state: RecordingState, moving: Boolean, elapsedNanos: Long, nowNanos: Long) =
        dailyRequested(state) && moving && fresh(elapsedNanos, nowNanos)
}
