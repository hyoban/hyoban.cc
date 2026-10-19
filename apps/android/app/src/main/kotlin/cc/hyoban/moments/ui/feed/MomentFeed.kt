package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import cc.hyoban.moments.ui.format.formatDayOfMonth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.theme.Space

/**
 * Moments as cards, a large header where a month starts and a pill for each day that
 * floats over its Moments as they scroll; [leading] goes above the first. [dayLabel]
 * says what a day's pill reads.
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
    months: Boolean = true,
    dayLabel: (String) -> String = ::formatDayOfMonth,
    showTrip: Boolean = true,
    leading: LazyListScope.() -> Unit = {},
) {
    val days = remember(moments) { moments.groupBy { it.dateKey }.toList() }
    val bottom = LocalBottomClearance.current + Space.xl

    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom)) {
        leading()
        var month: String? = null
        days.forEach { (day, dayMoments) ->
            if (months && day.take(7) != month) {
                month = day.take(7)
                val (name, year) = formatMonth(day)
                item(key = "month-${day.take(7)}", contentType = "month") { MonthHeader(name, year) }
            }
            stickyHeader(key = "day-$day", contentType = "day") {
                DayPill(dayLabel(day) + if (dayMoments.size > 1) " · ${stringResource(R.string.count_moments, dayMoments.size)}" else "")
            }
            items(dayMoments, key = { it.uid }, contentType = { "moment" }) { moment ->
                MomentCard(
                    moment,
                    places,
                    trip = if (showTrip) tripOf(moment, trips) else null,
                    urls = urls,
                    onOpen = { onMoment(moment.uid) },
                    onOpenMedia = { onMedia(moment.uid, it) },
                    modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 6.dp),
                )
            }
        }
    }
}

/** The middle of an empty screen: a spinner while the first refresh runs, else a line; scrollable, so it still pulls to refresh. */
@Composable
fun EmptyState(loading: Boolean, message: Int) {
    LazyColumn(Modifier.fillMaxSize()) {
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
