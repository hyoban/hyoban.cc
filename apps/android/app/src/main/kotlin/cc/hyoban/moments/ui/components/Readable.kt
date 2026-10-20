package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.LocalRailSpace
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * The widest a single column of cards and words grows: anything wider, a phone turned on
 * its side among them, keeps it this wide in the middle, or lays two columns side by side.
 */
val READABLE_WIDTH = 600.dp

/** From this wide on, a page's cards go two to a row. */
private val TWO_COLUMNS_FROM = 520.dp

/** The widest two columns grow together. */
private val WIDE_WIDTH = 1040.dp

/** Keeps what it pads clear of the system bars and the cutout down the sides, or down just one of them. */
@Composable
fun Modifier.sideInsetsPadding(sides: WindowInsetsSides = WindowInsetsSides.Horizontal): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(sides))

/** Whether the window is wider than it is tall: a phone on its side. */
@Composable
fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

/** Whether the page is wider than it is tall. */
val BoxWithConstraintsScope.landscape: Boolean
    get() = maxWidth > maxHeight

/**
 * How a page's list sits in its width: in one column or two, with [margin] each side
 * keeping it centred at no wider than it reads well.
 */
class PageColumns(val count: Int, val margin: Dp)

/**
 * A page's list, clear of the bars and the cutout down the sides and of the tabs beside
 * it on a phone on its side, as much room left on the one side as the other so it stays
 * in the middle; told how many columns its width holds and how far in from each side to
 * keep them, so the list itself still scrolls from edge to edge.
 */
@Composable
fun PageWidth(modifier: Modifier = Modifier, content: @Composable (PageColumns) -> Unit) {
    val rail = LocalRailSpace.current
    val safe = WindowInsets.safeDrawing
    val side = with(LocalDensity.current) {
        maxOf(safe.getLeft(this, LayoutDirection.Ltr).toDp() + rail.left, safe.getRight(this, LayoutDirection.Ltr).toDp() + rail.right)
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = side)) {
        val count = if (maxWidth >= TWO_COLUMNS_FROM) 2 else 1
        val widest = if (count == 2) WIDE_WIDTH else READABLE_WIDTH
        content(PageColumns(count, ((maxWidth - widest) / 2).coerceAtLeast(0.dp)))
    }
}

/** The tallest a Moment's photos stand in a card: on a phone on its side, no taller than most of the screen. */
val LocalMediaMaxHeight = staticCompositionLocalOf { Dp.Infinity }

/**
 * The whole width at [ratio], width over height, but no taller than [maxHeight]: a tall
 * photo is cropped further rather than standing taller than the screen.
 */
fun Modifier.aspectAtMost(ratio: Float, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val cap = if (maxHeight == Dp.Infinity) Int.MAX_VALUE else maxHeight.roundToPx()
    val height = minOf((width / ratio).roundToInt(), cap).coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
