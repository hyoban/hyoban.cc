package cc.hyoban.moments.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What moves of one item in the viewer, a live photo's clip or a video, played the device's
 * way: the phone with its own player, a computer handing a video to the system. The viewer
 * keeps one for each item and asks it for what it draws over the still and in the bars.
 */
interface ViewerMotion {
    /** What plays over the still from [url], while its page is [active]. */
    @Composable fun Over(url: String, active: Boolean, modifier: Modifier)

    /** Over the page while a video waits to play. */
    @Composable fun Waiting() {}

    /** A video's controls, in the bar under it. */
    @Composable fun Controls(modifier: Modifier) {}

    /** A live photo's button in the bar over it. */
    @Composable fun LiveButton(modifier: Modifier) {}

    /** Space: a video plays or pauses, a live photo plays or stops. */
    fun toggle()

    /** Held down, a live photo plays with its sound until let go; null where it can't. */
    val hold: ((Boolean) -> Unit)? get() = null
}
