package cc.hyoban.moments.ui.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.MediaTile
import cc.hyoban.moments.ui.components.Pill
import cc.hyoban.moments.ui.components.PressableCard
import cc.hyoban.moments.ui.components.RisingSheet
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.aboveSharedMedia
import cc.hyoban.moments.ui.components.mediaKey
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space

/** How far the page's sheet rises over the photos. */
private val RISE = 32.dp

/**
 * One Moment: its media edge to edge, and over their lower edge a sheet with when and
 * where, its words and the trip it was on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MomentScreen(
    container: AppContainer,
    uid: String,
    onMedia: (String, Int) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onBack: () -> Unit,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val trip = remember(moment, trips) { tripOf(moment, trips) }
    val urls = container.mediaUrls

    Box(Modifier.fillMaxSize().background(AppColors.page)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (moment.media.isNotEmpty()) {
                val pager = rememberPagerState { moment.media.size }
                Box(Modifier.fillMaxWidth().aspectRatio(shapeOf(moment.media.first()).coerceIn(0.75f, 1.5f))) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), key = { moment.media[it].uid }) { page ->
                        // The top of the photo is under the status bar and the back button.
                        MediaTile(
                            moment.media[page],
                            urls,
                            Modifier.fillMaxSize(),
                            liveBadgeAlignment = Alignment.BottomStart,
                            sharedKey = mediaKey(uid, moment.media[page].uid),
                            onClick = { onMedia(uid, page) },
                        )
                    }
                    if (moment.media.size > 1) {
                        PageDots(pager, Modifier.align(Alignment.BottomCenter).padding(bottom = RISE + Space.m))
                    }
                }
            } else {
                Spacer(Modifier.statusBarsPadding().height(72.dp))
            }

            RisingSheet(Modifier.offset(y = if (moment.media.isNotEmpty()) -RISE else 0.dp).aboveSharedMedia()) {
                Column(
                    Modifier.padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    Text(formatDay(moment.occurredAt), style = MaterialTheme.typography.headlineMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        formatTime(moment.occurredAt)?.let {
                            Pill(
                                it,
                                icon = Icons.Rounded.Schedule,
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        moment.places.forEach { id ->
                            Pill(places[id]?.name ?: id, icon = Icons.Rounded.Place, modifier = Modifier.clip(Corners.pill).clickable { onPlace(id) })
                        }
                    }
                    if (moment.text.isNotBlank()) {
                        SelectionContainer {
                            Text(moment.text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp))
                        }
                    }
                    trip?.let { trip ->
                        val cover = remember(trip, moments) { coversOf(trip, moments, trips, limit = 1).firstOrNull() }
                        val count = remember(trip, moments) { momentsOf(trip, moments, trips).size }
                        PressableCard(onClick = { onTrip(trip.id) }, modifier = Modifier.padding(top = Space.s)) {
                            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                cover?.let { Thumbnail(it, urls, size = 64) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Rounded.Luggage, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                        Text(trip.title, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(
                                        listOf(
                                            formatRange(trip.start, trip.end),
                                            stringResource(R.string.count_days, daysBetween(trip.start, trip.end)),
                                            stringResource(R.string.count_moments, count),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = Space.s),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart).aboveSharedMedia())
    }
}

@Composable
private fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(pager.pageCount) { page ->
            Box(
                Modifier.size(if (page == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (page == pager.currentPage) Color.White else Color.White.copy(alpha = 0.5f)),
            )
        }
    }
}
