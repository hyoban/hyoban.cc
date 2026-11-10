package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens

// A grouped list whose rows are laid out one by one, in a lazy list say, rather than inside one
// group: each row draws its part of the group, the surface, the hairline round the whole and
// the hairline between it and the row above, so together they read as the settings' groups do.

/** The shape of the row at [index] of [count]: the group's corners on the first and the last, square between. */
@Composable
@ReadOnlyComposable
fun groupedShape(index: Int, count: Int): Shape {
    val corners = AppTheme.shapes.medium
    return corners.copy(
        topStart = if (index == 0) corners.topStart else ZeroCornerSize,
        topEnd = if (index == 0) corners.topEnd else ZeroCornerSize,
        bottomStart = if (index == count - 1) corners.bottomStart else ZeroCornerSize,
        bottomEnd = if (index == count - 1) corners.bottomEnd else ZeroCornerSize,
    )
}

/** [groupedShape] for a list item, the same in every state. */
@Composable
@ReadOnlyComposable
fun groupedShapes(index: Int, count: Int): ListItemShapes = groupedShape(index, count).let { ListItemShapes(it, it, it, it, it, it) }

/** The row at [index] of [count] drawn as its part of the group: the surface, its edges' hairline, and the one between it and the row above, from [inset]. */
@Composable
fun Modifier.groupedRow(index: Int, count: Int, inset: Float = 16f): Modifier {
    val shape = groupedShape(index, count)
    val line = tokens.border
    return clip(shape).background(tokens.surface).drawWithContent {
        drawContent()
        val stroke = 1.dp.toPx()
        // The outline at twice the hairline, half of it clipped away by the row's own shape;
        // the edges shared with the rows above and below left out.
        val top = if (index == 0) 0f else stroke
        val bottom = if (index == count - 1) size.height else size.height - stroke
        clipRect(top = top, bottom = bottom) { drawOutline(shape.createOutline(size, layoutDirection, this), line, style = Stroke(stroke * 2)) }
        if (index > 0) drawLine(line, Offset(inset.dp.toPx(), stroke / 2), Offset(size.width, stroke / 2), stroke)
    }
}
