package cc.hyoban.moments.footprints

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The footprints as the pages read them and make their spans public; the phone's store keeps
 * them, and a device with none has an empty one. Every call is made off the main thread.
 */
interface Footprints {
    /** Counts up whenever what is kept changes. */
    val revision: StateFlow<Long>
    fun state(): RecordingState

    /** The days there are footprints of, newest first. */
    fun days(): List<String>
    fun points(selection: TrackSelection): List<TrackPoint>

    /** Every public span, those taken back and not yet let go by the site among them. */
    fun shares(): List<TrackShare>
    fun saveShare(share: TrackShare)
    fun removeShare(uid: String)
}

/** No footprints: a device that records none. */
object NoFootprints : Footprints {
    override val revision: StateFlow<Long> = MutableStateFlow(0L)
    override fun state() = RecordingState()
    override fun days(): List<String> = emptyList()
    override fun points(selection: TrackSelection): List<TrackPoint> = emptyList()
    override fun shares(): List<TrackShare> = emptyList()
    override fun saveShare(share: TrackShare) {}
    override fun removeShare(uid: String) {}
}
