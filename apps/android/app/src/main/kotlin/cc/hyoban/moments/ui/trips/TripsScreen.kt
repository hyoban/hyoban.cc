package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.Trips
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.Pill
import cc.hyoban.moments.ui.components.PressableCard
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.feed.CardRow
import cc.hyoban.moments.ui.feed.EmptyState
import cc.hyoban.moments.ui.feed.TabRefreshBox
import cc.hyoban.moments.ui.feed.rememberPulledRefresh
import cc.hyoban.moments.ui.feed.rememberRefreshErrors
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.theme.Space

/** The trips tab: every trip, newest first, each a card led by its photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(container: AppContainer, onTrip: (String) -> Unit) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val pull = rememberPulledRefresh(container)
    val snackbar = rememberRefreshErrors(container)
    val days = remember(trips) { trips.sumOf { daysBetween(it.start, it.end) } }

    val list = rememberLazyListState()

    TabPage(
        stringResource(R.string.title_trips),
        tab = Trips,
        subtitle = stringResource(R.string.count_trips, trips.size) + " · " + stringResource(R.string.count_days_total, days),
    ) {
        TabRefreshBox(pull) {
            if (trips.isEmpty()) {
                EmptyState(loading = !loaded || syncing, message = R.string.empty_trips)
            } else {
                PageWidth { columns ->
                    OnTabReselect(Trips) { list.animateScrollToItem(0) }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(start = columns.margin, end = columns.margin, bottom = LocalBottomClearance.current + Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        // Two to a row where the width allows.
                        items(trips.chunked(columns.count), key = { it.first().id }) { row ->
                            CardRow(row, columns.count, Modifier.animateItem().padding(horizontal = Space.gutter)) { trip ->
                                val covers = remember(trip, moments) { coversOf(trip, moments, trips) }
                                val count = remember(trip, moments) { momentsOf(trip, moments, trips).size }
                                TripCard(
                                    trip,
                                    covers,
                                    count,
                                    container.mediaUrls,
                                    onClick = { onTrip(trip.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current))
    }
}

@Composable
private fun TripCard(trip: TripRecord, covers: List<MomentMedia>, count: Int, urls: MediaUrls, onClick: () -> Unit, modifier: Modifier) {
    // It grows into the trip's page when opened.
    PressableCard(onClick = onClick, modifier = modifier.fillMaxWidth().sharedCard(tripCardKey(trip.id))) {
        CoverCollage(covers, urls, Modifier)
        Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s + 2.dp)) {
            Text(trip.title, style = MaterialTheme.typography.titleLarge)
            TripPills(trip, count)
        }
    }
}

/** A trip's dates, its length and its count of Moments, as pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripPills(trip: TripRecord, count: Int, places: Int? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Pill(formatRange(trip.start, trip.end), icon = Icons.Rounded.CalendarMonth)
        Pill(stringResource(R.string.count_days, daysBetween(trip.start, trip.end)))
        Pill(stringResource(R.string.count_moments, count))
        places?.let { Pill(stringResource(R.string.count_places, it)) }
    }
}

/** A trip's dates and its length, and its count of Moments when given, as one line. */
@Composable
fun tripSummary(trip: TripRecord, moments: Int? = null): String =
    listOfNotNull(
        formatRange(trip.start, trip.end),
        stringResource(R.string.count_days, daysBetween(trip.start, trip.end)),
        moments?.let { stringResource(R.string.count_moments, it) },
    ).joinToString(" · ")

/** A trip's photos across the top of a card: one large and two small, or as many as it has. */
@Composable
private fun CoverCollage(covers: List<MomentMedia>, urls: MediaUrls, modifier: Modifier) {
    // Edge to edge across the top of the card, the photos meeting without gaps.
    val gap = Arrangement.spacedBy(0.dp)
    val tile = RectangleShape

    when (covers.size) {
        0 -> Unit
        1 -> MediaStill(covers[0], urls, modifier.fillMaxWidth().aspectRatio(16f / 9f))
        2 -> Row(modifier.fillMaxWidth().aspectRatio(16f / 9f), horizontalArrangement = gap) {
            covers.forEach { MediaStill(it, urls, Modifier.weight(1f).fillMaxHeight().clip(tile)) }
        }
        else -> Row(modifier.fillMaxWidth().aspectRatio(16f / 10f), horizontalArrangement = gap) {
            MediaStill(covers[0], urls, Modifier.weight(2f).fillMaxHeight().clip(tile))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                MediaStill(covers[1], urls, Modifier.weight(1f).fillMaxWidth().clip(tile))
                MediaStill(covers[2], urls, Modifier.weight(1f).fillMaxWidth().clip(tile))
            }
        }
    }
}
