package cc.hyoban.moments.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

actual fun ImageRequest.Builder.readable(): ImageRequest.Builder = allowHardware(false)

actual fun edgeColors(image: coil3.Image, top: Boolean): ImageBitmap {
    val bitmap = image.toBitmap()
    val width = bitmap.width
    val rows = (bitmap.height / 4).coerceAtLeast(1)
    val pixels = IntArray(width * rows)
    bitmap.getPixels(pixels, 0, width, 0, if (top) 0 else bitmap.height - rows, width, rows)
    val row = averageEdge(pixels, width, rows)
    return android.graphics.Bitmap.createBitmap(row, EDGE_COLOUR_COUNT, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
