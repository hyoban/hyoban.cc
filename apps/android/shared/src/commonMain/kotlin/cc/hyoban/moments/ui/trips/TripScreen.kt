package cc.hyoban.moments.ui.trips


import cc.hyoban.moments.data.local.fix

import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.ledger.billsOf
import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.plugins.Plugins
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.RailSpace
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.strong
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
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
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
import cc.hyoban.moments.ui.feed.MomentFeed
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt

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
    /** Writes a Moment into the trip's plan; none where the device writes no Moments. */
    onWritePlan: ((String) -> Unit)? = null,
    /** Talks the plan over with the model; none where the device writes no Moments. */
    onPlanChat: ((String) -> Unit)? = null,
) {
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val all by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val routes by container.tripRoutes.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    // Its check-ins, bills and the car's charges, in its time, read down among its Moments as the
    // timeline has them, each module joining its own; the private check-ins out of sight in 演示模式.
    val demo = LocalSettings.current.demo
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    val joins = cc.hyoban.moments.ui.feed.rememberJoinInputs(container)
    val tripRecords = remember(trip, moments, demo, joins) {
        cc.hyoban.moments.ui.feed.joinRecords(cc.hyoban.moments.ui.feed.JoinScope(cc.hyoban.moments.ui.feed.JoinSpan.Trip(trip), moments, demo = demo), joins).build()
    }
    val stops = remember(trip, all, places) { stopsOf(trip, all, trips, places) }
    // Each stop's way from the one before, across days too, as the timeline draws them.
    val legSettings = LocalSettings.current
    val legLines = rememberLegLines(container, moments, Plugins.legStops(container, trip) { legSettings.isOn(it) }, listOf(trip))
    // The footprints between its records, as the timeline has them, each saying how the way was gone.
    val tracks = cc.hyoban.moments.ui.feed.rememberTimelineTracks(container)

    val dayLabel = { day: String -> textOf(Res.string.trip_day, daysBetween(trip.start, day)) + " " + formatDay(day) }
    // 第 n 天 at the end of each day's header.
    val dayNote = { day: String -> textOf(Res.string.trip_day, daysBetween(trip.start, day)) }
    // Whether it stays off the site, said as a property, not a switch, which is in 编辑旅程.
    val tripPrivate = remember(trips, trip.id) { container.repository.isTripPrivate(trip.id) }
    val privateTag = if (tripPrivate) stringResource(Res.string.visibility_private) else null
    // The title is the bar's; under it, what the trip covered.
    val sideways = isPhoneLandscape()
    val summary: @Composable () -> Unit = {
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xs, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            // On its side the bar's middle stands over the map's edge, so the name heads the column instead.
            if (sideways) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(trip.title, style = AppTheme.typography.title, modifier = Modifier.alignByBaseline().semantics { heading() })
                    privateTag?.let { PrivateTag(it, Modifier.alignByBaseline()) }
                }
            }
            // A plan's own page shows it is one by its dashed bubbles and 写计划.
            TripSubhead(trip, moments.size, places = stops.distinctBy { it.placeId }.size, planned = false)
            // Upright the name is the bar's, so what stays private sits beside the page's one button.
            val tagHere = privateTag.takeIf { !sideways }
            // A plan's one ink button writes into it; a trip's plays back what happened.
            val writePlan = onWritePlan?.takeIf { trip.planned == true }
            val playable = moments.any { it.planned != true }
            if (writePlan != null || playable || tagHere != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    if (writePlan != null) {
                        cc.hyoban.moments.ui.design.Button(onClick = { writePlan(trip.id) }, modifier = Modifier.testTag("write_plan")) {
                            Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text(stringResource(Res.string.trip_write_plan))
                        }
                        // Beside it, with a model set in 同步与来源, talking the plan over.
                        onPlanChat?.takeIf { LocalSettings.current.aiReady }?.let { chat ->
                            cc.hyoban.moments.ui.design.TextButton(onClick = { chat(trip.id) }, modifier = Modifier.testTag("plan_chat_open")) {
                                Text(stringResource(Res.string.plan_chat_title))
                            }
                        }
                    } else if (playable) {
                        cc.hyoban.moments.ui.design.Button(onClick = { onPlay(trip.id) }, modifier = Modifier.testTag("play_trip")) {
                            Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text(stringResource(Res.string.action_play_trip))
                        }
                    }
                    tagHere?.let { PrivateTag(it) }
                }
            }
        }
    }
    // Its figures, what the modules that are on add after its distance (the charging, what it cost,
    // leading to its bills), while any of them adds one.
    val settings = LocalSettings.current
    val figuresOn = cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.tripFacets, settings::isOn).isNotEmpty()
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
                    stringResource(Res.string.section_photo) + " " + photos.size,
                    style = AppTheme.typography.body.strong,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val see = stringResource(Res.string.action_see_moments)
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
                    val label = stringResource(Res.string.media_position, stringResource(media.kindLabel()), index + 1, photos.size)
                    MediaStill(
                        media,
                        container.mediaUrls,
                        Modifier.width(188.dp).fillMaxHeight().clip(AppTheme.shapes.large)
                            .clickable(onClickLabel = stringResource(Res.string.action_open_moment)) { onMoment(moment.uid) }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
    // The route as a card of its own, headed like the photo panel's sections; tapped, the
    // route on the map tab, lifted among the others.
    val showOnMap = stringResource(Res.string.action_show_on_map)
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
                SectionHeader(AppIcons.Route, stringResource(Res.string.section_route))
                // The map's corners the card's less its padding, so the two look as round.
                cc.hyoban.moments.ui.LocalDevice.current.TripMap(stops, routes[trip.id].orEmpty(), mapModifier.fillMaxWidth().clip(AppTheme.shapes.medium))
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
        val edit = stringResource(Res.string.action_edit_trip)
        Tooltip(edit, below = true) {
            IconButton(onClick = { onEdit(trip.id) }, modifier = Modifier.testTag("edit_trip")) {
                Icon(AppIcons.Pen, contentDescription = edit)
            }
        }
    }
    SubPage(trip.title, onBack, Modifier.background(AppColors.page), actions = actions, centre = if (sideways) { {} } else null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (sideways) {
                // On a phone on its side, the route down the half on the camera's side, edge to edge
                // and the whole height, and what the trip covered and its Moments scroll beside it (H11).
                cc.hyoban.moments.ui.components.MediaBeside(media = { half -> cc.hyoban.moments.ui.LocalDevice.current.TripMap(stops, routes[trip.id].orEmpty(), half) }) { half ->
                    Box(half) {
                    CompositionLocalProvider(LocalRailSpace provides RailSpace(0.dp, 0.dp, clearOfInsets = true)) {
                    MomentFeed(
                        moments,
                        places,
                        trips,
                        container.mediaUrls,
                        onMoment,
                        modifier = Modifier.fillMaxSize(),
                        state = feedState,
                        months = false,
                        dayLabel = dayLabel,
                        dayNote = dayNote,
                        showTrip = false,
                        onDay = onDay,
                        chat = true,
                        chronological = true,
                        bills = tripRecords,
                        legs = legLines,
                        tracks = tracks,
                    ) {
                        item(key = "summary") { summary() }
                        if (figuresOn) item(key = "spending") { spending() }
                        if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(3) }
                    }
                    }
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
                    bills = tripRecords,
                    legs = legLines,
                        tracks = tracks,
                ) {
                    item(key = "summary") { summary() }
                    if (figuresOn) item(key = "spending") { spending() }
                    if (photos.isNotEmpty()) item(key = "photos") { photoCarousel(5) }
                    item(key = "map") { routeCard(Modifier, Modifier.height(240.dp)) }
                    item(key = "gap") { Spacer(Modifier.height(Space.s)) }
                }
            }
        }
    }
}

/** What of a trip stays private, as a small label in the caption's grey. */
@Composable
private fun PrivateTag(text: String, modifier: Modifier = Modifier) {
    Row(modifier.testTag("trip_private_tag"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Icon(AppIcons.EyeOff, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(14.dp))
        Text(text, style = AppTheme.typography.caption, color = tokens.tx2, maxLines = 1)
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
    val km = remember(route) {
        route.sumOf { segment ->
            segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) -> haversineKm(lat1, lng1, lat2, lng2) }.sum()
        }
    }
    // The distance, then what the modules that are on add: the charging, what was spent.
    val settings = LocalSettings.current
    val scope = TripFacetScope(container, trip, onBills)
    val own = cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.tripFacets, settings::isOn).sortedBy { it.order }.mapNotNull { it.figure(scope) }
    val figures = listOfNotNull(
        (km.takeIf { it >= 1 }?.let { "%,d".format(it.roundToInt()) to stringResource(Res.string.facet_km) })?.let { it to null },
    ) + own.map { (it.value to it.label) to it.onClick }
    if (figures.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.l).testTag("trip_facets"),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        figures.forEach { (figure, onClick) ->
            Column(
                Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(figure.first, style = AppTheme.typography.title, maxLines = 1)
                Text(figure.second, style = AppTheme.typography.caption, color = tokens.tx2)
            }
        }
    }
}

/** The great-circle distance between two points, in kilometres. */
fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
    cc.hyoban.moments.data.local.greatCircleMetres(lat1, lng1, lat2, lng2) / 1000
