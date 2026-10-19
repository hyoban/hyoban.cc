package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.calendar.CalendarView
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.PageHeader

/**
 * The Moments tab: every Moment the site shows, newest first, month by month, or as a
 * calendar of the months they're in, switched from the header. Pull down to bring them
 * up to date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(container: AppContainer, onMoment: (String) -> Unit, onMedia: (String, Int) -> Unit, onDay: (String) -> Unit) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val snackbar = rememberRefreshErrors(container)
    // The year and month the timeline is narrowed to, kept as two numbers to survive a rotation.
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var month by rememberSaveable { mutableStateOf<Int?>(null) }
    val filter = TimeFilter(year, month)
    val years = remember(moments) { yearsOf(moments) }
    val months = remember(moments, year) { year?.let { monthsOf(moments, it) }.orEmpty() }
    val shown = remember(moments, filter) { moments.filtered(filter) }
    val placeCount = remember(shown) { shown.flatMap { it.places }.toSet().size }
    val subtitle = if (shown.isEmpty()) null else
        stringResource(R.string.count_moments, shown.size) + " · " + stringResource(R.string.count_places, placeCount)
    var calendar by rememberSaveable { mutableStateOf(false) }
    // Each view keeps where it was scrolled to while the other shows.
    val feedState = rememberLazyListState()
    val calendarState = rememberLazyListState()
    // A new filter starts both views from the top; coming back to the tab doesn't.
    var lastFilter by rememberSaveable(stateSaver = TimeFilterSaver) { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            feedState.scrollToItem(0)
            calendarState.scrollToItem(0)
        }
    }
    val header: LazyListScope.() -> Unit = {
        item(key = "header") {
            PageHeader(stringResource(R.string.title_moments), subtitle) {
                IconButton(onClick = { calendar = !calendar }) {
                    Icon(
                        if (calendar) Icons.Outlined.ViewAgenda else Icons.Outlined.CalendarMonth,
                        contentDescription = stringResource(if (calendar) R.string.action_show_feed else R.string.action_show_calendar),
                    )
                }
            }
        }
        if (years.size > 1) {
            item(key = "filter") {
                TimeFilterRows(filter, years, months) { next ->
                    year = next.year
                    month = next.month
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = syncing, onRefresh = container::refresh, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (moments.isEmpty()) {
                EmptyState(loading = !loaded || syncing, message = R.string.empty_moments)
            } else {
                Crossfade(calendar, label = "feed or calendar") { showCalendar ->
                    if (showCalendar) {
                        CalendarView(
                            shown,
                            container.mediaUrls,
                            // One Moment opens on its own; more open as the day's list.
                            onDay = { date, here -> if (here.size == 1) onMoment(here.single().uid) else onDay(date) },
                            state = calendarState,
                            leading = header,
                        )
                    } else {
                        MomentFeed(shown, places, trips, container.mediaUrls, onMoment, onMedia, state = feedState, leading = header)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current))
    }
}

/** The Moments of one day, `YYYY-MM-DD`, as the feed shows them. */
@Composable
fun DayScreen(container: AppContainer, date: String, onMoment: (String) -> Unit, onMedia: (String, Int) -> Unit, onBack: () -> Unit) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, date) { all.filter { it.occurredAt.startsWith(date) } }

    Box(Modifier.fillMaxSize()) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, onMedia, Modifier.statusBarsPadding(), months = false) {
            // Room for the back button, which floats over the list.
            item(key = "back") { Spacer(Modifier.height(56.dp)) }
            item(key = "header") { PageHeader(formatDay(date), stringResource(R.string.count_moments, moments.size)) }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}

/** The Moments at one place, newest first. */
@Composable
fun PlaceScreen(container: AppContainer, id: String, onMoment: (String) -> Unit, onMedia: (String, Int) -> Unit, onBack: () -> Unit) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, id) { all.filter { id in it.places } }

    Box(Modifier.fillMaxSize()) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, onMedia, Modifier.statusBarsPadding()) {
            // Room for the back button, which floats over the list.
            item(key = "back") { Spacer(Modifier.height(56.dp)) }
            item(key = "header") {
                PageHeader(places[id]?.name ?: id, stringResource(R.string.count_moments, moments.size))
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}
