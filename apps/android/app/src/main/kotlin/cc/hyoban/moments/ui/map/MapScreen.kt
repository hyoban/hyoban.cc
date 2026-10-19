package cc.hyoban.moments.ui.map

import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.animation.core.animateDpAsState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.components.AppColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Card
import com.materialkolor.ktx.harmonizeWithPrimary
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.components.CompactMomentRow
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space

/**
 * The map tab, as the site's map: every place the Moments name, coloured by the year of
 * its first Moment, and every trip's route. Tap a place for its Moments in a sheet; tap a
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
    // The site's year colours, each turned a little towards the scheme's primary so they sit with it.
    val scheme = MaterialTheme.colorScheme
    val colors = remember(mapPlaces, scheme) {
        yearColors(mapPlaces.map { it.firstYear }).mapValues { (_, color) -> scheme.harmonizeWithPrimary(color) }
    }
    val mapRoutes = remember(trips, routes) {
        trips.sortedByDescending { it.start }.mapNotNull { trip -> routes[trip.id]?.let { MapRoute(trip.id, it) } }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = highlighted != null) { highlighted = null }

    // Locating: each request asks for the place once more; a ring turns while it waits.
    val context = LocalContext.current
    var locate by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locating = true
            locate++
        } else {
            Toast.makeText(context, R.string.error_location_permission, Toast.LENGTH_SHORT).show()
        }
    }
    val requestLocate: () -> Unit = {
        val granted = LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            locating = true
            locate++
        } else {
            permission.launch(LOCATION_PERMISSIONS)
        }
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
            onTrip = onTrip,
            modifier = Modifier.fillMaxSize(),
            locate = locate,
            onLocated = { found ->
                locating = false
                if (!found) Toast.makeText(context, R.string.error_location, Toast.LENGTH_SHORT).show()
            },
        )
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (colors.isNotEmpty()) Legend(colors, trips = mapRoutes.isNotEmpty())
        }

        val trip = trips.firstOrNull { it.id == highlighted }
        // Above the trip's card while it shows, else just above the tabs.
        val lift by animateDpAsState(if (trip != null) 96.dp else 0.dp, label = "locate")
        LocateButton(
            locating,
            onClick = requestLocate,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = Space.gutter, bottom = LocalBottomClearance.current + Space.s + lift),
        )
        // Kept while the card leaves, after the route is let go.
        var shown by remember { mutableStateOf(trip) }
        if (trip != null) shown = trip
        AnimatedVisibility(
            trip != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = Space.gutter, end = Space.gutter, bottom = LocalBottomClearance.current),
        ) {
            shown?.let { RouteCard(it) { onTrip(it.id) } }
        }
    }

    // The sheet is its own window, over everything; it shows only while the map is the page
    // in front. A back swipe from a page opened from it draws the map under that page as a
    // preview, and a sheet popping up there would take the swipe and stop it.
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    selected?.takeIf { resumed.isAtLeast(Lifecycle.State.RESUMED) }?.let { id ->
        val place = places[id] ?: return@let
        val here = byPlace[id].orEmpty()
        val years = remember(here) { here.map { yearOf(it.occurredAt) }.distinct().sortedDescending() }
        val context = LocalContext.current
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = AppColors.sheetLevel.fill(),
        ) {
            CompositionLocalProvider(LocalSurfaceLevel provides AppColors.sheetLevel) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, contentDescription = null, tint = colors[years.minOrNull()] ?: MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(place.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            (listOf(stringResource(R.string.count_moments, here.size)) + years.joinToString("、") { context.getString(R.string.year_item, it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { selected = null; onPlace(id) }) {
                        Text(stringResource(R.string.action_all))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(here, key = { it.uid }) { moment ->
                        CompactMomentRow(moment, places, container.mediaUrls, onClick = { onMoment(moment.uid) })
                    }
                }
                }
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Brings the map to where the phone is; a ring while it finds out. */
@Composable
private fun LocateButton(locating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Floating like the tabs, the icon in primary as the one action the map has.
    FloatingActionButton(
        onClick = { if (!locating) onClick() },
        shape = Corners.full,
        containerColor = SurfaceLevel.floating(LocalDarkTheme.current).fill(),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        if (locating) {
            CircularProgressIndicator(Modifier.size(24.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.action_locate))
        }
    }
}

/** A bar floating over the map, coloured and raised like the tabs. */
@Composable
private fun FloatingPill(content: @Composable () -> Unit) {
    Surface(
        shape = Corners.full,
        color = SurfaceLevel.floating(LocalDarkTheme.current).fill(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        content = content,
    )
}

/** What the colours and lines mean, as on the site: each year's colour, and the routes for trips. */
@Composable
private fun Legend(colors: Map<Int, Color>, trips: Boolean) {
    FloatingPill {
        Row(
            Modifier.height(32.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            colors.forEach { (year, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(10.dp).border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(1.dp).background(color, CircleShape))
                    Text("$year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trips) {
                VerticalDivider(Modifier.height(14.dp), color = dividerColor())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(14.dp).height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape))
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
        colors = CardDefaults.cardColors(containerColor = SurfaceLevel.floating(LocalDarkTheme.current).fill()),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
    ) {
        Row(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Luggage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(trip.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(formatRange(trip.start, trip.end), stringResource(R.string.count_days, daysBetween(trip.start, trip.end))).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
