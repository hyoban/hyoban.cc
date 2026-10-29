package cc.hyoban.moments.ui.map

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
import cc.hyoban.moments.ui.design.FilledTonalButton
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
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary
import kotlinx.coroutines.flow.drop

/** What another page shows on the map tab: a place, with its panel up, or a trip's route, lifted with its card. */
sealed interface MapFocus {
    data class Place(val id: String) : MapFocus

    data class Trip(val id: String) : MapFocus

    data object Footprints : MapFocus
}

/**
 * The map tab, as the site's map: every place the Moments name, coloured by the year of
 * its first Moment, and every trip's route. Tap a place for its Moments in a panel, from which a map app shows the way there; tap a
 * route to lift it, and its card or the route again to open the trip. A place or a trip
 * shown on it from its page, [focus], is chosen and flown to, then let go with [onFocused].
 */
@Composable
fun MapScreen(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    focus: MapFocus? = null,
    onFocused: () -> Unit = {},
    onFootprintSettings: () -> Unit = {},
) {
    FootprintsOnMap(container, onFootprintSettings) { footprints ->
        MapContent(container, onMoment, onPlace, onTrip, focus, onFocused, footprints)
    }
}

@Composable
private fun MapContent(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    focus: MapFocus?,
    onFocused: () -> Unit,
    footprints: FootprintsMapState,
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val routes by container.routes.routes.collectAsStateWithLifecycle()
    val byPlace = remember(moments) {
        moments.flatMap { moment -> moment.places.distinct().map { it to moment } }.groupBy({ it.first }, { it.second })
    }
    val mapPlaces = remember(byPlace, places) {
        byPlace.mapNotNull { (id, here) -> places[id]?.let { MapPlace(id, it, here.size, here.minOf { moment -> yearOf(moment.occurredAt) }) } }
    }
    // The site's year colours as they are, in either mode, so a place is the same colour on the
    // phone as on the site's map: an exception to Material 3, which would turn them towards the
    // scheme's primary and set them at a custom colour's tones.
    val colors = remember(mapPlaces) { yearColors(mapPlaces.map { it.firstYear }) }
    val mapRoutes = remember(trips, routes) {
        trips.sortedByDescending { it.start }.mapNotNull { trip -> routes[trip.id]?.let { MapRoute(trip.id, it) } }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }

    // The fog of war: the cities read from the app's assets the first time it's turned on, and
    // those a Moment was written in, the phone's own among them, lit.
    val settings = LocalSettings.current
    val context = LocalContext.current
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
            show(context.getString(R.string.error_location_permission))
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

    // Whether the tabs stand down a rail, as from the medium breakpoint.
    val railed = isMediumWidth()

    Box(Modifier.fillMaxSize()) {
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
            footprints = footprints.segments,
            frameFootprints = footprints.frameRequested,
            onFootprintsFramed = footprints.onFramed,
            onFootprint = { selected = null; highlighted = null; footprints.open() },
            tab = Atlas,
            focus = focus.takeUnless { it == MapFocus.Footprints },
            onFocused = onFocused,
            fog = fog.takeIf { settings.mapFog },
            fogSweep = fogSweep,
            fogRegions = fogRegions.orEmpty(),
            onLocated = { found ->
                locating = false
                if (!found) show(context.getString(R.string.error_location))
            },
        )
        // On a phone on its side, at the top of the side away from the tabs and their buttons.
        val rail = LocalRailSpace.current
        Column(
            Modifier.align(
                when {
                    !railed -> Alignment.TopCenter
                    rail.left > 0.dp -> AbsoluteAlignment.TopRight
                    else -> AbsoluteAlignment.TopLeft
                },
            )
                .statusBarsPadding().sideInsetsPadding()
                .padding(top = Space.m, start = Space.gutter, end = Space.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // The two round buttons at the page's gutters and the footprints between them; the
            // legend under them on a line of its own, as wide as it needs whatever the phone.
            Row(
                (if (railed) Modifier else Modifier.fillMaxWidth()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                // Locating among the controls at the top, clear of the sheet of trips at the foot.
                LocateButton(locating, onClick = requestLocate)
                Box(if (railed) Modifier else Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FootprintsButton(onClick = { selected = null; highlighted = null; footprints.open() })
                }
                FogButton(settings.mapFog) {
                    fogSweep++
                    settings.changeMapFog(!settings.mapFog)
                }
            }
            if (colors.isNotEmpty()) Legend(colors, trips = mapRoutes.isNotEmpty())
            val shownFog = fog.takeIf { settings.mapFog }
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
        // While the map is turned, the compass: upright above the floating action button, and
        // above the trip's card while it shows; on a phone on its side, at the bottom by the rail.
        // Read as it is placed, so the map's screen isn't composed again every frame it rises.
        val lift = animateDpAsState(if (trip != null && !railed) 96.dp else 0.dp, AppTheme.motionScheme.defaultSpatialSpec(), label = "compass")
        Box(
            if (railed) {
                Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .absolutePadding(left = rail.left + Space.gutter, bottom = Space.gutter)
            } else {
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = Space.gutter, bottom = LocalBottomClearance.current + TRIPS_PEEK + Space.m)
                    .offset { IntOffset(0, -lift.value.roundToPx()) }
            },
        ) {
            CompassButton(turned, bearing, onClick = { north++ })
        }
        // Kept while the card leaves, after the route is let go.
        var shown by remember { mutableStateOf(trip) }
        if (trip != null) shown = trip
        val motion = AppTheme.motionScheme
        AnimatedVisibility(
            trip != null,
            enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
            exit = fadeOut(motion.defaultEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).sideInsetsPadding()
                .padding(start = Space.gutter, end = Space.gutter, bottom = LocalBottomClearance.current + TRIPS_PEEK + Space.m)
                .widthIn(max = READABLE_WIDTH),
        ) {
            // One route's card giving way to another's as another is lifted.
            Crossfade(shown, animationSpec = motion.fastEffectsSpec(), label = "route card") { card -> card?.let { RouteCard(it) { onTrip(it.id) } } }
        }
    }

    // The sheet at the map's foot: the trips, peeking as a line of how many and dragged up to
    // their list; a place chosen, its Moments in it instead, half the screen, dragged up to all
    // of it or down and away, back to the trips. Its handle is the sheet's own, tapped or
    // focused as well as dragged. In the app's own window, so a trip or a Moment opened from
    // it grows out of its card or row.
    BackHandler(enabled = selected != null) { selected = null }
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(selected) { sheetState.partialExpand() }
    // Swiped away, a place is let go and the trips come back; the trips themselves stay peeking.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect {
            if (it == SheetValue.Hidden) {
                if (selected != null) selected = null else sheetState.partialExpand()
            }
        }
    }
    val clearance = LocalBottomClearance.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffold,
            // Drawn up to the status bar at most, so the whole sheet's top stays clear of it.
            modifier = Modifier.statusBarsPadding(),
            sheetPeekHeight = if (selected != null) maxHeight * PLACE_SHEET_SHARE else TRIPS_PEEK + clearance,
            sheetContainerColor = AppColors.sheet,
            containerColor = Color.Transparent,
            sheetContent = {
                val id = selected
                val place = id?.let { places[it] }
                if (id == null || place == null) {
                    TripsSheet(trips, moments, container.mediaUrls, bottom = clearance, onTrip = onTrip)
                } else {
                    val here = byPlace[id].orEmpty()
                    val years = remember(here) { here.map { yearOf(it.occurredAt) }.distinct().sortedDescending() }
                    PlaceSheet(
                        place, id, here, years, colors, places, container.mediaUrls,
                        bottom = clearance,
                        onNavigate = { if (!navigateTo(context, place)) show(context.getString(R.string.no_map_app)) },
                        onAll = { selected = null; onPlace(id) },
                        onMoment = onMoment,
                    )
                }
            },
        ) { }
    }
}

/**
 * A place in the map's sheet: its name, how many Moments and in which years, the way there
 * and all of it; then its Moments as a grouped list, each row its first photo, when and
 * its words, kept clear of the navigation bar by [bottom].
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
    val context = LocalContext.current
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Pin, contentDescription = null, tint = colors[years.minOrNull()] ?: AppTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text(place.name, style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                Text(
                    (listOf(pluralStringResource(R.plurals.count_moments, here.size, here.size)) + years.joinToString(context.getString(R.string.separator_list)) { context.getString(R.string.year_item, it) }).joinToString("\u2003"),
                    style = AppTheme.typography.body,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The way there beside the name, named as well as drawn, as Material 3 keeps an
            // icon alone for what everyone knows; all of the place waits at the list's foot.
            FilledTonalButton(
                onClick = onNavigate,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("navigate"),
            ) {
                Icon(AppIcons.Directions, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_navigate_short))
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.l + bottom),
        ) {
            itemsIndexed(here, key = { _, moment -> moment.uid }) { index, moment ->
                // It grows into the Moment's page when opened, as a card on a list does.
                SegmentedListItem(
                    onClick = { onMoment(moment.uid) },
                    shapes = groupedShapes(index, here.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = Color.Transparent),
                    leadingContent = moment.media.firstOrNull()?.let { media -> { Thumbnail(media, urls, size = 56) } },
                    // When over it as the overline and its words the headline, as the search's
                    // results have it, each in the role Material 3 gives a list item's text.
                    overlineContent = { Text(listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ")) },
                    modifier = Modifier.sharedCard(moment.uid).groupedRow(index, here.size + 1, inset = 88f).testTag("moment_${moment.uid}"),
                ) {
                    Text(
                        moment.text.ifBlank { moment.places.joinToString(stringResource(R.string.separator_list)) { places[it]?.name ?: it } },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The place's own page, the last segment of the list, read on to after its Moments.
            item(key = "all") {
                SegmentedListItem(
                    onClick = onAll,
                    shapes = groupedShapes(here.size, here.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = Color.Transparent),
                    trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3) },
                    modifier = Modifier.groupedRow(here.size, here.size + 1).testTag("place_$id"),
                ) { Text(stringResource(R.string.action_see_moments)) }
            }
        }
    }
}

/** How much of the sheet of trips shows while it peeks: its title and count. */
private val TRIPS_PEEK = 96.dp

/**
 * The trips in the map's sheet: a line of how many and how many days, which is all that shows
 * while it peeks, then each trip's card, newest first, growing into its page when tapped.
 */
@Composable
private fun TripsSheet(trips: List<TripRecord>, moments: List<MomentRecord>, urls: MediaUrls, bottom: Dp, onTrip: (String) -> Unit) {
    val days = remember(trips) { trips.sumOf { daysBetween(it.start, it.end) } }
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.title_trips), style = AppTheme.typography.title, modifier = Modifier.weight(1f).semantics { heading() })
            Text(
                pluralStringResource(R.plurals.count_trips, trips.size, trips.size) + "\u2003" + pluralStringResource(R.plurals.count_days_total, days, days),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Space.l + bottom),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(trips.sortedByDescending { it.start }, key = { it.id }) { trip ->
                val covers = remember(trip, moments) { coversOf(trip, moments, trips) }
                val count = remember(trip, moments) { countOf(trip, moments, trips) }
                TripCard(trip, covers, count, urls, onClick = { onTrip(trip.id) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Which way north is, shown while the map is [turned]; tapped, the map turns back to it. */
@Composable
private fun CompassButton(turned: Boolean, bearing: Double, onClick: () -> Unit) {
    // Growing along one axis rather than scaling, as Material 3 has a component enter on Android.
    val motion = AppTheme.motionScheme
    AnimatedVisibility(
        turned,
        enter = expandVertically(motion.fastSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(motion.fastEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(motion.fastEffectsSpec()),
    ) {
        // A map control rather than a second floating action button: a floating round button.
        Tooltip(stringResource(R.string.action_north)) {
            Surface(
                onClick = onClick,
                shape = Corners.full,
                color = SurfaceLevel.Floating.fill(),
                contentColor = AppTheme.colorScheme.primary,
                shadowElevation = Elevation.level2,
                modifier = Modifier.padding(bottom = Space.m).size(48.dp).testTag("north"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Navigation,
                    contentDescription = stringResource(R.string.action_north),
                    modifier = Modifier.rotate(-bearing.toFloat()),
                )
                }
            }
        }
    }
}

/** Brings the map to where the phone is; a loading indicator while it finds out. */
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit) {
    // A round button in the floating surface, the size of the fog's beside it. Named on the
    // button, so it keeps its name while the indicator stands in for the icon.
    val label = stringResource(R.string.action_locate)
    Tooltip(label) {
        Surface(
            onClick = { if (!locating) onClick() },
            shape = Corners.full,
            color = SurfaceLevel.Floating.fill(),
            contentColor = AppTheme.colorScheme.onSurface,
            shadowElevation = Elevation.level2,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label }.testTag("locate"),
        ) {
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
}

/** Opens the phone's own footprints: a floating pill between the round buttons, as the map's controls float. */
@Composable
private fun FootprintsButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Corners.full,
        color = SurfaceLevel.Floating.fill(),
        contentColor = AppTheme.colorScheme.onSurface,
        shadowElevation = Elevation.level2,
        modifier = Modifier.heightIn(min = 48.dp).testTag("footprints"),
    ) {
        Row(
            Modifier.padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(AppIcons.Footprints, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.footprints_title), style = AppTheme.typography.label)
        }
    }
}

/** Turns the fog of war on and off: a floating round toggle, filled while it's on. */
@Composable
private fun FogButton(on: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.action_fog)
    // Filling and emptying as the fog comes and goes, on the motion scheme's effects spring.
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(if (on) AppTheme.colorScheme.primary else SurfaceLevel.Floating.fill(), spec, label = "fog button")
    val content by animateColorAsState(if (on) AppTheme.colorScheme.onPrimary else AppTheme.colorScheme.onSurface, spec, label = "fog icon")
    Tooltip(label) {
        Surface(
            checked = on,
            onCheckedChange = { onClick() },
            shape = Corners.full,
            color = container,
            contentColor = content,
            shadowElevation = Elevation.level2,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label }.testTag("fog"),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Fog, contentDescription = null) }
        }
    }
}

/** How many cities and provinces the fog leaves lit. */
@Composable
private fun FogCount(fog: FogOfWar) {
    FloatingPill {
        Text(
            stringResource(R.string.fog_lit, fog.cities, fog.provinces),
            style = AppTheme.typography.caption,
            color = AppTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(32.dp).padding(horizontal = Space.l).wrapContentHeight(),
        )
    }
}

/** A bar floating over the map, at the floating level and raised. */
@Composable
private fun FloatingPill(content: @Composable () -> Unit) {
    Surface(
        shape = Corners.full,
        color = SurfaceLevel.Floating.fill(),
        contentColor = AppTheme.colorScheme.onSurface,
        shadowElevation = Elevation.level2,
        content = content,
    )
}

/** What the colours and lines mean, as on the site: each year's colour, and the routes for trips. */
@Composable
private fun Legend(colors: Map<Int, Color>, trips: Boolean) {
    FloatingPill {
        Row(
            Modifier.height(32.dp).padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            colors.forEach { (year, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    // A dot as the map draws one, ringed in the surface.
                    Box(Modifier.size(10.dp).border(1.5.dp, AppTheme.colorScheme.surface, CircleShape).padding(1.dp).background(color, CircleShape))
                    Text("$year", style = AppTheme.typography.caption, color = AppTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            if (trips) {
                VerticalDivider(Modifier.height(14.dp), color = dividerColor())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Box(Modifier.width(14.dp).height(2.dp).background(AppTheme.colorScheme.onSurface.copy(alpha = ROUTE_OPACITY), CircleShape))
                    Text(stringResource(R.string.legend_trips), style = AppTheme.typography.caption, color = AppTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** The lifted route's trip, a card that opens it. */
@Composable
private fun RouteCard(trip: TripRecord, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = AppTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = SurfaceLevel.Floating.fill()),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2),
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s).sharedCard(tripCardKey(trip.id)).testTag("trip_${trip.id}"),
    ) {
        Row(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Luggage, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(trip.title, style = AppTheme.typography.titleSmall)
                Text(
                    tripSummary(trip),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
        }
    }
}
