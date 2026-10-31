package cc.hyoban.moments.ui.map

import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.groupedShapes
import cc.hyoban.moments.ui.design.groupedRow
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.format.daysBetween
import androidx.compose.foundation.lazy.items
import cc.hyoban.moments.data.trips.countOf
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.ui.trips.TripCard
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import cc.hyoban.moments.ui.LocalSettings
import androidx.compose.foundation.layout.wrapContentHeight
import cc.hyoban.moments.ui.design.BottomSheetScaffold
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Card
import cc.hyoban.moments.ui.design.CardDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.LocalContentColor
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.SheetValue
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.design.rememberBottomSheetScaffoldState
import cc.hyoban.moments.ui.design.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.footprints.FootprintsOnMap
import cc.hyoban.moments.footprints.FootprintsMapState
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.Atlas
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.READABLE_WIDTH
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.minimumInteractiveComponentSize
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.trips.haversineKm
import java.time.LocalDate
import kotlin.math.roundToInt

/** What another page shows on the map tab: a place, with its panel up, or a trip's route, lifted with its card. */
sealed interface MapFocus {
    data class Place(val id: String) : MapFocus

    data class Trip(val id: String) : MapFocus

    data object Footprints : MapFocus
}

/**
 * The map tab, as the site's map: every place the Moments name, coloured by the year of
 * its first Moment, and every trip's route. Tap a place for its Moments in a panel, from which a map app shows the way there; tap a
 * route to lift it, its card floating over the panel to open or play it. A place or a trip
 * shown on it from its page, [focus], is chosen and flown to, then let go with [onFocused].
 */
@Composable
fun MapScreen(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onPlay: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    focus: MapFocus? = null,
    onFocused: () -> Unit = {},
    onFootprintSettings: () -> Unit = {},
) {
    FootprintsOnMap(container, onFootprintSettings) { footprints ->
        MapContent(container, onMoment, onPlace, onTrip, onPlay, onSearch, focus, onFocused, footprints)
    }
}

@Composable
private fun MapContent(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onPlay: (String) -> Unit,
    onSearch: () -> Unit,
    focus: MapFocus?,
    onFocused: () -> Unit,
    footprints: FootprintsMapState,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val routes by container.tripRoutes.collectAsStateWithLifecycle()
    val byPlace = remember(moments) {
        moments.flatMap { moment -> moment.places.distinct().map { it to moment } }.groupBy({ it.first }, { it.second })
    }
    val mapPlaces = remember(byPlace, places) {
        byPlace.mapNotNull { (id, here) -> places[id]?.let { MapPlace(id, it, here.size, here.minOf { moment -> yearOf(moment.occurredAt) }) } }
    }
    // The site's year colours as they are, in either mode, so a place is the same colour on the
    // phone as on the site's map: one of the design's two palette exceptions.
    val colors = remember(mapPlaces) { yearColors(mapPlaces.map { it.firstYear }) }
    val mapRoutes = remember(trips, routes) {
        trips.sortedByDescending { it.start }.mapNotNull { trip -> routes[trip.id]?.let { MapRoute(trip.id, it) } }
    }
    // How far each trip's route runs, for its row and the panel's total.
    val kilometres = remember(mapRoutes) {
        mapRoutes.associate { route ->
            route.id to route.segments.sumOf { segment -> segment.line.zipWithNext { (lng1, lat1), (lng2, lat2) -> haversineKm(lat1, lng1, lat2, lng2) }.sum() }
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }

    // The fog of war: the cities read from the app's assets the first time it's turned on, and
    // those a Moment was written in, the phone's own among them, lit.
    val settings = LocalSettings.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var fogRegions by remember { mutableStateOf<List<FogRegion>?>(null) }
    // Each toggle, swept on the map rather than set; a toggle while one sweeps turns it around.
    var fogSweep by remember { mutableIntStateOf(0) }
    LaunchedEffect(settings.mapFog) {
        if (settings.mapFog && fogRegions == null) {
            fogRegions = withContext(Dispatchers.Default) {
                context.assets.open(FOG_REGIONS_ASSET).bufferedReader().use(::parseFogRegions)
            }
        }
    }
    val fog = remember(fogRegions, mapPlaces) {
        fogRegions?.let { regions ->
            buildFogOfWar(regions, mapPlaces.map { it.place.longitude to it.place.latitude }, mapPlaces.map { it.count })
        }
    }

    // A trip shown from its page is lifted from the map's first frame, its card already up for
    // the page to shrink into, as going back from the trip into its card does.
    val lifted = (focus as? MapFocus.Trip)?.id ?: highlighted
    BackHandler(enabled = lifted != null) { highlighted = null }
    LaunchedEffect(focus) {
        when (focus) {
            is MapFocus.Place -> {
                highlighted = null
                selected = focus.id
            }
            is MapFocus.Trip -> {
                selected = null
                highlighted = focus.id
            }
            MapFocus.Footprints -> {
                selected = null
                highlighted = null
                footprints.open()
                onFocused()
            }
            null -> Unit
        }
    }

    // Locating: each request asks for the place once more; a ring turns while it waits.
    val show = rememberShowMessage()
    var locate by remember { mutableIntStateOf(0) }
    // Which way the map is turned, and each ask to turn it back to north.
    var bearing by remember { mutableDoubleStateOf(0.0) }
    var north by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    val startLocating: () -> Unit = {
        locating = true
        locate++
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            startLocating()
        } else {
            show(resources.getString(R.string.error_location_permission))
        }
    }
    val requestLocate: () -> Unit = {
        val granted = LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            startLocating()
        } else {
            permission.launch(LOCATION_PERMISSIONS)
        }
    }

    // On a wide window the panel is a column standing at the start, the map beside it (L01);
    // on a phone on its side the tabs stand down a rail and the panel stays at the foot.
    val wide = cc.hyoban.moments.ui.components.isExpandedWidth()
    val railed = wide || cc.hyoban.moments.ui.components.isLandscape()
    val rail = LocalRailSpace.current
    val clearance = LocalBottomClearance.current
    val toggleFog: () -> Unit = {
        fogSweep++
        settings.changeMapFog(!settings.mapFog)
    }
    val openFootprints: () -> Unit = { selected = null; highlighted = null; footprints.open() }
    val shownFog = fog.takeIf { settings.mapFog }

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false)
    var screenHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    Box(Modifier.fillMaxSize().onSizeChanged { screenHeight = it.height }) {
        PlacesMap(
            mapPlaces,
            colors,
            mapRoutes,
            selected,
            lifted,
            onPlace = { selected = it },
            onRoute = { highlighted = it },
            // A tap on nothing closes the place panel.
            onEmptyTap = { selected = null },
            onTrip = onTrip,
            modifier = Modifier.fillMaxSize(),
            locate = locate,
            north = north,
            onBearing = { bearing = it },
            footprints = footprints.drawing,
            footprintFrame = footprints.frame,
            frameFootprints = footprints.frameRequested,
            onFootprintsFramed = footprints.onFramed,
            onFootprint = openFootprints,
            tab = Atlas,
            focus = focus.takeUnless { it == MapFocus.Footprints },
            onFocused = onFocused,
            fog = shownFog,
            fogSweep = fogSweep,
            fogRegions = fogRegions.orEmpty(),
            onLocated = { found ->
                locating = false
                if (!found) show(resources.getString(R.string.error_location))
            },
        )
        // At the top: the search and the fog's switch on one line, the legend under them and,
        // while the fog is on, how much of it is lit. On a wide window the search is the panel's
        // and the rest stands over the map beside it; on a phone on its side, at the top of the
        // side away from the tabs.
        Column(
            Modifier
                .align(
                    when {
                        !railed || wide -> Alignment.TopStart
                        rail.left > 0.dp -> AbsoluteAlignment.TopRight
                        else -> AbsoluteAlignment.TopLeft
                    },
                )
                .statusBarsPadding().sideInsetsPadding()
                .padding(top = Space.s, start = if (wide) rail.left + WIDE_PANEL + MAP_EDGE else MAP_EDGE, end = MAP_EDGE)
                .then(if (railed && !wide) Modifier.widthIn(max = SIDE_CONTROLS) else Modifier),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (wide) {
                    Box(Modifier.weight(1f)) { if (colors.isNotEmpty()) Legend(colors, trips = mapRoutes.isNotEmpty()) }
                } else {
                    SearchPill(onSearch, Modifier.weight(1f), floating = true)
                }
                FogButton(settings.mapFog, toggleFog)
            }
            if (!wide && colors.isNotEmpty()) Legend(colors, trips = mapRoutes.isNotEmpty())
            AnimatedVisibility(
                shownFog != null,
                enter = expandVertically(AppTheme.motionScheme.fastSpatialSpec()) + fadeIn(AppTheme.motionScheme.fastEffectsSpec()),
                exit = shrinkVertically(AppTheme.motionScheme.fastSpatialSpec()) + fadeOut(AppTheme.motionScheme.fastEffectsSpec()),
            ) {
                var counted by remember { mutableStateOf(shownFog) }
                if (shownFog != null) counted = shownFog
                counted?.let { FogCount(it) }
            }
        }

        val trip = trips.firstOrNull { it.id == lifted }
        // Turned some way from north, by more than a nudge.
        val turned = bearing.let { minOf(it, 360 - it) } > 0.5
        // On a wide window, how far the buttons and the card stand from the foot of the map.
        val foot = Space.l
        // The buttons down the side: the compass while the map is turned, the footprints and
        // where the phone is, 12 above the panel's top as it rises and falls, and over the lifted
        // trip's card while it shows; past the place's half of the screen they fade away. Read as
        // they are placed, so the map's screen isn't composed again every frame they move.
        var card by remember { mutableStateOf(ROUTE_CARD) }
        val lift = animateDpAsState(if (trip != null) card + Space.m - FLOATING_INSET else 0.dp, AppTheme.motionScheme.defaultSpatialSpec(), label = "map buttons")
        val sheetTop: () -> Float? = { sheetState.top.takeUnless { wide || it.isNaN() || screenHeight == 0 }?.plus(statusTop) }
        Column(
            Modifier.align(Alignment.BottomEnd).then(if (wide) Modifier.navigationBarsPadding() else Modifier).sideInsetsPadding()
                .padding(end = MAP_EDGE - FLOATING_INSET, bottom = if (wide) foot else Space.m - FLOATING_INSET)
                .offset {
                    val above = sheetTop()?.let { (it - screenHeight).roundToInt() } ?: -(clearance + TRIPS_PEEK).roundToPx()
                    IntOffset(0, above - lift.value.roundToPx())
                }
                .graphicsLayer {
                    val top = sheetTop() ?: return@graphicsLayer
                    // Whole down to the place's panel, gone by the time the panel is a fifth higher.
                    val rest = screenHeight * (1f - PLACE_SHEET_SHARE)
                    alpha = ((top - rest * 0.8f) / (rest * 0.2f)).coerceIn(0f, 1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompassButton(turned, bearing, onClick = { north++ })
            FootprintsButton(footprints.recording, onClick = openFootprints)
            LocateButton(locating, onClick = requestLocate)
        }
        // Kept while the card leaves, after the route is let go.
        var shown by remember { mutableStateOf(trip) }
        if (trip != null) shown = trip
        val motion = AppTheme.motionScheme
        AnimatedVisibility(
            trip != null,
            enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
            exit = fadeOut(motion.defaultEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { it / 2 },
            // 12 above the panel's top, as the buttons are.
            modifier = Modifier.align(Alignment.BottomCenter).then(if (wide) Modifier.navigationBarsPadding() else Modifier).sideInsetsPadding()
                .padding(start = if (wide) rail.left + WIDE_PANEL + MAP_EDGE else MAP_EDGE, end = MAP_EDGE, bottom = if (wide) foot else Space.m)
                .offset { IntOffset(0, sheetTop()?.let { (it - screenHeight).roundToInt() } ?: -(clearance + TRIPS_PEEK).roundToPx()) }
                .widthIn(max = READABLE_WIDTH)
                .onSizeChanged { card = with(density) { it.height.toDp() } },
        ) {
            // One route's card giving way to another's as another is lifted.
            Crossfade(shown, animationSpec = motion.fastEffectsSpec(), label = "route card") { card ->
                card?.let {
                    val covers = remember(it, moments) { coversOf(it, moments, trips, limit = 1) }
                    RouteCard(it, covers.firstOrNull(), tripColor(it, colors), kilometres[it.id], container.mediaUrls, onOpen = { onTrip(it.id) }, onPlay = { onPlay(it.id) })
                }
            }
        }
    }

    // The panel at the map's foot: the trips, resting as a line of how many and how far and
    // dragged up to their list; a place chosen, its Moments in it instead, half the screen,
    // dragged up to all of it or down and away, back to the trips. Its handle is the panel's own,
    // tapped or focused as well as dragged. In the app's own window, so a trip or a Moment opened
    // from it grows out of its row.
    BackHandler(enabled = selected != null) { selected = null }
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(selected) { sheetState.partialExpand() }
    // Swiped away, a place is let go and the trips come back; the trips themselves stay resting.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect {
            if (it == SheetValue.Hidden) {
                if (selected != null) selected = null else sheetState.partialExpand()
            }
        }
    }
    val sheetContent: @Composable () -> Unit = {
        val id = selected
        val place = id?.let { places[it] }
        if (id == null || place == null) {
            TripsSheet(
                trips, moments, colors, kilometres, container.mediaUrls, bottom = if (wide) Space.l else clearance,
                // Resting, only its title line shows above the tabs; its rows come in as it's drawn up.
                shown = { if (wide) 1f else (sheetState.raised * TRIPS_REVEAL).coerceAtMost(1f) },
                onTrip = onTrip,
            )
        } else {
            val here = byPlace[id].orEmpty()
            val years = remember(here) { here.map { yearOf(it.occurredAt) }.distinct().sortedDescending() }
            PlaceSheet(
                place, id, here, years, colors, places, container.mediaUrls,
                bottom = if (wide) Space.l else clearance,
                onNavigate = { if (!navigateTo(context, place)) show(resources.getString(R.string.no_map_app)) },
                onAll = { selected = null; onPlace(id) },
                onMoment = onMoment,
            )
        }
    }
    if (wide) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = rail.left)
                .width(WIDE_PANEL)
                .background(AppColors.sheet)
                .statusBarsPadding()
                .padding(top = Space.l)
                .testTag("map_panel"),
        ) {
            SearchPill(onSearch, Modifier.padding(horizontal = MAP_EDGE).fillMaxWidth(), floating = false)
            Spacer(Modifier.height(Space.l))
            sheetContent()
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffold,
            // Drawn up to the status bar at most, so the whole sheet's top stays clear of it.
            modifier = Modifier.statusBarsPadding(),
            sheetPeekHeight = if (selected != null) maxHeight * PLACE_SHEET_SHARE else TRIPS_PEEK + clearance,
            sheetContainerColor = AppColors.sheet,
            containerColor = Color.Transparent,
            sheetContent = { sheetContent() },
        ) { }
    }
}

/** The colour of a trip's year on the map, when a place first visited that year has one. */
private fun tripColor(trip: TripRecord, colors: Map<Int, Color>): Color? = colors[LocalDate.parse(trip.start).year]

/**
 * A place in the map's panel: its pin in its year's colour, its name, how many Moments and in
 * which years, the way there; then its Moments, each its first photo, when and its words, and
 * all of the place at the list's foot, kept clear of the tabs by [bottom].
 */
@Composable
private fun PlaceSheet(
    place: Place,
    id: String,
    here: List<MomentRecord>,
    years: List<Int>,
    colors: Map<Int, Color>,
    places: Map<String, Place>,
    urls: MediaUrls,
    bottom: Dp,
    onNavigate: () -> Unit,
    onAll: () -> Unit,
    onMoment: (String) -> Unit,
) {
    val resources = LocalResources.current
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Icon(AppIcons.Pin, contentDescription = null, tint = colors[years.minOrNull()] ?: tokens.accent)
            Column(Modifier.weight(1f)) {
                Text(place.name, style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                Text(
                    (listOf(pluralStringResource(R.plurals.count_moments, here.size, here.size)) + years.joinToString(resources.getString(R.string.separator_list)) { resources.getString(R.string.year_item, it) }).joinToString(" "),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The way there beside the name, an action chip handing it to a map app.
            AssistChip(
                onClick = onNavigate,
                label = { Text(stringResource(R.string.action_navigate_short)) },
                leadingIcon = { Icon(AppIcons.Directions, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("navigate"),
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(top = Space.l, bottom = Space.l + bottom),
        ) {
            items(here, key = { it.uid }) { moment ->
                // It grows into the Moment's page when opened, as a card on a list does.
                Row(
                    Modifier.fillMaxWidth().sharedCard(moment.uid).clickable { onMoment(moment.uid) }
                        .padding(horizontal = Space.gutter, vertical = Space.xs).heightIn(min = 64.dp)
                        .testTag("moment_${moment.uid}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    val media = moment.media.firstOrNull()
                    if (media != null) {
                        Thumbnail(media, urls, size = 56)
                    } else {
                        Box(Modifier.size(56.dp).clip(AppTheme.shapes.medium).background(tokens.fill))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" "),
                            style = AppTheme.typography.caption,
                            color = tokens.tx3,
                        )
                        Text(
                            moment.text.ifBlank { moment.places.joinToString(stringResource(R.string.separator_list)) { places[it]?.name ?: it } },
                            style = AppTheme.typography.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // The place's own page, read on to after its Moments.
            item(key = "all") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onAll).padding(horizontal = Space.gutter).heightIn(min = 56.dp)
                        .testTag("place_$id"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.action_see_moments), style = AppTheme.typography.body, modifier = Modifier.weight(1f))
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** How wide the trips' column is on a wide window. */
private val WIDE_PANEL = 360.dp

/** How quickly the trips' rows come in as the panel is drawn up: whole by a quarter of the way. */
private const val TRIPS_REVEAL = 4f

/** How much of the panel of trips shows while it rests, above the tabs: its handle and its title line. */
private val TRIPS_PEEK = 68.dp

/** How wide the controls at the top stand on a phone on its side, clear of the map's middle. */
private val SIDE_CONTROLS = 400.dp

/** How far the map's controls stand from the screen's edges. */
private val MAP_EDGE = 16.dp

/** How far a 40 button's 48 target reaches past it on each side. */
private val FLOATING_INSET = 4.dp

/** How tall the lifted trip's card stands at least, its words on one line. */
private val ROUTE_CARD = 88.dp

/**
 * The trips in the map's panel: a line of how many and how far, which is all that shows while it
 * rests, then a row for each trip, newest first, growing into its page when tapped.
 */
@Composable
private fun TripsSheet(
    trips: List<TripRecord>,
    moments: List<MomentRecord>,
    colors: Map<Int, Color>,
    kilometres: Map<String, Double>,
    urls: MediaUrls,
    bottom: Dp,
    shown: () -> Float,
    onTrip: (String) -> Unit,
) {
    val total = remember(kilometres) { kilometres.values.sum() }
    // Hidden while the panel rests, its rows take no taps.
    val open by remember { derivedStateOf { shown() > 0.5f } }
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.s),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(stringResource(R.string.title_trips), style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
            Text(
                if (trips.isEmpty()) {
                    stringResource(R.string.trips_none)
                } else {
                    listOfNotNull(
                        pluralStringResource(R.plurals.count_trips, trips.size, trips.size),
                        total.takeIf { it >= 1 }?.let { stringResource(R.string.map_km, "%,d".format(it.roundToInt())) },
                    ).joinToString(" ")
                },
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).graphicsLayer { alpha = shown() },
            contentPadding = PaddingValues(top = Space.xs, bottom = Space.l + bottom),
        ) {
            items(trips.sortedByDescending { it.start }, key = { it.id }) { trip ->
                val covers = remember(trip, moments) { coversOf(trip, moments, trips, limit = 1) }
                TripRow(
                    trip, covers.firstOrNull(), tripColor(trip, colors), kilometres[trip.id], urls,
                    modifier = Modifier.fillMaxWidth().sharedCard(tripCardKey(trip.id)).clickable(enabled = open) { onTrip(trip.id) }
                        .padding(horizontal = Space.gutter, vertical = Space.s).testTag("trip_${trip.id}"),
                ) { Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/**
 * A trip as a row: its cover with its year's dot, its name, and when it began, how many days and
 * how far, then [trailing].
 */
@Composable
private fun TripRow(
    trip: TripRecord,
    cover: MomentMedia?,
    year: Color?,
    km: Double?,
    urls: MediaUrls,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    val days = daysBetween(trip.start, trip.end)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.size(64.dp).clip(AppTheme.shapes.medium).background(tokens.fill)) {
            if (cover != null) Thumbnail(cover, urls, size = 64)
            if (year != null) {
                Box(
                    Modifier.padding(6.dp).size(10.dp).border(1.5.dp, Color.White, CircleShape).padding(1.5.dp).background(year, CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(trip.title, style = AppTheme.typography.body.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    formatDate(trip.start),
                    pluralStringResource(R.plurals.count_days, days, days),
                    km?.takeIf { it >= 1 }?.let { stringResource(R.string.map_km, "%,d".format(it.roundToInt())) },
                ).joinToString(" "),
                style = AppTheme.typography.caption,
                color = tokens.tx2,
            )
        }
        trailing()
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Opens the search: a pill floating over the map, or set into the panel on a wide window. */
@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier, floating: Boolean) {
    Surface(
        onClick = onClick,
        shape = Corners.full,
        color = SurfaceLevel.Floating.fill(),
        contentColor = tokens.tx3,
        border = cardBorder(),
        shadowElevation = if (floating) ShadowElevation else 0.dp,
        modifier = modifier.height(44.dp).testTag("map_search"),
    ) {
        Row(
            Modifier.padding(start = Space.m, end = Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(AppIcons.Search, contentDescription = null)
            Text(stringResource(R.string.map_search_hint), style = AppTheme.typography.body, color = tokens.tx3, maxLines = 1)
        }
    }
}

/**
 * A round button floating over the map: a 40 circle on the floating surface with its hairline and
 * shadow, its touch target still 48; [on] fills it with the accent, as a switch turned on.
 */
@Composable
private fun MapButton(
    label: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    on: Boolean? = null,
    tint: Color = tokens.tx,
    content: @Composable () -> Unit,
) {
    // Filling and emptying as a switch turns, on the motion scheme's effects spring.
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(if (on == true) tokens.accent else SurfaceLevel.Floating.fill(), spec, label = "map button")
    val color by animateColorAsState(if (on == true) tokens.onAccent else tint, spec, label = "map button icon")
    Tooltip(label) {
        Box(modifier.minimumInteractiveComponentSize().size(48.dp), contentAlignment = Alignment.Center) {
            val inner = Modifier.size(40.dp).semantics { contentDescription = label }.testTag(tag)
            val box: @Composable () -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() } }
            if (on != null) {
                Surface(
                    checked = on, onCheckedChange = { onClick() }, shape = Corners.full, color = container, contentColor = color,
                    border = if (on) null else cardBorder(), shadowElevation = ShadowElevation, modifier = inner, content = box,
                )
            } else {
                Surface(
                    onClick = onClick, shape = Corners.full, color = container, contentColor = color,
                    border = cardBorder(), shadowElevation = ShadowElevation, modifier = inner, content = box,
                )
            }
        }
    }
}

/** Which way north is, shown while the map is [turned]; tapped, the map turns back to it. */
@Composable
private fun CompassButton(turned: Boolean, bearing: Double, onClick: () -> Unit) {
    // Growing along one axis rather than scaling, from the buttons below it.
    val motion = AppTheme.motionScheme
    AnimatedVisibility(
        turned,
        enter = expandVertically(motion.fastSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(motion.fastEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(motion.fastEffectsSpec()),
    ) {
        MapButton(stringResource(R.string.action_north), onClick, tag = "north", tint = tokens.accent) {
            Icon(AppIcons.Navigation, contentDescription = null, modifier = Modifier.rotate(-bearing.toFloat()))
        }
    }
}

/** Brings the map to where the phone is; a loading indicator while it finds out. */
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit) {
    // Named on the button, so it keeps its name while the indicator stands in for the icon.
    MapButton(stringResource(R.string.action_locate), onClick = { if (!locating) onClick() }, tag = "locate") {
        Crossfade(locating, animationSpec = AppTheme.motionScheme.fastEffectsSpec(), label = "locating") { busy ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) {
                    Spinner(Modifier.size(24.dp), color = LocalContentColor.current)
                } else {
                    Icon(AppIcons.Locate, contentDescription = null)
                }
            }
        }
    }
}

/** Opens the phone's own footprints; a dot in the accent while it's recording. */
@Composable
private fun FootprintsButton(recording: Boolean, onClick: () -> Unit) {
    val label = if (recording) stringResource(R.string.footprints_map_recording) else stringResource(R.string.footprints_title)
    MapButton(label, onClick, tag = "footprints") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Footprints, contentDescription = null)
            if (recording) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = 5.dp).size(10.dp)
                        .background(SurfaceLevel.Floating.fill(), CircleShape).padding(1.dp).background(tokens.accent, CircleShape),
                )
            }
        }
    }
}

/** Turns the fog of war on and off: a round switch, filled with the accent while it's on. */
@Composable
private fun FogButton(on: Boolean, onClick: () -> Unit) {
    MapButton(stringResource(R.string.action_fog), onClick, tag = "fog", on = on) {
        Icon(AppIcons.Fog, contentDescription = null)
    }
}

/** How many cities and provinces the fog leaves lit. */
@Composable
private fun FogCount(fog: FogOfWar) {
    FloatingPill {
        Text(
            stringResource(R.string.fog_lit, fog.cities, fog.provinces),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            modifier = Modifier.height(LEGEND_HEIGHT).padding(horizontal = Space.m).wrapContentHeight(),
        )
    }
}

/** How tall the legend and the fog's count stand. */
private val LEGEND_HEIGHT = 28.dp

/** A bar floating over the map, at the floating level with its hairline and shadow. */
@Composable
private fun FloatingPill(content: @Composable () -> Unit) {
    Surface(
        shape = Corners.full,
        color = SurfaceLevel.Floating.fill(),
        contentColor = tokens.tx,
        border = cardBorder(),
        shadowElevation = ShadowElevation,
        content = content,
    )
}

/**
 * What the colours and lines mean, as on the site: each year's colour, and the routes for trips.
 * Read only; when it doesn't fit it scrolls sideways, its far end fading out.
 */
@Composable
private fun Legend(colors: Map<Int, Color>, trips: Boolean) {
    FloatingPill {
        val scroll = rememberScrollState()
        val surface = SurfaceLevel.Floating.fill()
        Box {
            Row(
                Modifier.height(LEGEND_HEIGHT).horizontalScroll(scroll).padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                colors.forEach { (year, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                        Text("$year", style = AppTheme.typography.caption, color = tokens.tx2, maxLines = 1, softWrap = false)
                    }
                }
                if (trips) {
                    VerticalDivider(Modifier.height(14.dp), color = dividerColor())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Box(Modifier.width(14.dp).height(3.dp).background(tokens.tx.copy(alpha = ROUTE_OPACITY), CircleShape))
                        Text(stringResource(R.string.legend_trips), style = AppTheme.typography.caption, color = tokens.tx2, maxLines = 1, softWrap = false)
                    }
                }
            }
            if (scroll.canScrollForward) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(28.dp).height(LEGEND_HEIGHT)
                        .background(Brush.horizontalGradient(listOf(surface.copy(alpha = 0f), surface))),
                )
            }
        }
    }
}

/** The lifted route's trip, a card floating over the panel: opened by a tap, played by its button. */
@Composable
private fun RouteCard(trip: TripRecord, cover: MomentMedia?, year: Color?, km: Double?, urls: MediaUrls, onOpen: () -> Unit, onPlay: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = AppTheme.shapes.large,
        color = SurfaceLevel.Floating.fill(),
        border = cardBorder(),
        shadowElevation = ShadowElevation,
        modifier = Modifier.fillMaxWidth().heightIn(min = ROUTE_CARD).sharedCard(tripCardKey(trip.id)).testTag("trip_${trip.id}"),
    ) {
        TripRow(trip, cover, year, km, urls, Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs, top = Space.m, bottom = Space.m)) {
            val label = stringResource(R.string.action_play_trip)
            IconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = label }.testTag("play_${trip.id}")) {
                Icon(AppIcons.Play, contentDescription = null, tint = tokens.tx, modifier = Modifier.size(20.dp))
            }
        }
    }
}
