package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.LocalRailSpace
import kotlin.math.roundToInt

/**
 * The widest a single column of cards and words grows: anything wider, a phone turned on
 * its side among them, keeps it this wide in the middle, or lays two columns side by side.
 */
val READABLE_WIDTH = 600.dp

/** From this wide on, Material 3's medium breakpoint, a page's cards go two to a row. */
private val TWO_COLUMNS_FROM = 600.dp

/** The widest two columns grow together. */
private val WIDE_WIDTH = 1040.dp

/** Keeps what it pads clear of the system bars and the cutout down the sides, or down just one of them. */
@Composable
fun Modifier.sideInsetsPadding(sides: WindowInsetsSides = WindowInsetsSides.Horizontal): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(sides))

/** Whether the window is wider than it is tall: a phone on its side. */
@Composable
fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

/** The window's width, which Material 3's breakpoints go by rather than which way the phone is turned. */
@Composable
@ReadOnlyComposable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/**
 * Whether the window is at least Material 3's medium breakpoint wide, 600dp: a phone on its
 * side, a tablet upright, an unfolded foldable, where the tabs stand down a rail and the
 * margins are 24dp. A phone on its side shown beside another app may be narrower.
 */
@Composable
@ReadOnlyComposable
fun isMediumWidth(): Boolean = windowWidth() >= MEDIUM_WIDTH

/**
 * Whether the window is at least Material 3's expanded breakpoint wide, 840dp, where a list
 * and its detail stand side by side: a phone on its side, mostly, but not a narrow one.
 */
@Composable
fun isExpandedWidth(): Boolean = windowWidth() >= EXPANDED_WIDTH

/**
 * Whether the window is under Material 3's medium height, 480dp: a phone on its side, where
 * a tall app bar would take half the screen, so a page's bar starts small.
 */
@Composable
fun isCompactHeight(): Boolean = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < COMPACT_HEIGHT_BELOW }

private val MEDIUM_WIDTH = 600.dp
private val EXPANDED_WIDTH = 840.dp
private val COMPACT_HEIGHT_BELOW = 480.dp

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
 * it, filling the pane the rail leaves, as Material 3 lays a pane beside a navigation rail;
 * told how many columns its width holds and how far in from each side to keep them, in the
 * middle of the pane, so the list itself still scrolls from edge to edge. A page of one
 * column whatever its width, settings or a form, says so with [maxColumns], and is kept no
 * wider than it reads well.
 */
@Composable
fun PageWidth(modifier: Modifier = Modifier, maxColumns: Int = 2, content: @Composable (PageColumns) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize().railPadding()) {
        val count = if (maxWidth >= TWO_COLUMNS_FROM) maxColumns else 1
        val widest = if (count == 2) WIDE_WIDTH else READABLE_WIDTH
        content(PageColumns(count, ((maxWidth - widest) / 2).coerceAtLeast(0.dp)))
    }
}

/** Clear of the bars and the cutout down the sides and of the tabs beside the page, as [PageWidth] keeps a list. */
@Composable
fun Modifier.railPadding(): Modifier {
    val rail = LocalRailSpace.current
    val safe = WindowInsets.safeDrawing
    val (left, right) = with(LocalDensity.current) {
        if (rail.clearOfInsets) {
            rail.left to rail.right
        } else {
            safe.getLeft(this, LayoutDirection.Ltr).toDp() + rail.left to safe.getRight(this, LayoutDirection.Ltr).toDp() + rail.right
        }
    }
    return absolutePadding(left = left, right = right)
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
