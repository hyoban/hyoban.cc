package cc.hyoban.moments.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.theme.MediaOverlay
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * On a computer the still is read whole, the thumbnail under it until it comes, and the two zoomed
 * as one with what lies over them; telephoto's tiles are the phone's alone.
 */
@Composable
actual fun ZoomableMedia(
    still: ViewedStill,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: ((Boolean) -> Unit)?,
    overlay: @Composable () -> Unit,
) {
    val shape = still.shape
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val zoomed = (zoom.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoom(zoomed) }
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    val tap by rememberUpdatedState(onTap)
    BoxWithConstraints(
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
            }
            .zoomable(
                zoom,
                onClick = { tap() },
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
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Where the photo sits inside the window, fitted and centred as it is laid out, so a pan
        // stops at the photo's edges rather than the window's.
        val fitted = remember(shape, constraints) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            if (width / height > shape) Size(height * shape, height) else Size(width, width / shape)
        }
        SideEffect { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(fitted)) }
        Box(Modifier.aspectRatio(shape)) {
            still.thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            when (val model = still.model) {
                null -> if (still.thumb == null && still.failed == null) Spinner(Modifier.align(Alignment.Center), color = MediaOverlay.content)
                // Coil doesn't read Compose's own bitmaps: one already had is drawn as it is.
                is ImageBitmap -> Image(model, contentDescription = contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> AsyncImage(model, contentDescription = contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            overlay()
        }
        if (still.thumb == null) still.failed?.let { StillFailed(it) }
    }
}
