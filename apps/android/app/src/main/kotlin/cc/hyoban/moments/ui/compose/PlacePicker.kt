package cc.hyoban.moments.ui.compose

import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.SurfaceLevel
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.LocalContentColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.FloatingActionButton
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.sheetSurface
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.strong
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.SearchField
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.theme.cardBorder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.AnimatedVisibility
import cc.hyoban.moments.ui.components.predictiveBackScale
import cc.hyoban.moments.ui.components.rememberPredictiveBack
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.TransformOrigin
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.local.kindOf
import cc.hyoban.moments.data.local.nearestPlaces
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.map.MapTarget
import cc.hyoban.moments.ui.map.PICK_ZOOM
import cc.hyoban.moments.ui.map.PickerMap
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where a Moment is: a known place, with the kind it is to take when that is changed, a new
 * one at [New.fix] under a name, or none.
 */
internal sealed interface PlaceChoice {
    data class Known(val id: String, val kind: PlaceKind? = null) : PlaceChoice
    data class New(val name: String, val fix: Fix, val kind: PlaceKind? = null) : PlaceChoice
    data object None : PlaceChoice
}

/**
 * The place for a new Moment, chosen on a map: a pin stands at its middle, and under the map
 * a search and one list of what is there, the choice first, ticked. The map dragged under the
 * pin makes a new place there, named after what is around it; a row tapped, a search's result
 * or a known place's dot chooses that instead. Opened with nothing chosen and nowhere to
 * start, it finds where the phone is. A place found in a map app comes in by sharing it to
 * the app instead.
 */
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
    // A name typed stays where the pin goes; one taken from a row follows the pin to what is there next.
    var typedName by remember { mutableStateOf(current is PlaceChoice.New) }
    // A kind chosen by hand stays; until then it follows what the pin is named after.
    var kindChosen by remember { mutableStateOf((current as? PlaceChoice.New)?.kind != null) }
    val opening = remember {
        when (current) {
            is PlaceChoice.Known -> places[current.id]?.fix()
            is PlaceChoice.New -> current.fix
            PlaceChoice.None -> fix
        }
    }
    var target by remember { mutableStateOf(opening?.let { MapTarget(it.latitude, it.longitude, PICK_ZOOM) }) }
    var locating by remember { mutableStateOf(false) }
    // Just where the phone is becomes the choice, a new place named after what is around it.
    val locate: () -> Unit = {
        locating = true
        scope.launch {
            currentLocation(context)?.let { location ->
                val here = Fix(location.latitude, location.longitude)
                named = false
                typedName = false
                kindChosen = false
                pick = PlaceChoice.New(name, here)
                target = MapTarget(here.latitude, here.longitude, PICK_ZOOM)
            }
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else Toast.makeText(context, R.string.error_location_permission, Toast.LENGTH_SHORT).show()
    }
    val askToLocate = {
        if (!locating) {
            if (LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                locate()
            } else {
                permission.launch(LOCATION_PERMISSIONS)
            }
        }
    }
    // The keyboard the Moment's words had goes, so the map shows. Opened with nothing chosen
    // and nowhere to start, where the phone is: asked once, and refused, the map waits for a
    // drag or the locate button.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        if (opening == null) askToLocate()
    }
    val fly = { to: Fix -> target = MapTarget(to.latitude, to.longitude, PICK_ZOOM) }
    val choose: (String) -> Unit = { id ->
        pick = PlaceChoice.Known(id)
        places[id]?.let { fly(it.fix()) }
    }
    val chooseFound: (Found) -> Unit = { found ->
        named = true
        typedName = false
        name = found.name
        kindChosen = false
        pick = PlaceChoice.New(found.name, found.fix, kindOf(found))
        fly(found.fix)
    }

    // Back, by the system's gesture, previews closing the picker under the finger.
    val back = rememberPredictiveBack(onBack = onDismiss)

    // What is typed in the search: while there is any, the list is what is called that,
    // nearest the pin first; null while it is looked up.
    val query = rememberTextFieldState()
    var results by remember { mutableStateOf<List<Found>?>(emptyList()) }
    val keyboard = LocalSoftwareKeyboardController.current
    // Back clears the search before it closes the picker.
    BackHandler(enabled = query.text.isNotEmpty()) { query.clearText() }

    // Where the pin has come to rest: what is around it, to name a new place after, and
    // the name of the nearest of them for it until one is typed.
    var landmarks by remember { mutableStateOf<List<Found>>(emptyList()) }
    // Around the pin, or the known place chosen, so either has what is near it to choose instead.
    val pinned = when (val chosen = pick) {
        is PlaceChoice.New -> chosen.fix
        is PlaceChoice.Known -> places[chosen.id]?.fix()
        PlaceChoice.None -> null
    }
    // What is shown stays until what is around the new point has come, and a pin come to rest
    // a step from where it was looked around, as after flying to where the phone is, doesn't look again.
    var lookedAround by remember { mutableStateOf<Fix?>(null) }
    LaunchedEffect(pinned) {
        val at = pinned ?: return@LaunchedEffect
        val last = lookedAround
        if (last != null && landmarks.isNotEmpty() && distance(last, at) < SAME_SPOT_METRES) return@LaunchedEffect
        delay(300)
        val around = search.around(context, at)
        lookedAround = at
        landmarks = around.landmarks.take(LANDMARKS)
        val new = pick as? PlaceChoice.New
        if (!named && new != null && new.fix == at) {
            name = around.name ?: coordinatesName(at)
            pick = new.copy(name = name, kind = if (kindChosen) new.kind else around.kind)
        }
    }
    // Where the search looks from, read when it runs: the pin may only come to rest after it starts.
    val searchFrom by rememberUpdatedState(pinned ?: opening ?: fix)
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString().trim() }.collectLatest { text ->
            if (text.isEmpty()) {
                results = emptyList()
                return@collectLatest
            }
            // Asked once typing pauses, so each word costs one of the day's searches, not each letter.
            delay(400)
            results = null
            val near = searchFrom
            results = if (near == null) emptyList() else runCatching { search.search(text, near) }.getOrDefault(emptyList())
        }
    }

    var dragging by remember { mutableStateOf(false) }
    // On a phone on its side the choice stands in a panel down the end, the whole height, the pin
    // at the middle of the map beside it (H08).
    val sideways = isPhoneLandscape()
    val card: @Composable (Modifier) -> Unit = { cardModifier ->
        PlaceCard(
            tall = sideways,
            pick = pick,
            name = name,
            places = places,
            landmarks = landmarks,
            query = query,
            results = results,
            near = pinned ?: opening ?: fix,
            locating = locating,
            onFound = { found ->
                chooseFound(found)
                query.clearText()
                keyboard?.hide()
            },
            onKnown = choose,
            onKind = { kind ->
                when (val chosen = pick) {
                    is PlaceChoice.New -> {
                        kindChosen = true
                        pick = chosen.copy(kind = kind)
                    }
                    // A known place keeps a kind: the one it has chosen again is no change.
                    is PlaceChoice.Known -> pick = chosen.copy(kind = kind.takeUnless { it == PlaceKind.of(places[chosen.id]?.kind) })
                    PlaceChoice.None -> Unit
                }
            },
            onName = {
                named = true
                typedName = true
                name = it
                (pick as? PlaceChoice.New)?.let { new -> pick = new.copy(name = it) }
            },
            onNone = { onPick(PlaceChoice.None) },
            onConfirm = { onPick(pick) },
            modifier = cardModifier,
        )
    }
    val locateButton: @Composable () -> Unit = {
        val locateLabel = stringResource(R.string.action_locate)
        Tooltip(locateLabel) {
            FloatingActionButton(
                onClick = askToLocate,
                // Round on the floating surface, as the map's own controls are, quieter
                // than the sheet's one action.
                containerColor = SurfaceLevel.Floating.fill(),
                contentColor = tokens.tx,
                shape = Corners.full,
                // Named on the button, so it keeps its name while the indicator stands in for the icon.
                modifier = Modifier.semantics { contentDescription = locateLabel }.testTag("locate"),
            ) {
                // A loading indicator while it finds where the phone is, as the map tab's button has.
                Crossfade(locating, animationSpec = AppTheme.motionScheme.fastEffectsSpec(), label = "locating") { busy ->
                    if (busy) {
                        Spinner(Modifier.size(32.dp), color = LocalContentColor.current)
                    } else {
                        Icon(AppIcons.Locate, contentDescription = null)
                    }
                }
            }
        }
    }
    val mapArea: @Composable (Modifier) -> Unit = { areaModifier ->
        Box(areaModifier) {
            PickerMap(
                places = places,
                chosen = (pick as? PlaceChoice.Known)?.id,
                target = target,
                onCenter = { latitude, longitude, byUser ->
                    val at = Fix(latitude, longitude)
                    // Dragged, the pin is a new place where it stands; a new place flown to an address stays one there.
                    if (byUser || pick is PlaceChoice.New) pick = PlaceChoice.New(name, at, (pick as? PlaceChoice.New)?.kind)
                    if (byUser && !typedName) named = false
                },
                onPlace = choose,
                modifier = Modifier.fillMaxSize().testTag("place_map"),
                onDrag = { dragging = it },
            )
            // The pin's point at the map's middle; a known place chosen shows as its own dot instead.
            val motion = AppTheme.motionScheme
            AnimatedVisibility(
                pick !is PlaceChoice.Known,
                enter = scaleIn(motion.fastSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeIn(motion.fastEffectsSpec()),
                exit = scaleOut(motion.fastSpatialSpec(), transformOrigin = TransformOrigin(0.5f, 1f)) + fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.Center).offset(y = -(PIN_HEIGHT / 2)),
            ) {
                val lift by animateDpAsState(if (dragging) PIN_LIFT else 0.dp, motion.fastSpatialSpec(), label = "pin lift")
                PickPin(lift)
            }
            FloatingBack(onDismiss, Modifier.align(Alignment.TopStart))
            // On a phone on its side, at the map's top end; upright, it stands on the sheet instead.
            if (sideways) Box(Modifier.align(Alignment.TopEnd).padding(Space.l)) { locateButton() }
        }
    }
    if (sideways) {
        Row(Modifier.fillMaxSize().predictiveBackScale(back).background(AppColors.page)) {
            mapArea(Modifier.weight(1f).fillMaxHeight())
            Box(
                Modifier.width(PANEL_WIDTH).fillMaxHeight().background(AppColors.sheet)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                    .padding(top = Space.s),
            ) { card(Modifier) }
        }
    } else {
        // The choice in a sheet over the map's foot, the map reaching under its corners and the
        // pin at the middle of what shows of it: the two laid out together in one pass, so they
        // come and grow as one, never the map's space before the sheet.
        val sheetShape = AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        // While the keyboard is up the map keeps the height it had, the sheet rising over it,
        // rather than being laid out again each frame the keyboard moves.
        val ime = WindowInsets.ime
        val resting = remember { intArrayOf(0) }
        Layout(
            content = {
                mapArea(Modifier)
                Box(Modifier.fillMaxWidth().sheetSurface(AppColors.sheet, sheetShape)) { card(Modifier) }
                // At the end, just above the sheet's top, so it rises with the sheet over the keyboard.
                Box(Modifier.padding(Space.l)) { locateButton() }
            },
            modifier = Modifier.fillMaxSize().predictiveBackScale(back).background(AppColors.page),
        ) { (map, choice, locate), constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val sheet = choice.measure(constraints.copy(minWidth = width, minHeight = 0))
            val fitted = (height - sheet.height + SHEET_CORNER.roundToPx()).coerceIn(0, height)
            if (ime.getBottom(this) == 0 || resting[0] == 0) resting[0] = fitted
            val shown = map.measure(Constraints.fixed(width, resting[0]))
            val button = locate.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(width, height) {
                shown.place(0, 0)
                sheet.place(0, height - sheet.height)
                button.place(width - button.width, (height - sheet.height - button.height).coerceAtLeast(0))
            }
        }
    }
}

/** How wide the choice's panel stands down the end on a phone on its side. */
private val PANEL_WIDTH = 360.dp

/**
 * The pin: an ink head with a dot of the page in it on a thin stem, whose point is where the
 * place is; [lift] raises head and stem off the map while it is dragged, a faint shadow left
 * where the point will land.
 */
@Composable
private fun PickPin(lift: Dp) {
    val ink = tokens.tx
    val page = tokens.bg
    Canvas(Modifier.size(PIN_WIDTH, PIN_HEIGHT)) {
        val middle = size.width / 2
        val shadow = (lift / PIN_LIFT).coerceIn(0f, 1f)
        drawOval(
            ink.copy(alpha = 0.15f + 0.1f * shadow),
            topLeft = Offset(middle - 4.dp.toPx(), size.height - 3.dp.toPx()),
            size = Size(8.dp.toPx(), 3.dp.toPx()),
        )
        val up = lift.toPx()
        val head = 11.dp.toPx()
        drawRoundRect(
            ink,
            topLeft = Offset(middle - 1.dp.toPx(), head * 1.6f - up),
            size = Size(2.dp.toPx(), size.height - 1.5.dp.toPx() - head * 1.6f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        drawCircle(ink, head, Offset(middle, head + 1.dp.toPx() - up))
        drawCircle(page, 4.dp.toPx(), Offset(middle, head + 1.dp.toPx() - up))
    }
}

/** One row of the list under the map: the choice, a known place or one found there. */
private sealed interface Entry {
    data object Chosen : Entry
    data class Known(val id: String, val metres: Double) : Entry
    data class Around(val found: Found) : Entry
}

/**
 * Under the map: the search, then one list, the choice first and ticked, then the known
 * places close by and what is found around the pin, or while something is typed what is
 * called that; and the buttons to keep it. The choice tapped again names a new place; its
 * kind is a menu at its end.
 */
@Composable
private fun PlaceCard(
    /** In the panel down the end: the list takes the height, the buttons at its foot. */
    tall: Boolean,
    pick: PlaceChoice,
    name: String,
    places: Map<String, Place>,
    landmarks: List<Found>,
    query: TextFieldState,
    results: List<Found>?,
    near: Fix?,
    locating: Boolean,
    onFound: (Found) -> Unit,
    onKnown: (String) -> Unit,
    onKind: (PlaceKind) -> Unit,
    onName: (String) -> Unit,
    onNone: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // What the keyboard adds under the sheet, past the navigation bar the sheet already keeps clear of.
    val ime = WindowInsets.ime.exclude(WindowInsets.navigationBars)
    val typed = query.text.toString().trim()
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(pick::class, (pick as? PlaceChoice.Known)?.id) { if (pick !is PlaceChoice.New) renaming = false }
    val chosenId = (pick as? PlaceChoice.Known)?.id
    val rows: List<Entry> = when {
        typed.isNotEmpty() -> results.orEmpty().map(Entry::Around)
        pick == PlaceChoice.None -> emptyList()
        else -> buildList {
            add(Entry.Chosen)
            near?.let { at ->
                nearestPlaces(places, at, limit = KNOWN_NEARBY + 1)
                    .filter { it.first != chosenId && it.second <= NEARBY_METRES }
                    .take(KNOWN_NEARBY)
                    .forEach { (id, metres) -> add(Entry.Known(id, metres)) }
            }
            landmarks.filter { it.name != (pick as? PlaceChoice.New)?.name }
                .sortedBy { distance(at = near, found = it) }
                .forEach { add(Entry.Around(it)) }
        }
    }
    Column(modifier.fillMaxWidth().then(if (tall) Modifier.fillMaxHeight() else Modifier).navigationBarsPadding().imePadding().padding(top = Space.l, bottom = Space.m)) {
        Surface(
            shape = Corners.full,
            color = SurfaceLevel.Card.fill(),
            border = cardBorder(),
            modifier = Modifier.padding(horizontal = Space.xl).fillMaxWidth(),
        ) {
            SearchField(
                state = query,
                onSearch = { keyboard?.hide() },
                placeholder = { Text(stringResource(R.string.place_search_hint), maxLines = 1) },
                leadingIcon = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(AppIcons.Search, contentDescription = null) }
                },
                trailingIcon = if (query.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { query.clearText() }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag("place_search_field"),
            )
        }
        // One height whatever it holds, so the sheet stands still as what is around comes in:
        // grown, it would move the map's middle, and with it the pin, which looks again. The
        // keyboard rising takes its height from the list, frame by frame, down to two and a
        // half rows, so the sheet's top stays put until then and only rises the rest.
        Box(
            Modifier.fillMaxWidth()
                .then(
                    if (tall) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.layout { measurable, constraints ->
                            val height = maxOf(LIST_TYPING.roundToPx(), LIST_HEIGHT.roundToPx() - ime.getBottom(this))
                            val list = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                            layout(list.width, list.height) { list.place(0, 0) }
                        }
                    },
                )
                .padding(top = Space.s),
        ) {
            when {
                typed.isNotEmpty() && results == null || typed.isEmpty() && locating && pick == PlaceChoice.None ->
                    Spinner(Modifier.align(Alignment.Center).size(24.dp), color = tokens.tx3)
                typed.isNotEmpty() && rows.isEmpty() -> Text(
                    stringResource(R.string.search_none_for, typed),
                    style = AppTheme.typography.body,
                    color = tokens.tx2,
                    modifier = Modifier.align(Alignment.Center),
                )
                rows.isEmpty() -> Text(
                    stringResource(R.string.place_drag_hint),
                    style = AppTheme.typography.body,
                    color = tokens.tx2,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = Space.xl),
                )
                else -> LazyColumn(Modifier.testTag("place_rows")) {
                    itemsIndexed(rows, key = { index, row ->
                        when (row) {
                            Entry.Chosen -> "chosen"
                            is Entry.Known -> "known:${row.id}"
                            is Entry.Around -> "$index:${row.found.name}@${row.found.fix}"
                        }
                    }) { index, row ->
                        when (row) {
                            Entry.Chosen -> ChosenRow(pick, name, places, renaming, onRename = { renaming = true }, onName = onName, onDone = { renaming = false }, onKind = onKind, modifier = Modifier.animateItem())
                            is Entry.Known -> PlaceRow(
                                places.getValue(row.id).name,
                                listOfNotNull(
                                    stringResource(R.string.place_visited),
                                    PlaceKind.of(places.getValue(row.id).kind)?.let { stringResource(it.label) },
                                    formatDistance(row.metres),
                                ),
                                onClick = { onKnown(row.id) },
                                modifier = Modifier.animateItem().testTag("nearby_${row.id}"),
                            )
                            is Entry.Around -> PlaceRow(
                                row.found.name,
                                listOfNotNull(
                                    kindOf(row.found)?.let { stringResource(it.label) },
                                    near?.let { formatDistance(distance(it, row.found.fix)) },
                                    row.found.address.takeIf { typed.isNotEmpty() },
                                ),
                                onClick = { onFound(row.found) },
                                modifier = Modifier.animateItem().testTag("landmark_$index"),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = Space.xl).padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
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

/** How far [found] is from [at], or nowhere when there is no [at]. */
private fun distance(at: Fix?, found: Found): Double = at?.let { distance(it, found.fix) } ?: 0.0

/** A place to choose, a row of the list: its name, and what it is and how far in a line under it. */
@Composable
private fun PlaceRow(name: String, about: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1) },
        supportingContent = about.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · "), maxLines = 1) } },
        leadingContent = { Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx3) },
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = Space.s),
    )
}

/**
 * The choice, first in the list on the accent's soft tint and ticked: a known place, or a new
 * one at the pin, which tapped is named in a field in its place. Its kind is a menu at its end.
 */
@Composable
private fun ChosenRow(
    pick: PlaceChoice,
    name: String,
    places: Map<String, Place>,
    renaming: Boolean,
    onRename: () -> Unit,
    onName: (String) -> Unit,
    onDone: () -> Unit,
    onKind: (PlaceKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = (pick as? PlaceChoice.Known)?.let { places[it.id] }
    val kind = when (pick) {
        is PlaceChoice.Known -> pick.kind ?: PlaceKind.of(known?.kind)
        is PlaceChoice.New -> pick.kind
        PlaceChoice.None -> null
    }
    var kinds by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.s).background(tokens.accentSoft, AppTheme.shapes.medium)
            .clickable(enabled = pick is PlaceChoice.New && !renaming, onClick = onRename)
            .padding(start = Space.l, end = Space.xs).heightIn(min = 64.dp).testTag("chosen_place"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Pin, contentDescription = null, tint = tokens.accent)
        Spacer(Modifier.width(Space.l))
        if (renaming && pick is PlaceChoice.New) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            // Named, it goes back to a row however the naming ends: the keyboard's done, back,
            // the keyboard put away, or a tap anywhere else that takes the focus.
            BackHandler(onBack = onDone)
            val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            var keyboardCame by remember { mutableStateOf(false) }
            LaunchedEffect(keyboardUp) { if (keyboardUp) keyboardCame = true else if (keyboardCame) onDone() }
            var focused by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.place_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.weight(1f).padding(vertical = Space.s).focusRequester(focus)
                    .onFocusChanged { if (focused && !it.isFocused) onDone() else focused = it.isFocused }
                    .testTag("place_name"),
            )
        } else {
            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                Text(
                    when (pick) {
                        is PlaceChoice.Known -> known?.name ?: pick.id
                        is PlaceChoice.New -> name.ifBlank { coordinatesName(pick.fix) }
                        PlaceChoice.None -> ""
                    },
                    style = AppTheme.typography.body.strong,
                    maxLines = 1,
                )
                Text(
                    stringResource(if (pick is PlaceChoice.Known) R.string.place_visited else R.string.place_at_pin),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                )
            }
        }
        Box {
            TextButton(onClick = { kinds = true }, modifier = Modifier.testTag("place_kind")) {
                Text(kind?.let { stringResource(it.label) } ?: stringResource(R.string.place_kind))
            }
            DropdownMenu(kinds, onDismissRequest = { kinds = false }) {
                PlaceKind.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.label)) },
                        onClick = {
                            onKind(entry)
                            kinds = false
                        },
                        trailingIcon = if (entry == kind) {
                            { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** What the kind is called on screen. */
internal val PlaceKind.label: Int
    get() = when (this) {
        PlaceKind.CITY -> R.string.place_kind_city
        PlaceKind.TOWN -> R.string.place_kind_town
        PlaceKind.REGION -> R.string.place_kind_region
        PlaceKind.SCENIC -> R.string.place_kind_scenic
        PlaceKind.FOOD -> R.string.place_kind_food
        PlaceKind.SHOPPING -> R.string.place_kind_shopping
        PlaceKind.STAY -> R.string.place_kind_stay
        PlaceKind.TRANSPORT -> R.string.place_kind_transport
    }

@Composable
internal fun formatDistance(metres: Double): String =
    if (metres < 1000) stringResource(R.string.distance_metres, metres.toInt()) else stringResource(R.string.distance_kilometres, metres / 1000)

internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private const val LANDMARKS = 8
/** How near the pin can come to rest to where it was looked around and keep what was found there. */
private const val SAME_SPOT_METRES = 20.0
/** How tall the list under the map stands: five rows, and at least two and a half over the keyboard. */
private val LIST_HEIGHT = 320.dp
private val LIST_TYPING = 168.dp
private const val KNOWN_NEARBY = 3
private val PIN_WIDTH = 28.dp
private val PIN_HEIGHT = 46.dp
/** How far the map reaches under the sheet: its corners' radius, Material 3's extra large. */
private val SHEET_CORNER = Radius.large

/** How far the pin lifts off the map while it is dragged. */
private val PIN_LIFT = 8.dp
