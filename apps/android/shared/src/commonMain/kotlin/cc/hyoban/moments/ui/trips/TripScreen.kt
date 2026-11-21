package cc.hyoban.moments.ui.trips


import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.store.isPublic

import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.ledger.billsOf
import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.spec.Module
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
    val ledgerOn = LocalSettings.current.isOn(Module.LEDGER)
    // Its check-ins and its bills, in its time, read down among its Moments as the timeline has
    // them: a bill written with one of its Moments under that Moment, the rest by their time,
    // each day's spending on its header; the private check-ins out of sight in 演示模式.
    val allCheckins by container.repository.checkins.collectAsStateWithLifecycle()
    val checkinsOn = LocalSettings.current.isOn(Module.CHECKIN)
    val demo = LocalSettings.current.demo
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    // The car's charges in its time and what it drove each of its days, as the timeline has them.
    val records by container.repository.records.collectAsStateWithLifecycle()
    val carOn = LocalSettings.current.isOn(Module.CAR)
    val tripRecords = remember(allCheckins, trip, checkinsOn, demo, ledger, ledgerOn, moments, records, carOn) {
        val checkins = if (!checkinsOn) emptyMap() else allCheckins.byDay.filterKeys { it in trip.start..trip.end }
            .mapValues { (_, day) -> day.filter { cc.hyoban.moments.data.trips.isInTrip(trip, it.at) && !(demo && it.private) } }
            .filterValues { it.isNotEmpty() }
        val currency = ledger.book?.currency ?: "CNY"
        val shown = ledger.books.filterNot { it.archived }.mapTo(HashSet()) { it.id }
        val uids = moments.mapTo(HashSet()) { it.uid } + checkins.values.flatten().map { it.uid }
        val links = cc.hyoban.moments.ledger.AutoLinks(moments, checkins.values.flatten())
        val inSpan = if (!ledgerOn) emptyList() else {
            cc.hyoban.moments.ledger.txnsOfTrip(ledger, trip).filter { it.book in shown && it.kind in cc.hyoban.moments.ui.feed.SHOWN_KINDS }
        }
        // A public bill linked to one of its Moments or check-ins goes in that bubble whenever it
        // was paid; the trip's spending stays what was paid in its span.
        val bills = if (!ledgerOn) emptyList() else inSpan + run {
            val ids = inSpan.mapTo(HashSet()) { it.id }
            ledger.txns.filter {
                it.id !in ids && it.book in shown && it.kind in cc.hyoban.moments.ui.feed.SHOWN_KINDS &&
                    it.moment in uids && ledger.isPublic(it)
            }
        }
        val inTripDay = { day: String -> day in trip.start..trip.end }
        val charges = if (!carOn) emptyList() else cc.hyoban.moments.ui.feed.chargesOf(records.entries.values, inTripDay)
            .filter { cc.hyoban.moments.data.trips.isInTrip(trip, it.at) }
        val drives = if (!carOn) emptyMap() else cc.hyoban.moments.ui.feed.drivesOf(records.vehicleMonths.values, inTripDay)
        // A charge and the bill paid for it at its very time are one line, where that bill would go.
        val (looseCharges, linkedCharges, paid) = cc.hyoban.moments.ui.feed.placeCharges(
            charges, bills, { txn -> links.of(txn)?.takeIf { it in uids } },
            cc.hyoban.moments.ui.feed.momentTimes(moments, checkins.values.flatten()),
        )
        val (withMoment, without) = bills.filterNot { it.id in paid }.partition { links.of(it) in uids }
        cc.hyoban.moments.ui.feed.TimelineBills(
            ledger,
            linked = withMoment.groupBy { links.of(it)!! }.mapValues { (_, txns) -> txns.sortedBy { it.time } },
            loose = without.groupBy { it.dateKey },
            spent = inSpan.groupBy { it.dateKey }.mapValues { (_, txns) -> cc.hyoban.moments.ledger.spendingOf(ledger, txns, currency).expense },
            currency = currency,
            checkins = checkins,
            charges = looseCharges,
            linkedCharges = linkedCharges,
            drives = drives,
        )
    }
    val stops = remember(trip, all, places) { stopsOf(trip, all, trips, places) }
    // Each stop's way from the one before, across days too, as the timeline draws them.
    val tripCheckins = remember(allCheckins, trip, checkinsOn) {
        if (!checkinsOn) emptyList() else allCheckins.byDay.filterKeys { it in trip.start..trip.end }.values.flatten()
    }
    val legLines = rememberLegLines(container, moments, tripCheckins, listOf(trip))
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
            TripSubhead(trip, moments.size, places = stops.distinctBy { it.placeId }.size)
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
                        if (ledgerOn) item(key = "spending") { spending() }
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
                    if (ledgerOn) item(key = "spending") { spending() }
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
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val records by container.repository.records.collectAsStateWithLifecycle()
    val carOn = LocalSettings.current.isOn(Module.CAR)
    val spendingOn = LocalSettings.current.isOn(Module.LEDGER)
    val km = remember(route) {
        route.sumOf { segment ->
            segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) -> haversineKm(lat1, lng1, lat2, lng2) }.sum()
        }
    }
    val charges = remember(records, trip, carOn) {
        if (!carOn) emptyList() else records.entries.values.mapNotNull { entry ->
            val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
            if (charge == null || !cc.hyoban.moments.data.trips.isInTrip(trip, at)) null
            else (charge["energyWh"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        }
    }
    val currency = ledger.book?.currency ?: "CNY"
    val spent = remember(ledger, moments, trips, trip, spendingOn) {
        if (!spendingOn) return@remember null
        val bills = cc.hyoban.moments.ui.ledger.billsOf(SpendingOf.Trip(trip.id), ledger, moments.associateBy { it.uid }, trips)
        if (bills.isEmpty()) null else cc.hyoban.moments.ledger.spendingOf(ledger, bills, currency).expense
    }
    val figures = listOfNotNull(
        (km.takeIf { it >= 1 }?.let { "%,d".format(it.roundToInt()) to stringResource(Res.string.facet_km) })?.let { it to null },
        (charges.takeIf { it.isNotEmpty() }?.let { "%.0f".format(it.sum() / 1000) to pluralStringResource(Res.plurals.facet_charging, it.size, it.size) })?.let { it to null },
        (spent?.let { cc.hyoban.moments.ui.ledger.money(it, currency) to stringResource(Res.string.facet_spent) })?.let { it to onBills },
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
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(figure.first, style = AppTheme.typography.title, maxLines = 1)
                Text(figure.second, style = AppTheme.typography.caption, color = tokens.tx2)
            }
        }
    }
}

/** The great-circle distance between two points, in kilometres. */
fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLng / 2).let { it * it }
    return 2 * 6371 * kotlin.math.asin(kotlin.math.sqrt(a))
}
