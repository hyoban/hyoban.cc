package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.countOf
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.PressableCard
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.feed.CardRow
import cc.hyoban.moments.ui.feed.LoadedContent
import cc.hyoban.moments.ui.feed.TabRefreshBox
import cc.hyoban.moments.ui.feed.rememberPulledRefresh
import cc.hyoban.moments.ui.feed.ShowRefreshErrors
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.theme.Space

/** The trips tab: every trip, newest first, each a card led by its photos. */
@Composable
fun TripCard(trip: TripRecord, covers: List<MomentMedia>, count: Int, urls: MediaUrls, onClick: () -> Unit, modifier: Modifier) {
    // It grows into the trip's page when opened.
    PressableCard(onClick = onClick, modifier = modifier.fillMaxWidth().sharedCard(tripCardKey(trip.id)).testTag("trip_${trip.id}")) {
        CoverCollage(covers, urls, Modifier)
        Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(trip.title, style = AppTheme.typography.title)
            TripSubhead(trip, count)
        }
    }
}

/** A trip's dates, its length, its count of Moments and of places when given, as a card's subhead in Material 3: one line of secondary text. */
@Composable
fun TripSubhead(trip: TripRecord, count: Int, places: Int? = null) {
    Text(
        (listOf(tripSummary(trip, count)) + listOfNotNull(places?.let { pluralStringResource(R.plurals.count_places, it, it) })).joinToString("\u2003"),
        style = AppTheme.typography.body,
        color = AppTheme.colorScheme.onSurfaceVariant,
    )
}

/** A trip's dates and its length, and its count of Moments when given, as one line. */
@Composable
fun tripSummary(trip: TripRecord, moments: Int? = null): String {
    val days = daysBetween(trip.start, trip.end)
    return listOfNotNull(
        formatRange(trip.start, trip.end),
        pluralStringResource(R.plurals.count_days, days, days),
        moments?.let { pluralStringResource(R.plurals.count_moments, it, it) },
    ).joinToString("\u2003")
}

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
