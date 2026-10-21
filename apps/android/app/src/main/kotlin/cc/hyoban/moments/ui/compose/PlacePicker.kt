package cc.hyoban.moments.ui.compose

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.animation.AnimatedVisibility
import cc.hyoban.moments.ui.components.predictiveBackScale
import cc.hyoban.moments.ui.components.rememberPredictiveBack
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Found
import cc.hyoban.moments.data.local.NEARBY_METRES
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.currentLocation
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.local.nearestPlaces
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.map.MapTarget
import cc.hyoban.moments.ui.map.PICK_ZOOM
import cc.hyoban.moments.ui.map.PickerMap
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a Moment is: a known place, a new one at [New.fix] under a name, or none. */
internal sealed interface PlaceChoice {
    data class Known(val id: String) : PlaceChoice
    data class New(val name: String, val fix: Fix) : PlaceChoice
    data object None : PlaceChoice
}

/**
 * The place for a new Moment, chosen on a map: a pin stands at its middle, and the map
 * dragged under it makes a new place there, named after what is around it until a name is
 * typed; a known place's dot tapped chooses it. A place found in a map app comes in by
 * sharing it to the app instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlacePicker(
    current: PlaceChoice,
    /** Where the Moment was worked out to be, to open on when nothing is chosen yet. */
    fix: Fix?,
    places: Map<String, Place>,
    search: PlaceSearch,
    onDismiss: () -> Unit,
    onPick: (PlaceChoice) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf(current) }
    var named by remember { mutableStateOf(current is PlaceChoice.New) }
    var name by remember { mutableStateOf((current as? PlaceChoice.New)?.name.orEmpty()) }
    val opening = remember {
        when (current) {
            is PlaceChoice.Known -> places[current.id]?.fix()
            is PlaceChoice.New -> current.fix
            PlaceChoice.None -> fix
        }
    }
    var target by remember { mutableStateOf(opening?.let { MapTarget(it.latitude, it.longitude, PICK_ZOOM) }) }
    var locating by remember { mutableStateOf(false) }
    val fly = { to: Fix -> target = MapTarget(to.latitude, to.longitude, PICK_ZOOM) }
    val choose: (String) -> Unit = { id ->
        pick = PlaceChoice.Known(id)
        places[id]?.let { fly(it.fix()) }
    }

    // Back, by the system's gesture, previews closing the picker under the finger.
    val back = rememberPredictiveBack(onBack = onDismiss)

    // Where the pin has come to rest: what is around it, to name a new place after, and
    // the name of the nearest of them for it until one is typed.
    var landmarks by remember { mutableStateOf<List<Found>>(emptyList()) }
    // Around the pin, or the known place chosen, so either has what is near it to choose instead.
    val pinned = when (val chosen = pick) {
        is PlaceChoice.New -> chosen.fix
        is PlaceChoice.Known -> places[chosen.id]?.fix()
        PlaceChoice.None -> null
    }
    LaunchedEffect(pinned) {
        landmarks = emptyList()
        val at = pinned ?: return@LaunchedEffect
        delay(300)
        val around = search.around(context, at)
        landmarks = around.landmarks.take(LANDMARKS)
        val new = pick as? PlaceChoice.New
        if (!named && new != null && new.fix == at) {
            name = around.name ?: coordinatesName(at)
            pick = new.copy(name = name)
        }
    }

    // What is chosen in Material 3's standard bottom sheet over the map, as the map's own
    // place is: as tall as what it holds, held there, the map reaching under its corners and
    // the pin at the middle of what shows of it.
    val density = LocalDensity.current
    var sheetHeight by remember { mutableStateOf(0.dp) }
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(skipHiddenState = true))
    BottomSheetScaffold(
        scaffoldState = sheet,
        modifier = Modifier.predictiveBackScale(back),
        sheetPeekHeight = sheetHeight,
        sheetSwipeEnabled = false,
        sheetDragHandle = null,
        sheetContainerColor = AppColors.page,
        sheetShadowElevation = Elevation.level1,
        containerColor = AppColors.page,
        sheetContent = {
            ChoiceCard(
                pick = pick,
                name = name,
                places = places,
                landmarks = landmarks,
                onLandmark = { landmark ->
                    named = true
                    name = landmark.name
                    pick = PlaceChoice.New(landmark.name, landmark.fix)
                    fly(landmark.fix)
                },
                onName = {
                    named = true
                    name = it
                    (pick as? PlaceChoice.New)?.let { new -> pick = new.copy(name = it) }
                },
                onKnown = choose,
                onNone = { onPick(PlaceChoice.None) },
                onConfirm = { onPick(pick) },
                modifier = Modifier.onSizeChanged { sheetHeight = with(density) { it.height.toDp() } },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().padding(bottom = (sheetHeight - SHEET_CORNER).coerceAtLeast(0.dp))) {
            PickerMap(
                places = places,
                chosen = (pick as? PlaceChoice.Known)?.id,
                target = target,
                onCenter = { latitude, longitude, byUser ->
                    val at = Fix(latitude, longitude)
                    // Dragged, the pin is a new place where it stands; a new place flown to an address stays one there.
                    if (byUser || pick is PlaceChoice.New) pick = PlaceChoice.New(name, at)
                },
                onPlace = choose,
                modifier = Modifier.fillMaxSize().testTag("place_map"),
            )
            // The pin's point at the map's middle; a known place chosen shows as its own dot instead.
            val motion = MaterialTheme.motionScheme
            AnimatedVisibility(
                pick !is PlaceChoice.Known,
                enter = scaleIn(motion.fastSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(motion.fastEffectsSpec()),
                exit = scaleOut(motion.fastSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.Center).offset(y = -(PIN_SIZE / 2)),
            ) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(PIN_SIZE))
            }
            FloatingBack(onDismiss, Modifier.align(Alignment.TopStart))
            val granted = LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                // At the map's bottom end, clear of the sheet's corners; a box of its own places
                // it, as the tooltip's box doesn't pass an alignment on.
                Box(Modifier.align(Alignment.BottomEnd).padding(bottom = SHEET_CORNER).padding(Space.l)) {
                    val locateLabel = stringResource(R.string.action_locate)
                    Tooltip(locateLabel) {
                        FloatingActionButton(
                            onClick = {
                                if (!locating) {
                                    locating = true
                                    scope.launch {
                                        // Just where the phone is becomes the choice, a new place named
                                        // after what is around it; a known place nearby is a chip away.
                                        currentLocation(context)?.let { location ->
                                            val here = Fix(location.latitude, location.longitude)
                                            named = false
                                            pick = PlaceChoice.New(name, here)
                                            fly(here)
                                        }
                                        locating = false
                                    }
                                }
                            },
                            shape = Corners.full,
                            containerColor = SurfaceLevel.Floating.fill(),
                            contentColor = MaterialTheme.colorScheme.primary,
                            // Named on the button, so it keeps its name while the indicator stands in for the icon.
                            modifier = Modifier.semantics { contentDescription = locateLabel }.testTag("locate"),
                        ) {
                            // A loading indicator while it finds where the phone is, as the map tab's button has.
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
            }
        }
    }
}

/** What is chosen, under the map: a known place, a new one to name, or none; and the buttons to keep it. */
@Composable
private fun ChoiceCard(
    pick: PlaceChoice,
    name: String,
    places: Map<String, Place>,
    landmarks: List<Found>,
    onLandmark: (Found) -> Unit,
    onName: (String) -> Unit,
    onKnown: (String) -> Unit,
    onNone: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Growing or shrinking from one kind of choice to another, rather than jumping, the map above following.
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.navigationBarsPadding().imePadding().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(start = Space.xl, end = Space.xl, top = Space.l, bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            when (pick) {
                is PlaceChoice.Known -> {
                    Text(stringResource(R.string.label_known_place), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(places[pick.id]?.name ?: pick.id, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("chosen_place"))
                    places[pick.id]?.let { NearbyChips(it.fix(), pick.id, places, landmarks, onKnown, onLandmark) }
                }
                is PlaceChoice.New -> {
                    Text(stringResource(R.string.place_new_here), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = name,
                        onValueChange = onName,
                        label = { Text(stringResource(R.string.place_name)) },
                        supportingText = { Text(coordinatesName(pick.fix)) },
                        singleLine = true,
                        // Done, or Enter, keeps the place as confirm does, once it has a name.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm() }),
                        modifier = Modifier.fillMaxWidth().testTag("place_name"),
                    )
                    NearbyChips(pick.fix, null, places, landmarks, onKnown, onLandmark)
                }
                PlaceChoice.None -> {
                    Text(stringResource(R.string.place_drag_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNone, modifier = Modifier.testTag("place_none")) { Text(stringResource(R.string.place_none)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    enabled = when (pick) {
                        is PlaceChoice.New -> pick.name.isNotBlank()
                        is PlaceChoice.Known -> true
                        PlaceChoice.None -> false
                    },
                    modifier = Modifier.testTag("place_confirm"),
                ) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}

/**
 * What is around [at] to choose instead: the known places close by but [chosen], each
 * with how far it is, then what OpenStreetMap knows there, each a name to take and a
 * place to move the pin to.
 */
@Composable
private fun NearbyChips(
    at: Fix,
    chosen: String?,
    places: Map<String, Place>,
    landmarks: List<Found>,
    onKnown: (String) -> Unit,
    onLandmark: (Found) -> Unit,
) {
    val known = nearestPlaces(places, at, limit = KNOWN_NEARBY + 1).filter { it.first != chosen && it.second <= NEARBY_METRES }.take(KNOWN_NEARBY)
    // The row keeps its height while what is around is looked up again, so the sheet, and the
    // pin at the middle of the map above it, stay put as the pin moves; the chips fade in and out.
    LazyRow(Modifier.heightIn(min = SuggestionChipDefaults.Height), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            itemsIndexed(known, key = { _, it -> "known:${it.first}" }) { _, (id, metres) ->
                SuggestionChip(
                    onClick = { onKnown(id) },
                    label = { Text("${places.getValue(id).name} · ${formatDistance(metres)}", maxLines = 1) },
                    icon = { Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.animateItem().testTag("nearby_$id"),
                )
            }
            itemsIndexed(landmarks, key = { _, it -> "${it.name}@${it.fix}" }) { index, landmark ->
                SuggestionChip(
                    onClick = { onLandmark(landmark) },
                    label = { Text(landmark.name, maxLines = 1) },
                    modifier = Modifier.animateItem().testTag("landmark_$index"),
                )
            }
    }
}

@Composable
internal fun formatDistance(metres: Double): String =
    if (metres < 1000) stringResource(R.string.distance_metres, metres.toInt()) else stringResource(R.string.distance_kilometres, metres / 1000)

internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private const val LANDMARKS = 8
private const val KNOWN_NEARBY = 3
private val PIN_SIZE = 44.dp
/** How far the map reaches under the sheet: its corners' radius, Material 3's extra large. */
private val SHEET_CORNER = Radius.extraLarge
