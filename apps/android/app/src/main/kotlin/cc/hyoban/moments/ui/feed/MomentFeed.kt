package cc.hyoban.moments.ui.feed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.DayPill
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.MomentCard
import cc.hyoban.moments.ui.components.MonthHeader
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.formatDayOfMonth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/**
 * What goes above a feed's Moments, a row at a time, each by its key: the page's header,
 * room for a back button. Kept apart from the list it goes in, as the feed is a list in
 * one column and a staggered grid in two.
 */
class FeedLeading {
    internal val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()

    fun item(key: String, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/** The rows of [leading] in a list. */
fun LazyListScope.leadingRows(leading: FeedLeading.() -> Unit) {
    FeedLeading().apply(leading).rows.forEach { (key, content) -> item(key = key) { Box(Modifier.animateItem()) { content() } } }
}

/**
 * Moments as cards, a large header where a month starts and a pill for each day that
 * floats over its Moments as they scroll; [leading] goes above the first. [dayLabel]
 * says what a day's pill reads. Where the width holds two columns, the cards fall into
 * them as a waterfall, each going under the shorter column, and each card says its day.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MomentFeed(
    moments: List<MomentRecord>,
    places: Map<String, Place>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    onMoment: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    months: Boolean = true,
    dayLabel: (String) -> String = ::formatDayOfMonth,
    showTrip: Boolean = true,
    /** The tab the feed is on, which, tapped again while shown, takes it back to its top. */
    tab: Any? = null,
    /** The Moment shown in a detail pane beside the feed, marked as chosen. */
    selected: String? = null,
    leading: FeedLeading.() -> Unit = {},
) {
    val days = remember(moments) { moments.groupBy { it.dateKey }.toList() }
    val bottom = LocalBottomClearance.current + LocalFabClearance.current + Space.xl
    val card: @Composable (MomentRecord, Modifier, Boolean) -> Unit = { moment, cardModifier, showDate ->
        MomentCard(
            moment,
            places,
            trip = if (showTrip) tripOf(moment, trips) else null,
            urls = urls,
            onOpen = { onMoment(moment.uid) },
            modifier = cardModifier,
            showDate = showDate,
            selected = moment.uid == selected,
        )
    }

    PageWidth(modifier) { columns ->
        if (columns.count > 1) {
            OnTabReselect(tab) { gridState.animateScrollToItem(0) }
            val rows = remember(leading) { FeedLeading().apply(leading).rows }
            // Headers run across both columns, out to the page's own edges.
            val across = Modifier.wide(Space.gutter)
            LazyVerticalStaggeredGrid(
                StaggeredGridCells.Fixed(columns.count),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = columns.margin + Space.gutter, end = columns.margin + Space.gutter, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalItemSpacing = Space.m,
            ) {
                rows.forEach { (key, content) ->
                    item(key = key, span = StaggeredGridItemSpan.FullLine) { Box(Modifier.animateItem().then(across)) { content() } }
                }
                // A month at a time, or, where there are no months to head, a day at a time.
                val groups = if (months) byMonth(moments) else days
                groups.forEach { (group, here) ->
                    item(key = "group-$group", contentType = "group", span = StaggeredGridItemSpan.FullLine) {
                        Box(Modifier.animateItem().then(across)) {
                            if (months) {
                                val (name, year) = formatMonth(here.first().dateKey)
                                MonthHeader(name, year)
                            } else {
                                DayPill(dayPillText(dayLabel(group), here.size))
                            }
                        }
                    }
                    items(here, key = { it.uid }, contentType = { "moment" }) { moment ->
                        card(moment, Modifier.animateItem(), months)
                    }
                }
                // Across the end, so both columns end together: a column left short, a month
                // with one Moment leaving the other empty, has the grid finding its end over
                // and over while it is pushed against it, jumping back and forth.
                item(key = "end", contentType = "end", span = StaggeredGridItemSpan.FullLine) { Spacer(Modifier) }
            }
        } else {
            OnTabReselect(tab) { state.animateScrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = columns.margin, end = columns.margin, bottom = bottom),
            ) {
                leadingRows(leading)
                var month: String? = null
                days.forEach { (day, dayMoments) ->
                    if (months && day.take(7) != month) {
                        month = day.take(7)
                        val (name, year) = formatMonth(day)
                        item(key = "month-${day.take(7)}", contentType = "month") { Box(Modifier.animateItem()) { MonthHeader(name, year) } }
                    }
                    stickyHeader(key = "day-$day", contentType = "day") {
                        // Over the cards it is held above, which come after it.
                        Box(Modifier.zIndex(1f).belowListTop(state, "day-$day")) {
                            DayPill(dayPillText(dayLabel(day), dayMoments.size))
                        }
                    }
                    items(dayMoments, key = { it.uid }, contentType = { "moment" }) { moment ->
                        card(moment, Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.m / 2), false)
                    }
                }
            }
        }
    }
}

/** A day's pill: its label, and how many Moments it has when more than one. */
@Composable
private fun dayPillText(label: String, count: Int): String =
    if (count > 1) "$label · ${pluralStringResource(R.plurals.count_moments, count, count)}" else label

/**
 * A sticky header held below the list's top padding while it is stuck, rather than at the
 * very top under the status bar, and pushed up from there by the next one coming.
 */
private fun Modifier.belowListTop(state: LazyListState, key: String): Modifier = graphicsLayer {
    val items = state.layoutInfo.visibleItemsInfo
    val index = items.indexOfFirst { it.key == key }
    val item = items.getOrNull(index) ?: return@graphicsLayer
    // Offsets count from below the top padding: a stuck header is above that, at the list's very top.
    if (item.offset >= 0) return@graphicsLayer
    val next = items.drop(index + 1).firstOrNull { (it.key as? String)?.startsWith("day-") == true }?.offset ?: Int.MAX_VALUE
    translationY = (minOf(0, next - item.size) - item.offset).toFloat()
}

/** The Moments month by month, as `YYYY-MM` and that month's, in the order given. */
private fun byMonth(moments: List<MomentRecord>): List<Pair<String, List<MomentRecord>>> =
    moments.groupBy { it.dateKey.take(7) }.toList()

/** Wider by [by] each side than the space it is given: a header across a grid out to the page's edges, past the grid's own padding. */
private fun Modifier.wide(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Up to [columns] cards side by side, each as wide as the others, a short row's last place left empty. */
@Composable
fun <T> CardRow(cards: List<T>, columns: Int, modifier: Modifier = Modifier, card: @Composable RowScope.(T) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        cards.forEach { card(it) }
        repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** The middle of an empty screen: a loading indicator while the first refresh runs, else a line; scrollable, so it still pulls to refresh. */
/**
 * A tab's [content], or while it is [empty] its [EmptyState]. Once there is something to show
 * it fades in quickly over the skeleton, which stays under it until it has, as Material 3
 * ends a skeleton loader.
 */
@Composable
fun LoadedContent(empty: Boolean, loading: Boolean, message: Int, content: @Composable () -> Unit) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        empty,
        // Emptied, the list fades out before the message fades in, rather than the two showing at once.
        transitionSpec = {
            if (targetState && !initialState) fadeIn(tween(EMPTY_FADE_IN_MS, delayMillis = EMPTY_FADE_OUT_MS)) togetherWith fadeOut(tween(EMPTY_FADE_OUT_MS))
            else fadeIn(fade) togetherWith ExitTransition.KeepUntilTransitionsFinished
        },
        label = "loaded",
    ) { nothing ->
        // On the page's own colour, so as it fades in it covers the skeleton under it whole,
        // rather than the skeleton's cards showing through until they are taken away at once.
        if (nothing) EmptyState(loading, message) else Box(Modifier.fillMaxSize().background(LocalSurfaceLevel.current.fill())) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
/**
 * A tab with nothing to show yet: while it loads, cards drawn as their shapes, pulsing
 * softly, as Material 3 keeps a layout steady with skeleton loaders rather than letting the
 * content pop in; once loaded and still empty, [message].
 */
@Composable
fun EmptyState(loading: Boolean, message: Int) {
    if (loading) {
        SkeletonCards()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * A few cards' shapes, a photo, a subhead and two lines of words each, pulsing while what
 * they stand for loads, the pulse moving from the top start to the bottom end as Material 3
 * has a skeleton's; still, with the phone's animations removed.
 */
@Composable
private fun SkeletonCards() {
    // How far the pulse has gone, 0 to 1, or null with the phone's animations removed.
    val sweep = if (LocalReducedMotion.current) {
        null
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SKELETON_SWEEP_MS, easing = LinearEasing)),
            label = "skeleton sweep",
        )
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bone = MaterialTheme.colorScheme.surfaceContainerHighest
    val loading = stringResource(R.string.empty_loading)
    PageWidth { columns ->
        Column(
            Modifier.fillMaxSize().padding(horizontal = columns.margin).padding(Space.gutter)
                .skeletonSweep(sweep, rtl)
                .clearAndSetSemantics { contentDescription = loading }
                .testTag("skeleton"),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            repeat(3) {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(LocalSurfaceLevel.current.raised.fill()).padding(bottom = Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(bone))
                    Box(Modifier.padding(horizontal = Space.l).fillMaxWidth(0.45f).height(14.dp).clip(Corners.full).background(bone))
                    Box(Modifier.padding(horizontal = Space.l).fillMaxWidth().height(16.dp).clip(Corners.full).background(bone))
                    Box(Modifier.padding(horizontal = Space.l).fillMaxWidth(0.7f).height(16.dp).clip(Corners.full).background(bone))
                }
            }
        }
    }
}

/**
 * The pulse as a band across the skeleton at 45°, full where it is and faint either side,
 * crossing from the top start corner to the bottom end one and starting over off the edge,
 * where the whole skeleton is faint, so it starts over without a jump.
 */
private fun Modifier.skeletonSweep(sweep: State<Float>?, rtl: Boolean): Modifier =
    if (sweep == null) this
    else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        // A point's place along the diagonal is half of x and y together, so the band runs
        // from before 0 to past the far corner's (width + height) / 2.
        val far = (size.width + size.height) / 2
        val band = far * SKELETON_BAND
        val middle = -band + (far + band * 2) * sweep.value
        val faint = Color.Black.copy(alpha = SKELETON_FAINT)
        val start = Offset(if (rtl) size.width - (middle - band) else middle - band, middle - band)
        val end = Offset(if (rtl) size.width - (middle + band) else middle + band, middle + band)
        drawRect(Brush.linearGradient(listOf(faint, Color.Black, faint), start, end), blendMode = BlendMode.DstIn)
    }

private const val SKELETON_SWEEP_MS = 1600
// Material's fade-through timings, for a list emptied.
private const val EMPTY_FADE_OUT_MS = 90
private const val EMPTY_FADE_IN_MS = 210
private const val SKELETON_BAND = 0.5f
private const val SKELETON_FAINT = 0.5f

/** Says when a refresh failed, as a snackbar in the app's host. */
@Composable
fun ShowRefreshErrors(container: AppContainer) {
    val show = rememberShowMessage()
    val error by container.repository.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(error) {
        error?.let { show(context.getString(R.string.message_refresh_failed, failureReason(context, it))) }
    }
}

/**
 * Pull to refresh that shows its indicator only for a refresh pulled for: the refreshes the
 * app makes by itself, on start, go on without it.
 */
class PulledRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/** A tab's list under its top app bar, pulled down to refresh, the indicator coming down from the bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabRefreshBox(pull: PulledRefresh, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = state, isRefreshing = pull.refreshing, modifier = Modifier.align(Alignment.TopCenter))
        },
        content = content,
    )
}

@Composable
fun rememberPulledRefresh(container: AppContainer): PulledRefresh {
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    var pulled by remember { mutableStateOf(false) }
    // Over once the refresh it started is.
    LaunchedEffect(syncing) { if (!syncing) pulled = false }
    return PulledRefresh(pulled) {
        pulled = true
        container.refresh()
    }
}
