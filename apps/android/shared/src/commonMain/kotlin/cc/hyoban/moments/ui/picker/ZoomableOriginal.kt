package cc.hyoban.moments.ui.picker

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A picked photo's original, zooming as [cc.hyoban.moments.ui.viewer.ZoomablePhoto] does, read
 * by telephoto in tiles of the original as it zooms, so the detail holds up as far as it goes
 * rather than the screen-sized picture being stretched. [thumb], at [shape] (width over height),
 * stands in until the original is drawn. [overlay] lies over the photo at its fitted size,
 * unzoomed: a live photo's clip, which plays only while it is held at full size.
 */
@Composable
expect fun ZoomableOriginal(
    model: Any,
    thumb: ImageBitmap?,
    shape: Float,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
)
