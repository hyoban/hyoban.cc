package cc.hyoban.moments.ui.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.Dp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.READABLE_WIDTH
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import cc.hyoban.moments.ui.components.sideInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.components.sharedCard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.statusBars
import cc.hyoban.moments.ui.components.NeutralChip
import androidx.compose.material3.AssistChip
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
import cc.hyoban.moments.ui.components.mediaKey
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.format.formatTime
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

    // The whole page is the card it was opened from, grown to the screen.
    BoxWithConstraints(Modifier.fillMaxSize().sharedCard(uid, page = true).background(AppColors.page)) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val scroll = rememberScrollState()
        val details: @Composable () -> Unit = {
            MomentDetails(moment, places, trip, moments, trips, urls, onPlace, onTrip)
        }
        if (landscape && moment.media.isNotEmpty()) {
            // On its side, the photos fill the left half, up under the status bar, and the
            // words scroll beside them, rather than a photo taller than the screen over them.
            Row(Modifier.fillMaxSize()) {
                MomentPhotos(
                    moment,
                    urls,
                    onMedia,
                    Modifier.weight(1f).fillMaxHeight(),
                    dotsAbove = Space.m + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().verticalScroll(scroll).sideInsetsPadding(WindowInsetsSides.End)) { details() }
            }
        } else {
            // The photos are as tall as the tallest of them. A portrait one runs under the status
            // bar; landscape ones start below the bar, the page's colour above them. The bar's
            // icons follow what is under them.
            val shape = moment.media.minOfOrNull(::shapeOf)
            val bleeds = shape != null && shape < 1f

            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (moment.media.isNotEmpty()) {
                    // The tallest photo whole across the width, unless it would be taller than
                    // the screen, where it stops at the screen's height.
                    val above = if (bleeds) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    MomentPhotos(
                        moment,
                        urls,
                        onMedia,
                        Modifier.then(if (bleeds) Modifier else Modifier.statusBarsPadding())
                            .fillMaxWidth().height(minOf(screenWidth / shape!!, screenHeight - above)),
                        dotsAbove = RISE + Space.m,
                        whole = true,
                    )
                } else {
                    Spacer(Modifier.statusBarsPadding().height(72.dp))
                }

                RisingSheet(Modifier.offset(y = if (moment.media.isNotEmpty()) -RISE else 0.dp)) {
                    // Centred, and clear of the sides, on a phone on its side.
                    Box(Modifier.fillMaxWidth().sideInsetsPadding().wrapContentWidth().widthIn(max = READABLE_WIDTH)) { details() }
                }
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}

/**
 * The Moment's photos, swiped through, each opening the viewer; dots [dotsAbove] the bottom
 * when there are several. Each fills the space, or, when [whole], spans its width at its own
 * shape, centred, cropped only where it is taller than the space.
 */
@Composable
private fun MomentPhotos(moment: MomentRecord, urls: MediaUrls, onMedia: (String, Int) -> Unit, modifier: Modifier, dotsAbove: Dp, whole: Boolean = false) {
    val uid = moment.uid
    val pager = rememberPagerState { moment.media.size }
    Box(modifier) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { moment.media[it].uid }) { page ->
            val media = moment.media[page]
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // The top of the photo is under the status bar and the back button.
                MediaTile(
                    media,
                    urls,
                    if (whole) Modifier.fillMaxWidth().height(minOf(maxWidth / shapeOf(media), maxHeight)) else Modifier.fillMaxSize(),
                    liveBadgeAlignment = Alignment.BottomStart,
                    sharedKey = mediaKey(uid, media.uid),
                    onClick = { onMedia(uid, page) },
                )
            }
        }
        if (moment.media.size > 1) {
            PageDots(pager, Modifier.align(Alignment.BottomCenter).padding(bottom = dotsAbove))
        }
    }
}

/** When and where, the Moment's words and the trip it was on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentDetails(
    moment: MomentRecord,
    places: Map<String, Place>,
    trip: TripRecord?,
    moments: List<MomentRecord>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
) {
    Column(
        Modifier.padding(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Text(formatDay(moment.occurredAt), style = MaterialTheme.typography.headlineMedium)
        // The places are assist chips, each opening its place, with the 48dp a tap needs;
        // the time beside them is a label, centred on the chips' row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            formatTime(moment.occurredAt)?.let { Pill(it, icon = Icons.Rounded.Schedule) }
            moment.places.forEach { id ->
                NeutralChip(places[id]?.name ?: id, Icons.Rounded.Place, onClick = { onPlace(id) })
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
            PressableCard(onClick = { onTrip(trip.id) }, modifier = Modifier.padding(top = Space.s).sharedCard(tripCardKey(trip.id))) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    cover?.let { Thumbnail(it, urls, size = 64) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Luggage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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

@Composable
private fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 6.dp),
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
