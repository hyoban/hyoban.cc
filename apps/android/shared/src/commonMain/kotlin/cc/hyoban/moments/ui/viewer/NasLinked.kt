package cc.hyoban.moments.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.data.spec.NasLink
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.ui.picker.ZoomableOriginal
import java.io.IOException

/*
 * A Moment's photo linked to the NAS, looked at in a viewer as the NAS's own photo is: the site's
 * copy may be smaller, 打码'd or a still, while the NAS keeps the original whole, live or not. Only
 * what is looked at changes; what is shared or saved stays the site's.
 */

/**
 * Where the photo linked to [link] has its live clip, as the NAS's own has it: a motion photo's
 * read by range from its original, where the start of the original says it starts; an iPhone's,
 * the MOV the NAS keeps beside it, asked for once (the answer kept). Null for a still; throws
 * when the NAS couldn't be asked.
 */
suspend fun Nas.linkedClip(link: NasLink): MotionSource? {
    val photo = linkedPhoto(link) ?: throw IOException("the size of ${link.name} not read")
    val start = clipStart(photo)
    if (start != null) {
        cached(photo)?.let { return MotionSource(it.toURI().toString(), start) }
        val (url, headers) = original(photo)
        return MotionSource(url, start, headers, motionKey(photo, apart = false))
    }
    return probeClip(photo)?.let { MotionSource(it.toURI().toString()) }
}

/**
 * The clip a viewer plays for a photo linked to [link]: the NAS's ([linkedClip]), else [published]
 * (the site's clip, a published live photo's) when the NAS has none or couldn't be asked.
 */
suspend fun Nas.viewedClip(link: NasLink, published: MotionSource?): MotionSource? =
    try {
        linkedClip(link) ?: published
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        published
    }

/** Fetches the NAS's big thumbnail of the photo linked to [link] ahead, for a viewer turning to it. */
suspend fun Nas.linkedAhead(link: NasLink) {
    thumbnail(photo(link), NasProtocol.BIG)
}

/**
 * The photo linked to [link] drawn from the NAS, as its own is in 相册's viewer: its big thumbnail,
 * then its original once zoomed into, read in tiles as it zooms. [fallback], the site's copy, stands
 * while neither is had, the NAS not reached.
 */
@Composable
fun NasLinkedStill(
    nas: Nas,
    link: NasLink,
    shape: Float,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: ((Boolean) -> Unit)?,
    overlay: @Composable () -> Unit,
    fallback: @Composable () -> Unit,
) {
    val photo = remember(link) { nas.photo(link) }
    val big by produceState<ImageBitmap?>(null, link) { value = nas.thumbnail(photo, NasProtocol.BIG) }
    var deep by remember(link) { mutableStateOf(false) }
    val original by produceState(nas.cached(photo), link, deep) {
        if (deep && value == null) value = nas.fetchOriginal(photo)
    }
    val model: Any = original ?: big ?: return fallback()
    ZoomableOriginal(
        model,
        big,
        shape,
        contentDescription,
        onZoom = { zoomed ->
            if (zoomed) deep = true
            onZoom(zoomed)
        },
        onHold = onHold,
        onTap = onTap,
        overlay = overlay,
    )
}
