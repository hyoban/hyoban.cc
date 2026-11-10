package cc.hyoban.moments.footprints

// What the footprints' store hands out, apart from the store itself, which is the phone's.

data class RecordingState(val daily: Boolean = false, val session: String? = null, val paused: Boolean = false) {
    val requested get() = daily || session != null
    val recording get() = requested && !paused
}

data class TrackSession(val id: String, val started: Long, val ended: Long?)
data class TrackSelection(val day: String? = null, val session: String? = null, val from: Long? = null, val to: Long? = null)

/**
 * A span of the footprints made public: its line, simplified to [simplify] metres, goes out with
 * 全部发布到网站; one following a trip ([collection], its uid) moves with the trip's days.
 * [removed] keeps one taken back until the site lets it go; [published] is what last went.
 */
data class TrackShare(
    val uid: String,
    val start: Long,
    val end: Long,
    val collection: String? = null,
    val simplify: Double = 30.0,
    val removed: Boolean = false,
    val published: String? = null,
)
/** A Play services activity (`DetectedActivity`) entered or left at [time]. */
data class ActivityChange(val time: Long, val activity: Int, val entering: Boolean)

/** A recording command (daily_on, pause, …, with who asked), a rest, a wake or a lost sky. */
data class TrackEvent(val time: Long, val kind: String, val detail: String?)

data class FootprintHistory(val days: List<String>, val sessions: List<TrackSession>)
