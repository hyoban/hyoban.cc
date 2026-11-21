package cc.hyoban.moments.ui.picker

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
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
 * zooming and panning with it: a live photo's clip.
 */
@Composable
actual fun ZoomableOriginal(
    model: Any,
    thumb: ImageBitmap?,
    shape: Float,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onHold: ((Boolean) -> Unit)?,
    onTap: (() -> Unit)?,
    overlay: @Composable () -> Unit,
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
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.aspectRatio(shape))
            }
        }
        ZoomableAsyncImage(
            // Coil reads a Bitmap but not Compose's ImageBitmap: until the original comes, the
            // thumbnail stands in as one, so it zooms and the zoom can fetch the original.
            if (model is ImageBitmap) model.asAndroidBitmap() else model,
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
        // Over the photo, not in it, so laid onto where the photo is drawn now: a live clip playing
        // while it is pinched stays on it. That is read from the photo's bounds on the screen, not
        // the zoom's transformation, which also fits and centres the original once it is read in
        // tiles but not the thumbnail before it. Its player is a view of its own, which would take
        // every touch from the photo under it, so they go on to the photo too.
        // Read here, as it is composed: a layer's own block isn't told when the zoom moves.
        val drawn = zoom.transformedContentBounds
        Box(
            Modifier.touchesThrough().fillMaxSize().graphicsLayer {
                if (drawn.width > 0f && drawn.height > 0f && size.width > 0f && size.height > 0f) {
                    // Where the overlay sits unmoved: fitted and centred, as the photo first is.
                    val width = if (size.width / size.height > shape) size.height * shape else size.width
                    val height = width / shape
                    scaleX = drawn.width / width
                    scaleY = drawn.height / height
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = drawn.left - (size.width - width) / 2 * scaleX
                    translationY = drawn.top - (size.height - height) / 2 * scaleY
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.aspectRatio(shape)) { overlay() }
        }
    }
}

/** Lets what lies under this take its touches too, as a sibling over it otherwise keeps them all. */
private fun Modifier.touchesThrough(): Modifier = this then TouchesThrough

private data object TouchesThrough : ModifierNodeElement<TouchesThroughNode>() {
    override fun create() = TouchesThroughNode()
    override fun update(node: TouchesThroughNode) {}
}

private class TouchesThroughNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {}
    override fun onCancelPointerInput() {}
    override fun sharePointerInputWithSiblings() = true
}
