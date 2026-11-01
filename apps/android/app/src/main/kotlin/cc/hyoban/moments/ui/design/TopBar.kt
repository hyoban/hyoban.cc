package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens
import kotlin.math.roundToInt

// The design system's top bar, which is no bar: the way back and the page's actions float over
// the page as circles, and the page's title large under them is the first thing on the page,
// scrolling away with it, under the circles, as the bar folds, and opening again at the page's
// top; the last half of the way it fades out as the title fades in small between the circles. Pulled down at the top, the title
// and the page follow the finger, the further the heavier, and spring back when let go; let go
// half folded, the bar settles open or folded, whichever is nearer.

/**
 * How far the bar has folded: [heightOffset] from 0 (open) down to [heightOffsetLimit] (folded);
 * [contentOffset], how far the page under it has scrolled; [stretch], how far the open title has
 * been pulled down past its place.
 */
@Stable
class TopBarState(heightOffset: Float = 0f) {
    var heightOffsetLimit by mutableFloatStateOf(-Float.MAX_VALUE)
    var heightOffset by mutableFloatStateOf(heightOffset)
    var contentOffset by mutableFloatStateOf(0f)

    /** How far the finger has pulled past the top, of which the title follows [stretch]. */
    var pull by mutableFloatStateOf(0f)
    var stretch by mutableFloatStateOf(0f)
        private set

    internal fun pullTo(value: Float, distance: Float) {
        pull = value.coerceAtLeast(0f)
        // The system's rubber band: following the finger at first, ever more slowly after.
        stretch = distance * (1f - 1f / (pull * RUBBER_BAND / distance + 1f))
    }

    /** 0 open, 1 folded. */
    val collapsedFraction: Float
        get() = if (heightOffsetLimit != 0f && heightOffsetLimit != -Float.MAX_VALUE) heightOffset / heightOffsetLimit else 0f

    /** Whether the page has scrolled under the bar. */
    val overlappedFraction: Float
        get() = if (contentOffset < -1f) 1f else 0f
}

/**
 * How the bar follows the page's scrolling: folding until folded, or [pinned] where it never
 * folds; pulled past the top as far as [distance] would take a page of that height, and settling
 * on [spring].
 */
class TopBarScroll(val state: TopBarState, val pinned: Boolean, private val distance: Float, private val spring: AnimationSpec<Float>) {
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (pinned || available.y >= 0f) return Offset.Zero
            // Pushed back up, a pull gives way first, then the title folds.
            var left = available.y
            if (state.pull > 0f) {
                val taken = maxOf(left, -state.pull)
                state.pullTo(state.pull + taken, distance)
                left -= taken
            }
            val before = state.heightOffset
            state.heightOffset = (before + left).coerceIn(state.heightOffsetLimit, 0f)
            return Offset(0f, available.y - left + (state.heightOffset - before))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            state.contentOffset += consumed.y
            if (pinned) return Offset.Zero
            if (available.y > 0f) {
                // Opened again only once the page is at its top and pulled further; pulled further
                // still by the finger, the open title follows it down.
                val before = state.heightOffset
                state.heightOffset = (before + available.y).coerceIn(state.heightOffsetLimit, 0f)
                val opened = state.heightOffset - before
                // Nothing left to scroll: the page is at its top, however it got there.
                state.contentOffset = 0f
                val rest = available.y - opened
                if (rest > 0f && source == NestedScrollSource.UserInput) {
                    state.pullTo(state.pull + rest, distance)
                    return Offset(0f, available.y)
                }
                return Offset(0f, opened)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pinned || state.pull <= 0f) return Velocity.Zero
            // Let go of a pull: it springs back rather than flinging the page.
            animate(state.pull, 0f, animationSpec = spring) { value, _ -> state.pullTo(value, distance) }
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val folded = state.collapsedFraction
            if (pinned || folded <= 0f || folded >= 1f) return Velocity.Zero
            // Let go half folded: open or folded, whichever is nearer.
            val target = if (folded < 0.5f) 0f else state.heightOffsetLimit
            animate(state.heightOffset, target, animationSpec = spring) { value, _ -> state.heightOffset = value }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberTopBarScroll(pinned: Boolean = false): TopBarScroll {
    // Kept with how far the page under it has scrolled, which is kept too, so the two agree
    // again when the page is made anew (a theme or a window changed).
    val state = rememberSaveable(saver = TopBarStateSaver) { TopBarState() }
    val distance = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    val motion = AppTheme.motionScheme
    val spring = remember(motion) { motion.defaultSpatialSpec<Float>() }
    return remember(pinned, distance, spring) { TopBarScroll(state, pinned, distance, spring) }
}

private val TopBarStateSaver = listSaver<TopBarState, Float>(
    save = { listOf(it.heightOffset, it.contentOffset) },
    restore = { (height, content) -> TopBarState(height).apply { contentOffset = content } },
)

/** How much of a pull past the top the title follows, the system's rubber band. */
private const val RUBBER_BAND = 0.55f

/** The bar's insets: clear of the status bar and of the sides' cutouts. */
val TopBarWindowInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/**
 * The top bar: [navigationIcon] and [actions] as circles floating over the page, and between
 * them, as it folds or while [small], [title] small and centred, [subtitle] under it. The title
 * large is the page's own, [TopBarLargeTitle].
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
    small: Boolean = false,
) {
    val typography = AppTheme.typography
    val state = scroll.state
    CompositionLocalProvider(LocalFloatingIconButtons provides true) {
        Layout(
            content = {
                Box { navigationIcon() }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                Column(
                    Modifier.graphicsLayer {
                        val folded = if (small) 1f else state.collapsedFraction
                        alpha = ((folded - 0.5f) * 2f).coerceIn(0f, 1f)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides tokens.tx) { ProvideTextStyle(typography.body.strong) { title() } }
                    if (subtitle != null) {
                        CompositionLocalProvider(LocalContentColor provides tokens.tx2) { ProvideTextStyle(typography.caption) { subtitle() } }
                    }
                }
            },
            modifier = modifier.fillMaxWidth().windowInsetsPadding(windowInsets).height(TopBarRowHeight).padding(horizontal = 4.dp),
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
}

/**
 * The page's [title] large, [subtitle] under it, its first thing under the bar's circles: as tall
 * as is left of it as [scroll] folds the bar, the rest gone up under the circles, and pulled down
 * with the page at its top; [large] for a tab's page, as large as a page's own.
 */
@Composable
fun TopBarLargeTitle(
    title: @Composable () -> Unit,
    scroll: TopBarScroll,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = TopBarWindowInsets,
    large: Boolean = false,
) {
    val typography = AppTheme.typography
    val state = scroll.state
    Layout(
        content = {
            Column(
                // On the page's colour: what has scrolled under it doesn't show through.
                // Whole until it is half folded away, then fading out as the small title fades in.
                Modifier.fillMaxWidth().graphicsLayer { alpha = ((1f - state.collapsedFraction) * 2f).coerceIn(0f, 1f) }.background(tokens.bg)
                    .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
        modifier = modifier,
    ) { measurables, constraints ->
        // Laid out at its full height, as tall as is left of it, and placed up under the circles
        // by as much as has folded away, or down by as much as it is pulled.
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
        val full = placeable.height.toFloat()
        if (state.heightOffsetLimit != -full) {
            state.heightOffsetLimit = -full
            state.heightOffset = state.heightOffset.coerceIn(-full, 0f)
        }
        val shown = (full + state.heightOffset + state.stretch).roundToInt().coerceAtLeast(0)
        layout(constraints.maxWidth, shown) { placeable.place(0, shown - placeable.height) }
    }
}

/** How tall the row of floating circles stands, 40dp circles with 8dp above and below. */
val TopBarRowHeight = 56.dp
