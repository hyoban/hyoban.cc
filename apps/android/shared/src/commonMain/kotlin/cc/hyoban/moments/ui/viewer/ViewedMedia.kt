package cc.hyoban.moments.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.data.spec.MediaType
import org.jetbrains.compose.resources.StringResource

/**
 * One photo or video as the viewer shows it, whichever source it comes from: a Moment's, one being
 * written, or 相册's gallery, camera, NAS and published photos. A source says what it has of it and
 * the viewer draws every one the same way ([MediaPage]): zoomed by [ZoomableMedia], its live clip or
 * video played over it by the device's [ViewerMotion].
 */
interface ViewedMedia {
    /** Which it is among the viewer's, kept as it turns. */
    val key: String

    /**
     * What may move of it: nothing for a still, a clip for a live photo, or a video. A photo that
     * may turn out live (one picked, one linked to the NAS) says [MediaType.LIVE_PHOTO]: it plays
     * only once [rememberClip] finds its clip.
     */
    val type: MediaType

    /** Whether its video is one the device's player reads (MP4 and its kin); one that isn't keeps its poster. */
    val playable: Boolean get() = true

    /** What a screen reader says of the photo. */
    @Composable fun description(): String?

    /** Its still as it comes, sharper once [deep], zoomed into; [active] while it is the one shown. */
    @Composable fun rememberStill(active: Boolean, deep: Boolean): ViewedStill

    /** Where its live clip or video is read, while [active]; null for a still, and until it is known. */
    @Composable fun rememberClip(active: Boolean): MotionSource? = null

    /** Small, for the row of them under the viewer. */
    @Composable fun Thumb(modifier: Modifier)

    /** What it says of itself for the info panel. */
    @Composable fun rememberDetails(): PhotoDetails?

    /** A note for the info panel where [details] say too little, or where it is looked at from. */
    fun infoNote(details: PhotoDetails?): StringResource? = null

    /** The viewer has rested on it: what it then fetches of its own. */
    fun settled(reading: ReadAhead) {}

    /** The viewer has rested beside it, on [from]: what it fetches ahead, so turning to it shows it at once. */
    fun ahead(from: ViewedMedia, reading: ReadAhead) {}

    /** Drawn over its page, unzoomed: a camera's original coming down. */
    @Composable fun Decor() {}
}

/**
 * What a viewer reads ahead with: the image loader's [context], the screen's longest side in pixels
 * ([screen]), and a [scope] that outlives the page that asked, so what comes is kept for the next one.
 */
class ReadAhead(val context: coil3.PlatformContext, val screen: Int, val scope: kotlinx.coroutines.CoroutineScope)

/**
 * A still as far as it is had: [shape] width over height, [thumb] drawn at once while [model] is
 * read, the file, content address, web address or image request it is drawn from, in tiles where the
 * device reads them so. Neither yet: a spinner. [failed] says it couldn't be read, with a way to try again.
 */
data class ViewedStill(
    val shape: Float,
    val thumb: ImageBitmap? = null,
    val model: Any? = null,
    val failed: (() -> Unit)? = null,
)

/** The videos the players read, MP4 and its kin, by their types; another kind shows its poster. */
val PLAYED_VIDEOS = setOf("video/mp4", "video/quicktime", "video/3gpp")
