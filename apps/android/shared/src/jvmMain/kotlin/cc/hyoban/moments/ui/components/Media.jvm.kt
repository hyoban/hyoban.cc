package cc.hyoban.moments.ui.components

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.request.ImageRequest
import coil3.toBitmap

/** A computer's images are always read from memory. */
actual fun ImageRequest.Builder.readable(): ImageRequest.Builder = this

actual fun edgeColors(image: coil3.Image, top: Boolean): ImageBitmap {
    val bitmap = image.toBitmap().asComposeImageBitmap()
    val width = bitmap.width
    val rows = (bitmap.height / 4).coerceAtLeast(1)
    val pixels = IntArray(width * rows)
    bitmap.readPixels(pixels, startX = 0, startY = if (top) 0 else bitmap.height - rows, width = width, height = rows)
    val row = averageEdge(pixels, width, rows)
    val edge = ImageBitmap(EDGE_COLOUR_COUNT, 1)
    val canvas = Canvas(edge)
    val paint = Paint()
    row.forEachIndexed { x, colour ->
        paint.color = Color(colour)
        canvas.drawRect(x.toFloat(), 0f, x + 1f, 1f, paint)
    }
    return edge
}
