package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import coil3.compose.AsyncImage

/** On a computer the original is read whole and zoomed as the viewer's photos are; telephoto's tiles are the phone's alone. */
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
    ZoomablePhoto(shape, onZoom, onHold = onHold, onTap = onTap) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.aspectRatio(shape)) }
            AsyncImage(model, contentDescription = contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Box(Modifier.aspectRatio(shape)) { overlay() }
        }
    }
}
