package cc.hyoban.moments.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary
import com.materialkolor.ktx.harmonizeWithPrimary
import kotlinx.coroutines.flow.drop

/**
 * The map tab, as the site's map: every place the Moments name, coloured by the year of
 * its first Moment, and every trip's route. Tap a place for its Moments in a panel, from which a map app shows the way there; tap a
 * route to lift it, and its card or the route again to open the trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onMoment: (String) -> Unit, onPlace: (String) -> Unit, onTrip: (String) -> Unit) {
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
    // The site's year colours, each turned a little towards the scheme's primary so they sit with it,
    // at Material's tones for a custom colour, so the number on a dot reads in either mode.
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val colors = remember(mapPlaces, scheme, dark) {
        yearColors(mapPlaces.map { it.firstYear }).mapValues { (_, color) -> scheme.harmonizeWithPrimary(color).atTone(if (dark) 80.0 else 40.0) }
    }
    val mapRoutes = remember(trips, routes) {
        trips.sortedByDescending { it.start }.mapNotNull { trip -> routes[trip.id]?.let { MapRoute(trip.id, it) } }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = highlighted != null) { highlighted = null }

    // Locating: each request asks for the place once more; a ring turns while it waits.
    val context = LocalContext.current
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

    // Locating is the map's floating action button, which the app places above the
    // navigation bar or at the top of the rail.
    val accessory = LocalNavAccessory.current
    val currentLocate by rememberUpdatedState(requestLocate)
    val currentLocating by rememberUpdatedState(locating)
    val locateBeside = remember<@Composable () -> Unit> {
        { LocateButton(currentLocating, onClick = { currentLocate() }, inRail = isMediumWidth()) }
    }
    // Whether the tabs stand down a rail, as from Material 3's medium breakpoint.
    val railed = isMediumWidth()
    // It steps aside while a place's sheet is up, the sheet covering what it would locate.
    val placeOpen = selected != null
    DisposableEffect(accessory, placeOpen) {
        if (!placeOpen) accessory.value = locateBeside
        onDispose { if (accessory.value === locateBeside) accessory.value = null }
    }

    Box(Modifier.fillMaxSize()) {
        PlacesMap(
            mapPlaces,
            colors,
            mapRoutes,
            selected,
            highlighted,
            onPlace = { selected = it },
            onRoute = { highlighted = it },
            // A tap on nothing closes the place panel.
            onEmptyTap = { selected = null },
            onTrip = onTrip,
            modifier = Modifier.fillMaxSize(),
            locate = locate,
            north = north,
            onBearing = { bearing = it },
            tab = Atlas,
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
                .padding(top = Space.m, start = if (railed) Space.gutter else 0.dp, end = if (railed) Space.gutter else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (colors.isNotEmpty()) Legend(colors, trips = mapRoutes.isNotEmpty())
        }

        val trip = trips.firstOrNull { it.id == highlighted }
        // Turned some way from north, by more than a nudge.
        val turned = bearing.let { minOf(it, 360 - it) } > 0.5
        // While the map is turned, the compass: upright above the floating action button, and
        // above the trip's card while it shows; on a phone on its side, at the bottom by the rail.
        // Read as it is placed, so the map's screen isn't composed again every frame it rises.
        val lift = animateDpAsState(if (trip != null && !railed) 96.dp else 0.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "compass")
        Box(
            if (railed) {
                Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .absolutePadding(left = rail.left + Space.gutter, bottom = Space.gutter)
            } else {
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = Space.gutter, bottom = LocalBottomClearance.current + LocalFabClearance.current)
                    .offset { IntOffset(0, -lift.value.roundToPx()) }
            },
        ) {
            CompassButton(turned, bearing, onClick = { north++ })
        }
        // Kept while the card leaves, after the route is let go.
        var shown by remember { mutableStateOf(trip) }
        if (trip != null) shown = trip
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            trip != null,
            enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
            exit = fadeOut(motion.defaultEffectsSpec()) + slideOutVertically(motion.defaultSpatialSpec()) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).sideInsetsPadding()
                .padding(start = Space.gutter, end = Space.gutter, bottom = LocalBottomClearance.current + LocalFabClearance.current)
                .widthIn(max = READABLE_WIDTH),
        ) {
            // One route's card giving way to another's as another is lifted.
            Crossfade(shown, animationSpec = motion.fastEffectsSpec(), label = "route card") { card -> card?.let { RouteCard(it) { onTrip(it.id) } } }
        }
    }

    // A place's Moments in Material 3's standard bottom sheet, the one it has for what a map
    // shows about a place: half the screen when a place is chosen, dragged up to all of it or
    // down and away, its handle the sheet's own, tapped or focused as well as dragged. In the
    // app's own window, so a Moment opened from it grows out of its row as from a card.
    var shownPlace by remember { mutableStateOf(selected) }
    if (selected != null) shownPlace = selected
    BackHandler(enabled = selected != null) { selected = null }
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(selected) { if (selected != null) sheetState.partialExpand() else sheetState.hide() }
    // Swiped away, the place is let go.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect { if (it == SheetValue.Hidden) selected = null }
    }
    val clearance = LocalBottomClearance.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffold,
            // Drawn up to the status bar at most, so the whole sheet's top stays clear of it.
            modifier = Modifier.statusBarsPadding(),
            sheetPeekHeight = maxHeight * PLACE_SHEET_SHARE,
            sheetContainerColor = SurfaceLevel.Floating.fill(),
            containerColor = Color.Transparent,
            sheetContent = {
                val id = shownPlace
                val place = id?.let { places[it] }
                if (id != null && place != null) {
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
 * and all of it; then its Moments as a segmented list, each row its first photo, when and
 * its words, kept clear of the navigation bar by [bottom].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            Icon(Icons.Rounded.Place, contentDescription = null, tint = colors[years.minOrNull()] ?: MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text(place.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    (listOf(pluralStringResource(R.plurals.count_moments, here.size, here.size)) + years.joinToString(context.getString(R.string.separator_list)) { context.getString(R.string.year_item, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_navigate_short))
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.l, bottom = Space.l + bottom),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(here, key = { _, moment -> moment.uid }) { index, moment ->
                // It grows into the Moment's page when opened, as a card on a list does.
                SegmentedListItem(
                    onClick = { onMoment(moment.uid) },
                    shapes = ListItemDefaults.segmentedShapes(index, here.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = LocalSurfaceLevel.current.raised.fill()),
                    leadingContent = moment.media.firstOrNull()?.let { media -> { Thumbnail(media, urls, size = 56) } },
                    // When over it as the overline and its words the headline, as the search's
                    // results have it, each in the role Material 3 gives a list item's text.
                    overlineContent = { Text(listOfNotNull(formatDate(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ")) },
                    modifier = Modifier.sharedCard(moment.uid).testTag("moment_${moment.uid}"),
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
                    shapes = ListItemDefaults.segmentedShapes(here.size, here.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = LocalSurfaceLevel.current.raised.fill()),
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.testTag("place_$id"),
                ) { Text(stringResource(R.string.action_see_moments), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Which way north is, shown while the map is [turned]; tapped, the map turns back to it. */
@Composable
private fun CompassButton(turned: Boolean, bearing: Double, onClick: () -> Unit) {
    // Growing along one axis rather than scaling, as Material 3 has a component enter on Android.
    val motion = MaterialTheme.motionScheme
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
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = Elevation.level2,
                modifier = Modifier.padding(bottom = Space.m).size(48.dp).testTag("north"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Navigation,
                    contentDescription = stringResource(R.string.action_north),
                    modifier = Modifier.rotate(-bearing.toFloat()),
                )
                }
            }
        }
    }
}

/** Brings the map to where the phone is; a loading indicator while it finds out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit, inRail: Boolean = false) {
    // The map's one floating action button, in Material 3's own colours; level with the rail
    // at its top. Named on the button, so it keeps its name while the indicator stands in
    // for the icon.
    val label = stringResource(R.string.action_locate)
    Tooltip(label) {
        FloatingActionButton(
            onClick = { if (!locating) onClick() },
            elevation = if (inRail) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else FloatingActionButtonDefaults.elevation(),
            modifier = Modifier.semantics { contentDescription = label }.testTag("locate"),
        ) {
            Crossfade(locating, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "locating") { busy ->
                if (busy) {
                    LoadingIndicator(Modifier.size(32.dp), color = LocalContentColor.current)
                } else {
                    Icon(Icons.Rounded.MyLocation, contentDescription = null)
                }
            }
        }
    }
}

/** A bar floating over the map, at the floating level and raised. */
@Composable
private fun FloatingPill(content: @Composable () -> Unit) {
    Surface(
        shape = Corners.full,
        color = SurfaceLevel.Floating.fill(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                    Box(Modifier.size(10.dp).border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(1.dp).background(color, CircleShape))
                    Text("$year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trips) {
                VerticalDivider(Modifier.height(14.dp), color = dividerColor())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Box(Modifier.width(14.dp).height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = ROUTE_OPACITY), CircleShape))
                    Text(stringResource(R.string.legend_trips), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = SurfaceLevel.Floating.fill()),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2),
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s).sharedCard(tripCardKey(trip.id)).testTag("trip_${trip.id}"),
    ) {
        Row(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Luggage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(trip.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    tripSummary(trip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
