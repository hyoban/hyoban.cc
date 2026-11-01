package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.RailSpace
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Space

/**
 * Whether a page takes the design's arrangement for a phone on its side (H00): wider than tall
 * and too short for the upright one, so what would take height moves to the sides.
 */
@Composable
fun isPhoneLandscape(): Boolean = hidesSystemBars()

/**
 * Whether the tabs stand down the left on their side (H00): down the side away from the camera's
 * cutout, so the phone reads the same turned either way; the leading side where there is none.
 */
@Composable
fun railOnLeft(): Boolean {
    val cutout = WindowInsets.displayCutout
    val density = LocalDensity.current
    val left = cutout.getLeft(density, LayoutDirection.Ltr)
    val right = cutout.getRight(density, LayoutDirection.Ltr)
    return when {
        left > right -> false
        right > left -> true
        else -> LocalLayoutDirection.current == LayoutDirection.Ltr
    }
}

/**
 * Whether a page's photo or map takes the left half on its side (H00): the camera's side, where it
 * runs under the cutout and the words beside it needn't keep clear of it; the left without one.
 */
@Composable
fun mediaOnLeft(): Boolean {
    val cutout = WindowInsets.displayCutout
    val density = LocalDensity.current
    return cutout.getLeft(density, LayoutDirection.Ltr) >= cutout.getRight(density, LayoutDirection.Ltr)
}

/**
 * A page on its side opening with a photo or a map (H00): [media] filling the half on the camera's
 * side, edge to edge and the whole height, and [content], the words, the other half, clear of the
 * bars on its far side. Each is handed the modifier of its half.
 */
@Composable
fun MediaBeside(
    modifier: Modifier = Modifier,
    media: @Composable (Modifier) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val left = mediaOnLeft()
    Row(modifier.fillMaxSize()) {
        val half = Modifier.weight(1f).fillMaxHeight()
        val words = half.windowInsetsPadding(WindowInsets.safeDrawing.only(if (left) WindowInsetsSides.Right else WindowInsetsSides.Left))
        if (left) {
            media(half)
            content(words)
        } else {
            content(words)
            media(half)
        }
    }
}

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
