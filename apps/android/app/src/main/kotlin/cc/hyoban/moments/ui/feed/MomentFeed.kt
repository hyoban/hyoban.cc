package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.components.DayPill
import cc.hyoban.moments.ui.components.MomentCard
import cc.hyoban.moments.ui.components.MonthHeader
import cc.hyoban.moments.ui.components.PageWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import cc.hyoban.moments.ui.format.formatDayOfMonth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.theme.Space

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
    FeedLeading().apply(leading).rows.forEach { (key, content) -> item(key = key) { content() } }
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
    onMedia: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    months: Boolean = true,
    dayLabel: (String) -> String = ::formatDayOfMonth,
    showTrip: Boolean = true,
    /** Room above the first row: the status bar, on a tab whose list runs up under it. */
    top: Dp = 0.dp,
    leading: FeedLeading.() -> Unit = {},
) {
    val days = remember(moments) { moments.groupBy { it.dateKey }.toList() }
    val bottom = LocalBottomClearance.current + Space.xl
    val card: @Composable (MomentRecord, Modifier, Boolean) -> Unit = { moment, cardModifier, showDate ->
        MomentCard(
            moment,
            places,
            trip = if (showTrip) tripOf(moment, trips) else null,
            urls = urls,
            onOpen = { onMoment(moment.uid) },
            onOpenMedia = { onMedia(moment.uid, it) },
            modifier = cardModifier,
            showDate = showDate,
        )
    }

    PageWidth(modifier) { columns ->
        if (columns.count > 1) {
            val rows = remember(leading) { FeedLeading().apply(leading).rows }
            // Headers run across both columns, out to the page's own edges.
            val across = Modifier.wide(Space.gutter)
            LazyVerticalStaggeredGrid(
                StaggeredGridCells.Fixed(columns.count),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = columns.margin + Space.gutter, top = top, end = columns.margin + Space.gutter, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalItemSpacing = Space.m,
            ) {
                rows.forEach { (key, content) ->
                    item(key = key, span = StaggeredGridItemSpan.FullLine) { Box(across) { content() } }
                }
                // A month at a time, or, where there are no months to head, a day at a time.
                val groups = if (months) byMonth(moments) else days
                groups.forEach { (group, here) ->
                    item(key = "group-$group", contentType = "group", span = StaggeredGridItemSpan.FullLine) {
                        Box(across) {
                            if (months) {
                                val (name, year) = formatMonth(here.first().dateKey)
                                MonthHeader(name, year)
                            } else {
                                DayPill(dayLabel(group) + if (here.size > 1) " · ${stringResource(R.string.count_moments, here.size)}" else "")
                            }
                        }
                    }
                    items(here, key = { it.uid }, contentType = { "moment" }) { moment ->
                        card(moment, Modifier.animateItem(), months)
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = columns.margin, top = top, end = columns.margin, bottom = bottom),
            ) {
                leadingRows(leading)
                var month: String? = null
                days.forEach { (day, dayMoments) ->
                    if (months && day.take(7) != month) {
                        month = day.take(7)
                        val (name, year) = formatMonth(day)
                        item(key = "month-${day.take(7)}", contentType = "month") { MonthHeader(name, year) }
                    }
                    stickyHeader(key = "day-$day", contentType = "day") {
                        // Over the cards it is held above, which come after it.
                        Box(Modifier.zIndex(1f).belowListTop(state, "day-$day")) {
                            DayPill(dayLabel(day) + if (dayMoments.size > 1) " · ${stringResource(R.string.count_moments, dayMoments.size)}" else "")
                        }
                    }
                    items(dayMoments, key = { it.uid }, contentType = { "moment" }) { moment ->
                        card(moment, Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 6.dp), false)
                    }
                }
            }
        }
    }
}

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

/** The middle of an empty screen: a spinner while the first refresh runs, else a line; scrollable, so it still pulls to refresh. */
@Composable
fun EmptyState(loading: Boolean, message: Int) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                    if (loading) CircularProgressIndicator()
                    Text(
                        stringResource(if (loading) R.string.empty_loading else message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** A snackbar host that says when a refresh failed. */
@Composable
fun rememberRefreshErrors(container: AppContainer): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val error by container.repository.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(context.getString(R.string.message_refresh_failed, it)) }
    }

    return snackbar
}

/**
 * Pull to refresh that shows its spinner only for a refresh pulled for: the refreshes the
 * app makes by itself, on start, go on without it.
 */
class PulledRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/**
 * A tab's list, pulled down to refresh, running up under the status bar as it scrolls,
 * the spinner coming down below the bar; the list leaves [statusBarTop] clear above its
 * first row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabRefreshBox(pull: PulledRefresh, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = pull.refreshing,
        onRefresh = pull.onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(state = state, isRefreshing = pull.refreshing, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding())
        },
        content = content,
    )
}

/** The status bar's height, for a list that runs up under it to leave clear above its first row. */
val statusBarTop: Dp
    @Composable get() = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

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
