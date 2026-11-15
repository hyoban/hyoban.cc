package cc.hyoban.moments.footprints

// What the footprints' store hands out, apart from the store itself, which is the phone's.

data class RecordingState(val daily: Boolean = false, val session: String? = null, val paused: Boolean = false) {
    val requested get() = daily || session != null
    val recording get() = requested && !paused
}

data class TrackSession(val id: String, val started: Long, val ended: Long?)
data class TrackSelection(val day: String? = null, val session: String? = null, val from: Long? = null, val to: Long? = null)

/** A Play services activity (`DetectedActivity`) entered or left at [time]. */
data class ActivityChange(val time: Long, val activity: Int, val entering: Boolean)

/** A recording command (daily_on, pause, …, with who asked), a rest, a wake or a lost sky. */
data class TrackEvent(val time: Long, val kind: String, val detail: String?)

data class FootprintHistory(val days: List<String>, val sessions: List<TrackSession>)
