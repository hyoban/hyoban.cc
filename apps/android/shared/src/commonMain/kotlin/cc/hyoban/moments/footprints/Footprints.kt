package cc.hyoban.moments.footprints

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
