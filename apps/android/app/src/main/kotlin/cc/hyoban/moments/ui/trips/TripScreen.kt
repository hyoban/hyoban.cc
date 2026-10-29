package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.stopsOf
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.SectionHeader
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.kindLabel
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.feed.MomentFeed
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.ledger.SpendingCard
import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.TripMap
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.launch

/**
 * One trip, a pushed page under its top app bar: what it covered, its route on a map
 * card, then its Moments day by day, in order, each day's pill held under the bar.
 */
@Composable
fun TripScreen(
    container: AppContainer,
    id: String,
    onMoment: (String) -> Unit,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    onDay: (String) -> Unit = {},
    onMap: (String) -> Unit = {},
) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val routes by container.routes.routes.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    val stops = remember(trip, all, places) { stopsOf(trip, all, trips, places) }
    val context = LocalContext.current

    val dayLabel = { day: String -> context.getString(R.string.trip_day, daysBetween(trip.start, day)) + "\u2003" + formatDay(day) }
    // The title is the bar's; under it, what the trip covered.
    val summary: @Composable () -> Unit = {
        Box(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xs, bottom = Space.l)) {
            TripSubhead(trip, moments.size, places = stops.distinctBy { it.placeId }.size)
        }
    }
    // What it cost, from the ledger kept on the phone, leading to its bills.
    val ledgerNav = LocalLedgerNav.current
    val spending: @Composable () -> Unit = {
        SpendingCard(container, SpendingOf.Trip(trip.id), onOpen = { ledgerNav?.list(LedgerFilter(trip = trip.id)) }, Modifier.padding(bottom = Space.l))
    }
    // The trip's photos in a row, each the same size in rounded corners, snapping to its start
    // as they're swiped; each opens its Moment. Headed with an arrow pointing
    // down to the Moments, which it scrolls to on this page, so all of them can be seen without swiping sideways, as a carousel on a
    // page that scrolls needs.
    val feedState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val photos = remember(moments) { moments.flatMap { moment -> moment.media.filter { it.type != MediaType.VIDEO }.map { moment to it } } }
    val photoCarousel: @Composable (Int) -> Unit = { momentsFrom ->
        Column(Modifier.padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(Modifier.padding(start = Space.gutter, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.section_photo) + "\u2003" + photos.size,
                    style = AppTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val see = stringResource(R.string.action_see_moments)
                Tooltip(see) {
                    IconButton(onClick = { scope.launch { feedState.animateScrollToItem(momentsFrom) } }, modifier = Modifier.testTag("see_moments")) {
                        Icon(AppIcons.ArrowDown, contentDescription = see)
                    }
                }
            }
            val list = rememberLazyListState()
            LazyRow(
                Modifier.fillMaxWidth().height(208.dp).testTag("trip_photos"),
                state = list,
                contentPadding = PaddingValues(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                flingBehavior = rememberSnapFlingBehavior(list),
            ) {
                items(photos.size) { index ->
                    val (moment, media) = photos[index]
                    val label = stringResource(R.string.media_position, stringResource(media.kindLabel()), index + 1, photos.size)
                    MediaStill(
                        media,
                        container.mediaUrls,
                        Modifier.width(188.dp).fillMaxHeight().clip(AppTheme.shapes.large)
                            .clickable(onClickLabel = stringResource(R.string.action_open_moment)) { onMoment(moment.uid) }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
    // The route as a card of its own, headed like the photo panel's sections; tapped, the
    // route on the map tab, lifted among the others.
    val showOnMap = stringResource(R.string.action_show_on_map)
    val routeCard: @Composable (Modifier, Modifier) -> Unit = { modifier, mapModifier ->
        val level = LocalSurfaceLevel.current.raised
        Surface(
            onClick = { onMap(trip.id) },
            shape = AppTheme.shapes.medium,
            color = level.fill(),
            modifier = modifier.padding(horizontal = Space.gutter).fillMaxWidth()
                .semantics { onClick(label = showOnMap) { onMap(trip.id); true } }.testTag("trip_route"),
        ) {
            Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SectionHeader(AppIcons.Route, stringResource(R.string.section_route))
                // The map's corners the card's less its padding, so the two look as round.
                TripMap(stops, routes[trip.id].orEmpty(), mapModifier.fillMaxWidth().clip(AppTheme.shapes.extraSmall))
            }
        }
    }

    // The page is the trip's card grown to the screen.
    // Played back along its route from the bar, once it has Moments to show.
    val actions: @Composable RowScope.() -> Unit = {
        Tooltip(showOnMap, below = true) {
            IconButton(onClick = { onMap(trip.id) }, modifier = Modifier.testTag("show_on_map")) {
                Icon(AppIcons.Map, contentDescription = showOnMap)
            }
        }
        if (moments.isNotEmpty()) {
            val play = stringResource(R.string.action_play_trip)
            Tooltip(play, below = true) {
                IconButton(onClick = { onPlay(trip.id) }, modifier = Modifier.testTag("play_trip")) {
                    Icon(AppIcons.Play, contentDescription = play)
                }
            }
        }
    }
    SubPage(trip.title, onBack, Modifier.sharedCard(tripCardKey(id), page = true).background(AppColors.page), actions = actions) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (landscape) {
                // On its side, the route stays on the start side, as tall as the space under
                // the bar, and what the trip covered and its Moments scroll beside it, its top
                // level with what the trip covered.
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(0.42f).fillMaxHeight().sideInsetsPadding(WindowInsetsSides.Start)
                            .navigationBarsPadding().padding(top = Space.xs, bottom = Space.l),
                    ) {
                        routeCard(Modifier.weight(1f), Modifier.weight(1f))
                    }
                    MomentFeed(
                        moments,
                        places,
                        trips,
                        container.mediaUrls,
                        onMoment,
                        modifier = Modifier.weight(0.58f),
                        state = feedState,
                        months = false,
                        dayLabel = dayLabel,
                        showTrip = false,
                        onDay = onDay,
                    ) {
                        item(key = "summary") { summary() }
                        item(key = "spending") { spending() }
                        if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(2) }
                    }
                }
            } else {
                MomentFeed(
                    moments,
                    places,
                    trips,
                    container.mediaUrls,
                    onMoment,
                    state = feedState,
                    months = false,
                    dayLabel = dayLabel,
                    showTrip = false,
                    onDay = onDay,
                ) {
                    item(key = "summary") { summary() }
                    item(key = "spending") { spending() }
                    if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(4) }
                    item(key = "map") { routeCard(Modifier, Modifier.height(240.dp)) }
                    item(key = "gap") { Spacer(Modifier.height(Space.s)) }
                }
            }
        }
    }
}
