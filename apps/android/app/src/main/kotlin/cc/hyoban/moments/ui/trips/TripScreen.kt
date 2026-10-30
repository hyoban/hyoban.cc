package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.TripMap
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt
import cc.hyoban.moments.ui.design.AppSwitch
import androidx.compose.foundation.selection.toggleable

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
    onEdit: (String) -> Unit = {},
) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val routes by container.tripRoutes.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    val stops = remember(trip, all, places) { stopsOf(trip, all, trips, places) }
    val context = LocalContext.current

    val dayLabel = { day: String -> context.getString(R.string.trip_day, daysBetween(trip.start, day)) + "\u2003" + formatDay(day) }
    // 第 n 天 at the end of each day's header.
    val dayNote = { day: String -> context.getString(R.string.trip_day, daysBetween(trip.start, day)) }
    // The title is the bar's; under it, what the trip covered.
    val summary: @Composable () -> Unit = {
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xs, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            TripSubhead(trip, moments.size, places = stops.distinctBy { it.placeId }.size)
            if (moments.isNotEmpty()) {
                cc.hyoban.moments.ui.design.Button(onClick = { onPlay(trip.id) }, modifier = Modifier.testTag("play_trip")) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(stringResource(R.string.action_play_trip))
                }
            }
        }
    }
    // What it cost, from the ledger kept on the phone, leading to its bills.
    val ledgerNav = LocalLedgerNav.current
    val spending: @Composable () -> Unit = {
        TripFacets(container, trip, routes[trip.id].orEmpty(), onBills = { ledgerNav?.list(LedgerFilter(trip = trip.id)) })
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
                    style = AppTheme.typography.titleSmall,
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
                TripMap(stops, routes[trip.id].orEmpty(), mapModifier.fillMaxWidth().clip(AppTheme.shapes.small))
            }
        }
    }

    // The page is the trip's card grown to the screen.
    // Played back along its route from the bar, once it has Moments to show.
    // The bar holds the map and editing; playing back is the page's one ink button, under the name.
    val actions: @Composable RowScope.() -> Unit = {
        Tooltip(showOnMap, below = true) {
            IconButton(onClick = { onMap(trip.id) }, modifier = Modifier.testTag("show_on_map")) {
                Icon(AppIcons.Map, contentDescription = showOnMap)
            }
        }
        val edit = stringResource(R.string.action_edit_trip)
        Tooltip(edit, below = true) {
            IconButton(onClick = { onEdit(trip.id) }, modifier = Modifier.testTag("edit_trip")) {
                Icon(AppIcons.Pen, contentDescription = edit)
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
                        dayNote = dayNote,
                        showTrip = false,
                        onDay = onDay,
                        chat = true,
                        chronological = true,
                    ) {
                        item(key = "summary") { summary() }
                        item(key = "spending") { spending() }
                        item(key = "track") { TrackShareRow(container, trip) }
                        if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(3) }
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
                    dayNote = dayNote,
                    showTrip = false,
                    onDay = onDay,
                    chat = true,
                    chronological = true,
                ) {
                    item(key = "summary") { summary() }
                    item(key = "spending") { spending() }
                    item(key = "track") { TrackShareRow(container, trip) }
                    if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(5) }
                    item(key = "map") { routeCard(Modifier, Modifier.height(240.dp)) }
                    item(key = "gap") { Spacer(Modifier.height(Space.s)) }
                }
            }
        }
    }
}

/**
 * Whether the trip's footprints are public: on, a span following the trip goes out with the
 * next publish, simplified, and the site draws the trip's route from it; off, it is taken back.
 */
@Composable
private fun TrackShareRow(container: AppContainer, trip: cc.hyoban.moments.data.spec.TripRecord) {
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    val collection = remember(trip) { cc.hyoban.moments.data.spec.Entries.collectionUid(trip) }
    val share by androidx.compose.runtime.produceState<cc.hyoban.moments.footprints.TrackShare?>(null, revision, collection) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.footprints.shares().firstOrNull { it.collection == collection && !it.removed }
        }
    }
    val scope = rememberCoroutineScope()
    val toggle = { on: Boolean ->
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val current = share
            if (on) {
                val zone = java.time.ZoneId.systemDefault()
                container.footprints.saveShare(
                    cc.hyoban.moments.footprints.TrackShare(
                        uid = current?.uid ?: cc.hyoban.moments.data.local.Ulid.next(),
                        start = java.time.LocalDate.parse(trip.start).atStartOfDay(zone).toInstant().toEpochMilli(),
                        end = java.time.LocalDate.parse(trip.end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
                        collection = collection,
                    ),
                )
            } else if (current != null) {
                container.footprints.removeShare(current.uid)
            }
        }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(share != null, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = toggle)
            .padding(horizontal = Space.gutter, vertical = Space.m)
            .testTag("trip_track_public"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(stringResource(R.string.trip_track_public), style = AppTheme.typography.body)
            Text(stringResource(R.string.trip_track_public_note), style = AppTheme.typography.bodySmall, color = tokens.tx2)
        }
        Spacer(Modifier.width(Space.m))
        AppSwitch(share != null)
    }
}

/**
 * What the trip came to, at most three figures in a row straight on the page, each in figures
 * over its caption: how far its route went, its charging, and what it cost, which leads to its
 * bills. A figure it has none of is left out.
 */
@Composable
private fun TripFacets(
    container: AppContainer,
    trip: cc.hyoban.moments.data.spec.TripRecord,
    route: List<cc.hyoban.moments.data.trips.RouteSegment>,
    onBills: () -> Unit,
) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val second by container.repository.second.collectAsStateWithLifecycle()
    val km = remember(route) {
        route.sumOf { segment ->
            segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) -> haversineKm(lat1, lng1, lat2, lng2) }.sum()
        }
    }
    val charges = remember(second, trip) {
        second.entries.values.mapNotNull { entry ->
            val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
            if (charge == null || at.take(10) < trip.start || at.take(10) > trip.end) null
            else (charge["energyWh"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        }
    }
    val currency = ledger.book?.currency ?: "CNY"
    val spent = remember(ledger, moments, trips, trip) {
        val bills = cc.hyoban.moments.ui.ledger.billsOf(SpendingOf.Trip(trip.id), ledger, moments.associateBy { it.uid }, trips)
        if (bills.isEmpty()) null else cc.hyoban.moments.ledger.spendingOf(ledger, bills, currency).expense
    }
    val figures = listOfNotNull(
        (km.takeIf { it >= 1 }?.let { "%,d".format(it.roundToInt()) to stringResource(R.string.facet_km) })?.let { it to null },
        (charges.takeIf { it.isNotEmpty() }?.let { "%.0f".format(it.sum() / 1000) to stringResource(R.string.facet_charging, it.size) })?.let { it to null },
        (spent?.let { cc.hyoban.moments.ui.ledger.money(it, currency) to stringResource(R.string.facet_spent) })?.let { it to onBills },
    )
    if (figures.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.l).testTag("trip_facets"),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        figures.forEach { (figure, onClick) ->
            Column(
                Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(figure.first, style = AppTheme.typography.numTitle, maxLines = 1)
                Text(figure.second, style = AppTheme.typography.caption, color = tokens.tx2)
            }
        }
    }
}

/** The great-circle distance between two points, in kilometres. */
private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLng / 2).let { it * it }
    return 2 * 6371 * kotlin.math.asin(kotlin.math.sqrt(a))
}
