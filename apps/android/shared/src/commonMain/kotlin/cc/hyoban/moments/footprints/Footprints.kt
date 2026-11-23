package cc.hyoban.moments.footprints

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * The footprints as the pages read them, which never leave the phone; the phone's store keeps
 * them, and a device with none has an empty one. Every call is made off the main thread.
 */
interface Footprints {
    /** Counts up whenever what is kept changes. */
    val revision: StateFlow<Long>
    fun state(): RecordingState

    /** The days there are footprints of, newest first. */
    fun days(): List<String>
    fun points(selection: TrackSelection): List<TrackPoint>

    /** The days and the trips recorded by session, newest first. */
    fun history(): FootprintHistory = FootprintHistory(days(), emptyList())

    /** [points] and the unconfirmed end after them, which only a device recording now has. */
    fun pointsWithTail(selection: TrackSelection): Pair<List<TrackPoint>, List<TrackPoint>> = points(selection) to emptyList()

    /** The activities the device said it was doing, entered and left, from [from] to [to]; none where it keeps none. */
    fun activities(from: Long, to: Long): List<ActivityChange> = emptyList()
}

/** No footprints: a device that records none. */
object NoFootprints : Footprints {
    override val revision: StateFlow<Long> = MutableStateFlow(0L)
    override fun state() = RecordingState()
    override fun days(): List<String> = emptyList()
    override fun points(selection: TrackSelection): List<TrackPoint> = emptyList()
}

/**
 * What the device that records the footprints adds to their panel on the map: turning the
 * recording, and exporting or deleting what it keeps. A device that only shows them (the Mac,
 * from the phone's backup) has none, and the panel leaves those out.
 */
interface FootprintRecording {
    /** What the recording says of itself now, or null before it starts. */
    val status: StateFlow<StringResource?>

    /** Turns the recording by [action] (daily_on, start_trip, pause, …). */
    suspend fun control(action: String)

    /**
     * Asks for what recording needs, then hands [action] to [onGranted]; [onDeclined] when the
     * ask is put off, [onDenied] when it is refused.
     */
    @Composable fun rememberEnable(onGranted: (String) -> Unit, onDeclined: () -> Unit, onDenied: () -> Unit): (String) -> Unit

    /** Saves [selection], [raw] or as the map draws it, to a GPX file the owner names from [name], through [work]. */
    @Composable fun rememberExport(work: (suspend () -> Unit) -> Unit): (selection: TrackSelection, raw: Boolean, name: String) -> Unit

    fun deletable(selection: TrackSelection): Boolean
    fun delete(selection: TrackSelection)
}
