package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.RailSpace
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Space

/**
 * Whether a page takes the design's arrangement for a phone on its side (H00): wider than tall
 * and too short for the upright one, so what would take height moves to the sides.
 */
@Composable
fun isPhoneLandscape(): Boolean = hidesSystemBars()

/** How wide the column of a page's figures stands at its start on a phone on its side, its list beside it. */
val SIDE_COLUMN = 264.dp

/**
 * A page on its side as the design has it (H00): [side], what doesn't scroll, the figures and the
 * page's main button, in a column at its start; [content], the list, filling the rest beside it.
 */
@Composable
fun SideBySide(
    modifier: Modifier = Modifier,
    sideWidth: Dp = SIDE_COLUMN,
    /** How far in its column stands from the start; none where what it holds keeps its own margin. */
    sideInset: Dp = Space.gutter,
    side: @Composable ColumnScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    // Clear of the cutout down either side as a whole; the list beside the column, then, needn't keep clear of it again.
    Row(modifier.railPadding(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Column(Modifier.width(sideWidth).fillMaxHeight().padding(start = sideInset), content = side)
        CompositionLocalProvider(LocalRailSpace provides RailSpace(0.dp, 0.dp, clearOfInsets = true)) { content() }
    }
}
