package cc.hyoban.moments.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens
import kotlin.math.roundToInt

// The design system's top bar, which is no bar: the way back and the page's actions float over
// the page as circles, the page's title large under them, which folds away as the page scrolls
// up and opens again at its top; folded, the title fades in small between the circles. On the
// page itself; the fade under it, once something has scrolled there, is the page's to draw.

/** How far the bar has folded: [heightOffset] from 0 (open) down to [heightOffsetLimit] (folded); [contentOffset], how far the page under it has scrolled. */
@Stable
class TopBarState(heightOffset: Float = 0f) {
    var heightOffsetLimit by mutableFloatStateOf(-Float.MAX_VALUE)
    var heightOffset by mutableFloatStateOf(heightOffset)
    var contentOffset by mutableFloatStateOf(0f)

    /** 0 open, 1 folded. */
    val collapsedFraction: Float
        get() = if (heightOffsetLimit != 0f && heightOffsetLimit != -Float.MAX_VALUE) heightOffset / heightOffsetLimit else 0f

    /** Whether the page has scrolled under the bar. */
    val overlappedFraction: Float
        get() = if (contentOffset < -1f) 1f else 0f
}

/** How the bar follows the page's scrolling: folding until folded, or [pinned] where it never folds. */
class TopBarScroll(val state: TopBarState, val pinned: Boolean) {
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (pinned || available.y >= 0f) return Offset.Zero
            val before = state.heightOffset
            state.heightOffset = (before + available.y).coerceIn(state.heightOffsetLimit, 0f)
            return Offset(0f, state.heightOffset - before)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            state.contentOffset += consumed.y
            if (pinned) return Offset.Zero
            if (available.y > 0f) {
                // Opened again only once the page is at its top and pulled further.
                val before = state.heightOffset
                state.heightOffset = (before + available.y).coerceIn(state.heightOffsetLimit, 0f)
                return Offset(0f, state.heightOffset - before)
            }
            if (consumed.y == 0f && available.y > 0f) state.contentOffset = 0f
            return Offset.Zero
        }
    }
}

@Composable
fun rememberTopBarScroll(pinned: Boolean = false): TopBarScroll {
    val offset = rememberSaveable { floatArrayOf(0f) }
    val state = remember { TopBarState(offset[0]) }
    return remember(pinned) { TopBarScroll(state, pinned) }
}

/** The bar's insets: clear of the status bar and of the sides' cutouts. */
val TopBarWindowInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/**
 * The top bar: [navigationIcon] and [actions] as circles floating over the page, and between
 * them, as it folds or while [small], [title] small and centred. Unless [small], [title] large
 * under them, with [subtitle] under it; [large] for a tab's page, as large as a page's own.
 */
@Composable
fun TopBar(
    title: @Composable () -> Unit,
    scroll: TopBarScroll,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopBarWindowInsets,
    large: Boolean = false,
    small: Boolean = false,
) {
    val typography = AppTheme.typography
    val state = scroll.state
    val folded = if (small) 1f else state.collapsedFraction
    Column(modifier.fillMaxWidth().windowInsetsPadding(windowInsets)) {
        CompositionLocalProvider(LocalFloatingIconButtons provides true) {
            Layout(
                content = {
                    Box { navigationIcon() }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                    Column(
                        Modifier.graphicsLayer { alpha = ((folded - 0.5f) * 2f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides tokens.tx) { ProvideTextStyle(typography.body.strong) { title() } }
                        if (subtitle != null && small) {
                            CompositionLocalProvider(LocalContentColor provides tokens.tx2) { ProvideTextStyle(typography.caption) { subtitle() } }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp),
            ) { measurables, constraints ->
                // The small title centred on the page, as wide as is left between the wider side
                // and its mirror, so it stays in the middle whichever side has more buttons.
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val start = measurables[0].measure(loose)
                val end = measurables[1].measure(loose)
                val side = maxOf(start.width, end.width)
                val titleWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0)
                val middle = measurables[2].measure(loose.copy(maxWidth = titleWidth))
                val height = constraints.maxHeight
                layout(constraints.maxWidth, height) {
                    start.place(0, (height - start.height) / 2)
                    end.place(constraints.maxWidth - end.width, (height - end.height) / 2)
                    middle.place((constraints.maxWidth - middle.width) / 2, (height - middle.height) / 2)
                }
            }
        }
        if (!small) {
            // Laid out at its full height and shown only as much as is left of it.
            Layout(
                content = {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).graphicsLayer { alpha = 1f - folded },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides tokens.tx) {
                            ProvideTextStyle(typography.titlePage) { title() }
                        }
                        if (subtitle != null) {
                            CompositionLocalProvider(LocalContentColor provides tokens.tx2) { ProvideTextStyle(typography.body) { subtitle() } }
                        }
                    }
                },
                modifier = Modifier.clipToBounds(),
            ) { measurables, constraints ->
                val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
                val full = placeable.height.toFloat()
                if (state.heightOffsetLimit != -full) {
                    state.heightOffsetLimit = -full
                    state.heightOffset = state.heightOffset.coerceIn(-full, 0f)
                }
                val shown = (full + state.heightOffset).roundToInt().coerceAtLeast(0)
                layout(constraints.maxWidth, shown) { placeable.place(0, shown - placeable.height) }
            }
        }
    }
}

/** How tall the row of floating circles stands, 40dp circles with 8dp above and below. */
private val BAR_HEIGHT = 56.dp
