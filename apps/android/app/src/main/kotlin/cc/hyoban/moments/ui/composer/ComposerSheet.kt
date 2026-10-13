package cc.hyoban.moments.ui.composer

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Timestamps
import cc.hyoban.moments.data.spec.Ulid
import cc.hyoban.moments.ui.moments.formatOccurredAt
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * The composer: a note that slides up over the list. The words are the hero; where and
 * when are worked out and shown as chips to correct, the newest photos sit a tap away,
 * and one button saves. Editing an existing Moment is the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerSheet(container: AppContainer, uid: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The sheet comes and goes with the keyboard, below; asking it to hide (a tap outside, back,
    // a swipe) only asks it to leave.
    var leaving by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    // The sheet starts out open and doesn't slide in on its own: the keyboard brings it up.
    val sheetState = remember {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            initialValue = SheetValue.Expanded,
            confirmValueChange = { value ->
                if (value != SheetValue.Hidden || leaving) true else false.also { asked = true }
            },
        )
    }
    val places by container.repository.places.collectAsStateWithLifecycle()
    val copyPhotos by container.settings.copyPhotos.collectAsStateWithLifecycle(initialValue = false)
    val existing = remember(uid) { uid?.let(container.repository::moment) }
    val state = remember(uid) { ComposerState(existing) }
    val focus = remember { FocusRequester() }

    // The sheet rides up on the keyboard: one movement, the sheet's top meeting its place as the
    // keyboard meets its own; what the keyboard has risen is already in the sheet's padding. With
    // no keyboard coming (a hardware one, or a permission dialog taking the focus) it slides up
    // by itself.
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val alone = remember { Animatable(0f) }
    var risen by remember { mutableStateOf(false) }
    fun rise(): Float {
        if (risen) return 1f
        val target = imeTarget.getBottom(density)
        return if (target > 0) (ime.getBottom(density).toFloat() / target).coerceAtMost(1f) else alone.value
    }

    // Going, the keyboard goes down and takes the sheet with it. The sheet's own slide out runs
    // underneath, for the scrim to fade with it, and is taken back out of where the sheet is drawn;
    // a sheet already swiped partway down just carries on. The keyboard's top is wherever is
    // higher of the inset and the sheet's window: some phones animate the inset as it goes, others
    // drop it at once and grow the window, which stopped at the keyboard, back to the screen's foot.
    var windowHeight by remember { mutableIntStateOf(0) }
    var sheetHeight by remember { mutableIntStateOf(0) }
    var leaveFrom by remember { mutableIntStateOf(0) }
    var leaveBottom by remember { mutableFloatStateOf(0f) }
    var leaveHeight by remember { mutableIntStateOf(0) }
    var leaveOffset by remember { mutableFloatStateOf(0f) }
    var sheetFocus by remember { mutableStateOf<FocusManager?>(null) }
    fun keyboardTop(): Float = minOf(windowHeight.toFloat(), leaveBottom - ime.getBottom(density))
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        val dragged = sheetState.requireOffset() > windowHeight - sheetHeight + 1
        leaveFrom = if (dragged || !risen) 0 else ime.getBottom(density)
        leaveBottom = sheetState.requireOffset() + sheetHeight + leaveFrom
        leaveHeight = sheetHeight
        leaveOffset = sheetState.requireOffset()
        sheetFocus?.clearFocus(force = true)
        scope.launch {
            val hiding = launch { sheetState.hide() }
            if (leaveFrom > 0) withTimeoutOrNull(600) { snapshotFlow { keyboardTop() }.first { it >= leaveBottom - 1 } }
            hiding.join()
            then()
        }
    }
    fun shift(height: Float): Float {
        val now = ime.getBottom(density)
        return when {
            leaving && leaveFrom > 0 -> {
                val top = keyboardTop()
                val left = (leaveBottom - top) / leaveFrom
                top - leaveHeight * left - sheetState.requireOffset()
            }
            leaving -> 0f
            else -> (height - now) * (1f - rise())
        }
    }

    // How far the sheet is in, for the scrim: the sheet's own fades in a snap of its own and
    // ahead of the keyboard, this one moves with the sheet.
    fun shown(): Float = when {
        leaving && leaveFrom > 0 -> (leaveBottom - keyboardTop()) / leaveFrom
        leaving -> (windowHeight - sheetState.requireOffset()) / (windowHeight - leaveOffset).coerceAtLeast(1f)
        else -> rise()
    }.coerceIn(0f, 1f)

    LaunchedEffect(Unit) {
        focus.requestFocus()
        if (existing == null) state.text = container.settings.draft()
        val keyboard = withTimeoutOrNull(400) { snapshotFlow { imeTarget.getBottom(density) }.first { it > 0 } }
        if (keyboard == null) alone.animateTo(1f, tween(250))
        snapshotFlow { rise() }.first { it >= 1f }
        risen = true
    }

    // Where the phone is, and the first guess at the place: the nearest known one, else what
    // the map calls the spot, which the picker offers to save as a new one.
    suspend fun locate() {
        state.locating = true
        val fix = container.location.get()
        state.here = fix?.let { it.latitude to it.longitude }
        state.locating = false
        if (fix == null || state.placeIds.isNotEmpty() || state.placesTouched) return
        val known = container.repository.nearestPlace(fix.latitude, fix.longitude)
        if (known != null) state.placeIds.add(known.first)
        else state.suggestion = container.placeNames.near(fix.latitude, fix.longitude).firstOrNull()
    }
    LaunchedEffect(Unit) {
        if (existing == null && container.location.hasPermission()) locate()
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { locate() }
    }
    LaunchedEffect(Unit) {
        if (existing == null && !container.location.hasPermission()) askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // The newest photos, once the gallery may be read.
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { state.recent = container.recentPhotos.latest() }
    }
    LaunchedEffect(Unit) {
        if (container.recentPhotos.hasPermission()) state.recent = container.recentPhotos.latest()
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris: List<Uri> ->
        uris.forEach { if (it !in state.picked) state.picked.add(it) }
    }

    fun dismiss() {
        if (existing == null) scope.launch { container.settings.setDraft(state.text) }
        onDismiss()
    }

    fun save() = scope.launch {
        val imported = state.picked.mapIndexed { index, uri ->
            container.photoImport.import(uri, state.time + index + 1, copyPhotos)
        }
        // Photos added later than the moment they show: the earliest shot is when it happened.
        val time = if (!state.timeTouched && imported.any { it.takenAt != null }) {
            imported.mapNotNull { it.takenAt }.minOf { Timestamps.toEpochMillis(it) }
        } else {
            state.time
        }
        val now = Timestamps.now()
        val record = MomentRecord(
            uid = existing?.uid ?: Ulid.new(time),
            occurredAt = Timestamps.at(time),
            places = state.placeIds.toList(),
            text = state.text.trim(),
            hidden = state.hidden,
            media = state.keptMedia + imported,
            trip = existing?.trip,
            published = existing?.published,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            extra = existing?.extra ?: JsonObject(emptyMap()),
        )
        container.repository.saveMoment(record)
        if (existing == null) container.settings.setDraft("")
        leave(onDismiss)
    }

    LaunchedEffect(asked) { if (asked) leave(::dismiss) }

    val scrim = BottomSheetDefaults.ScrimColor
    Box(Modifier.fillMaxSize().drawBehind { drawRect(scrim, alpha = scrim.alpha * shown()) })

    ModalBottomSheet(
        onDismissRequest = { leave(::dismiss) },
        sheetState = sheetState,
        scrimColor = Color.Transparent,
        modifier = Modifier
            .onGloballyPositioned { layout ->
                windowHeight = layout.parentLayoutCoordinates?.size?.height ?: 0
                sheetHeight = layout.size.height
            }
            .graphicsLayer { translationY = shift(size.height) },
    ) {
        val focusManager = LocalFocusManager.current
        SideEffect { sheetFocus = focusManager }
        when (val picking = state.pickingPlace) {
            true -> PlacePicker(
                container = container,
                here = state.here,
                selected = state.placeIds,
                onToggle = { id ->
                    state.placesTouched = true
                    if (id in state.placeIds) state.placeIds.remove(id) else state.placeIds.add(id)
                },
                onCreate = { id -> state.placesTouched = true; state.placeIds.add(id) },
                onDone = { state.pickingPlace = false },
            )
            false -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val suggestion = state.suggestion.takeIf { state.placeIds.isEmpty() && !state.placesTouched }
                    AssistChip(
                        // A guess opens the picker with it filled in, to save or correct.
                        onClick = { state.pickingPlace = true },
                        leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                        label = {
                            Text(
                                when {
                                    state.placeIds.isNotEmpty() -> state.placeIds.joinToString("、") { places[it]?.name ?: it }
                                    state.locating -> stringResource(R.string.hint_locating)
                                    suggestion != null -> stringResource(R.string.hint_place_guess, suggestion)
                                    else -> stringResource(R.string.action_add_place)
                                },
                            )
                        },
                    )
                    AssistChip(
                        onClick = { state.pickingTime = true },
                        label = {
                            Text(
                                if (!state.timeTouched && abs(System.currentTimeMillis() - state.time) < 10 * 60_000) stringResource(R.string.time_just_now)
                                else formatOccurredAt(Timestamps.at(state.time)),
                            )
                        },
                    )
                }

                BasicTextField(
                    value = state.text,
                    onValueChange = { state.text = it },
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 12.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (state.text.isEmpty()) {
                                Text(stringResource(R.string.hint_text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )

                PhotoStrip(
                    kept = state.keptMedia,
                    recent = state.recent,
                    picked = state.picked,
                    canReadGallery = container.recentPhotos.hasPermission(),
                    onToggle = { uri -> if (uri in state.picked) state.picked.remove(uri) else state.picked.add(uri) },
                    onRemoveKept = { state.keptMedia.remove(it) },
                    onAskGallery = { askPhotos.launch(container.recentPhotos.permissions) },
                    onPick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )

                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(checked = state.hidden, onCheckedChange = { state.hidden = it }) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.label_hidden),
                            tint = if (state.hidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.hidden) Text(stringResource(R.string.label_hidden_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f))
                    Button(onClick = { save() }, enabled = state.text.isNotBlank() || state.picked.isNotEmpty() || state.keptMedia.isNotEmpty()) {
                        Text(stringResource(R.string.action_save_moment))
                    }
                }
            }
        }
    }

    if (state.pickingTime) {
        TimeDialog(initial = state.time, onDismiss = { state.pickingTime = false }, onPick = { state.time = it; state.timeTouched = true; state.pickingTime = false })
    }
}

/** What the composer holds while it's open. */
class ComposerState(existing: MomentRecord?) {
    var text by mutableStateOf(existing?.text ?: "")
    var time by mutableStateOf(existing?.let { Timestamps.toEpochMillis(it.occurredAt) } ?: System.currentTimeMillis())
    var timeTouched by mutableStateOf(existing != null)
    var hidden by mutableStateOf(existing?.hidden ?: false)
    val placeIds = mutableStateListOf<String>().apply { existing?.places?.let(::addAll) }
    var placesTouched by mutableStateOf(existing != null)
    val keptMedia = mutableStateListOf<MomentMedia>().apply { existing?.media?.let(::addAll) }
    val picked = mutableStateListOf<Uri>()
    var recent by mutableStateOf<List<Uri>>(emptyList())
    var here by mutableStateOf<Pair<Double, Double>?>(null)
    /** What the map calls where the phone is, when no known place is near. */
    var suggestion by mutableStateOf<String?>(null)
    var locating by mutableStateOf(false)
    var pickingPlace by mutableStateOf(false)
    var pickingTime by mutableStateOf(false)
}

/** The photos already on the Moment, the newest in the gallery to tap, and the way to the full picker. */
@Composable
private fun PhotoStrip(
    kept: List<MomentMedia>,
    recent: List<Uri>,
    picked: List<Uri>,
    canReadGallery: Boolean,
    onToggle: (Uri) -> Unit,
    onRemoveKept: (MomentMedia) -> Unit,
    onAskGallery: () -> Unit,
    onPick: () -> Unit,
) {
    val tile = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))

    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(kept, key = { it.uid }) { item ->
            Box(tile.clickable { onRemoveKept(item) }) {
                AsyncImage(model = item.local?.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                SelectedMark()
            }
        }
        items(recent, key = { it.toString() }) { uri ->
            val selected = uri in picked
            Box(
                tile
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { onToggle(uri) },
            ) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                if (selected) SelectedMark()
            }
        }
        if (!canReadGallery) {
            item {
                Box(tile.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).clickable(onClick = onAskGallery), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.action_allow_gallery), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
        }
        item {
            Box(tile.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).clickable(onClick = onPick), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_photos), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectedMark() {
    Box(Modifier.padding(4.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).then(Modifier), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)))
    }
}
