package cc.hyoban.moments.ui.picker

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import cc.hyoban.moments.ui.viewer.DOUBLE_TAP_ZOOM
import cc.hyoban.moments.ui.viewer.MAX_ZOOM
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * A picked photo's original, zooming as [cc.hyoban.moments.ui.viewer.ZoomablePhoto] does, read
 * by telephoto in tiles of the original as it zooms, so the detail holds up as far as it goes
 * rather than the screen-sized picture being stretched. [thumb], at [shape] (width over height),
 * stands in until the original is drawn. [overlay] lies over the photo at its fitted size,
 * unzoomed: a live photo's clip, which plays only while it is held at full size.
 */
@Composable
fun ZoomableOriginal(
    model: Any,
    thumb: Bitmap?,
    shape: Float,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val image = rememberZoomableImageState(zoom)
    val zoomed = (zoom.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoom(zoomed) }
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    Box(
        Modifier.fillMaxSize()
            // Held down, the finger's lifting ends what the hold started.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()
                    if (held) {
                        held = false
                        hold?.invoke(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!image.isImageDisplayed) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.aspectRatio(shape))
            }
        }
        ZoomableAsyncImage(
            model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            state = image,
            onClick = onTap?.let { tap -> { tap() } },
            onLongClick = if (onHold != null) {
                {
                    if (!zoomed) {
                        held = true
                        onHold(true)
                    }
                }
            } else {
                null
            },
            onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM),
        )
        Box(Modifier.aspectRatio(shape)) { overlay() }
    }
}
