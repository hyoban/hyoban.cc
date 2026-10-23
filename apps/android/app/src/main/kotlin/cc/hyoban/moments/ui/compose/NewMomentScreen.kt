package cc.hyoban.moments.ui.compose

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Around
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PhotoInfo
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.SAME_PLACE_METRES
import cc.hyoban.moments.data.local.SharedPlace
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.currentLocation
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.local.nearestPlaces
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.parseStamp
import cc.hyoban.moments.data.local.stamp
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.showcase
import cc.hyoban.moments.ui.components.isCompactHeight
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.Group
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import coil3.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Moment written on the phone: its words, the photos picked for it, and when and where,
 * worked out from the photos or else from now and where the phone is, each open to
 * change. Saved, it is kept on the phone, and reaches the site only when the changes are
 * published. Given a Moment to [edit], it starts from that one as it was saved: one of the
 * phone's is kept as changed, as is one of the site's, with the token, until it is published.
 * One being changed can be deleted here too, at the foot of the page, and then [onDeleted].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewMomentScreen(container: AppContainer, onBack: () -> Unit, onSaved: () -> Unit, edit: String? = null, onDeleted: () -> Unit = onBack) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val places by container.repository.places.collectAsStateWithLifecycle()

    // The Moment being changed, and its photos, time and place as it was saved.
    val canPublish = container.canPublish
    val original = remember(edit) { edit?.let { container.repository.moment(it) }?.takeIf { it.published == null || canPublish } }
    val published = original?.published != null
    // Each photo it had by where it is shown from: the phone's copy, or the site's still.
    val originalMedia = remember(original) {
        original?.media.orEmpty().associateBy { media ->
            Uri.parse(media.local?.uri ?: container.mediaUrls.still(media, PHOTO_WIDTH) ?: "media:${media.uid}")
        }
    }
    val originalTime = remember(original) { original?.let { parseStamp(it.occurredAt) } }
    val originalPlace = remember(original) { original?.let { it.places.firstOrNull()?.let(PlaceChoice::Known) ?: PlaceChoice.None } }

    var text by rememberSaveable { mutableStateOf(original?.text.orEmpty()) }
    var photos by remember { mutableStateOf(originalMedia.keys.toList()) }
    // The photos kept for the record, never picked for a cover: see MomentMedia.utility.
    val originalUtility = remember(originalMedia) { originalMedia.filterValues { !it.showcase }.keys }
    var utility by remember { mutableStateOf(originalUtility) }
    val infos = remember { mutableStateMapOf<Uri, PhotoInfo>() }
    val openedAt = remember { OffsetDateTime.now() }
    var chosenTime by remember { mutableStateOf(originalTime) }
    var chosenPlace by remember { mutableStateOf(originalPlace) }
    // The place last shared in, to say where the place came from while it is still that one.
    var sharedFrom by remember { mutableStateOf<PlaceChoice?>(null) }
    var deviceFix by remember { mutableStateOf<Fix?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var geocoded by remember { mutableStateOf<Pair<Fix, Around>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    // Saving with nothing written says so at the words, as a full-screen dialog's errors
    // are shown where they are; a save that fails says why in a dialog.
    var emptyError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val publishing by container.publishing.collectAsStateWithLifecycle()

    LaunchedEffect(photos) {
        photos.filter { it !in infos && it !in originalMedia }.forEach { infos[it] = Photos.read(context, it) }
    }

    // When: the earliest photo's time, else, when none says, the earliest a photo's file was
    // made, else when the page was opened.
    val photoTime = (photos.mapNotNull { infos[it]?.takenAt }.ifEmpty { photos.mapNotNull { infos[it]?.savedAt } })
        .minByOrNull { it.toInstant() }
    val time = chosenTime ?: photoTime ?: openedAt
    val timeSource = when {
        original != null && chosenTime == originalTime -> R.string.source_saved
        chosenTime != null -> R.string.source_chosen
        photoTime != null -> R.string.source_photo
        else -> R.string.source_now
    }

    // Where: just where the first photo that says was taken, else where the phone is, as a
    // new place named after what is around it; a known place nearby is chosen on the map.
    val photoFix = photos.firstNotNullOfOrNull { infos[it]?.fix }
    val fix = photoFix ?: deviceFix
    LaunchedEffect(fix) {
        if (fix != null && geocoded?.first != fix) geocoded = fix to container.placeSearch.around(context, fix)
    }
    val around = geocoded?.takeIf { it.first == fix }?.second
    val suggestedName = fix?.let { at -> around?.name ?: coordinatesName(at) }
    val place: PlaceChoice = chosenPlace ?: if (fix != null && suggestedName != null) PlaceChoice.New(suggestedName, fix, around?.kind) else PlaceChoice.None

    // A place shared in from a map, now or while this page is open, is the Moment's place.
    val shared by container.sharedPlace.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        // A Moment being changed leaves a shared place to a new one.
        val place = shared?.takeIf { original == null } ?: return@LaunchedEffect
        container.takeSharedPlace()
        chosenPlace = sharedChoice(place, places)
        sharedFrom = chosenPlace
        editingPlace = false
    }

    // Photos shared in from the gallery, now or while this page is open, join the Moment's.
    val sharedPhotos by container.sharedPhotos.collectAsStateWithLifecycle()
    LaunchedEffect(sharedPhotos) {
        if (original != null || sharedPhotos.isEmpty()) return@LaunchedEffect
        photos = (photos + container.takeSharedPhotos()).distinct().take(MAX_PHOTOS)
    }

    val locate: () -> Unit = {
        locating = true
        scope.launch {
            deviceFix = currentLocation(context)?.let { Fix(it.latitude, it.longitude) }
            locating = false
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else locationDenied = true
    }
    LaunchedEffect(Unit) {
        // A Moment being changed has its place; the map finds the phone when asked.
        if (original != null) return@LaunchedEffect
        if (LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            locate()
        } else {
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }

    // The documents picker rather than the photo picker: only what it hands over keeps where
    // the photo was taken.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        photos = (photos + picked).distinct().take(MAX_PHOTOS)
    }
    val pickPhotos = { picker.launch(arrayOf("image/*")) }
    // Where a photo was taken is kept from the app until it may see it: asked for once, before
    // the first pick ever; declined, the photos still give their time and the phone its place.
    val mediaLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pickPhotos() }
    val addPhotos = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !container.settings.askedMediaLocation &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            container.settings.askedMediaLocation = true
            mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            pickPhotos()
        }
    }
    // The tile opens the phone's camera app itself, with all it can do; what it takes goes to
    // the gallery, to be added from there.
    val openCamera: () -> Unit = {
        runCatching { context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
            .onFailure { saveError = context.getString(R.string.error_no_camera) }
    }

    val written = text.isNotBlank() || photos.isNotEmpty()
    // What leaving would lose: anything written, or, for a Moment being changed, any change.
    val changed = if (original == null) {
        written
    } else {
        text != original.text || photos != originalMedia.keys.toList() || utility.intersect(photos.toSet()) != originalUtility ||
            chosenTime != originalTime || chosenPlace != originalPlace
    }
    val leave = { if (changed) confirmingDiscard = true else onBack() }
    BackHandler(enabled = changed && !saving) { confirmingDiscard = true }

    // Kept on the phone, one of the site's as changed too; none of it goes to the API until
    // the changes are published.
    val save: () -> Unit = save@{
        if (saving) return@save
        saving = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    // A place left as it was keeps all the Moment's places, as it may have more than one.
                    val places = if (original != null && chosenPlace == originalPlace) original.places else null
                    val (moment, newPlace) = buildMoment(context, container, text, photos, utility, infos, time, place, original, originalMedia, places)
                    if (original == null) container.addLocal(moment, newPlace) else container.updateLocal(moment, newPlace)
                }
                onSaved()
            } catch (error: Exception) {
                saving = false
                saveError = context.getString(R.string.error_save, failureReason(context, error))
            }
        }
    }

    LaunchedEffect(written) { if (written) emptyError = false }
    // A full-screen dialog, as Material 3 has a task with fields to fill: an X to leave, and
    // Save, never greyed out, telling what is missing rather than refusing to be tapped.
    Box(Modifier.fillMaxSize()) {
        SubPage(
            stringResource(if (original == null) R.string.title_new_moment else R.string.title_edit_moment),
            onBack = leave,
            modifier = Modifier.background(AppColors.page),
            close = true,
            actions = {
                TextButton(
                    onClick = {
                        when {
                            !written -> emptyError = true
                            !changed -> onBack()
                            else -> save()
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(end = Space.s).testTag("save"),
                ) { Text(stringResource(R.string.action_save)) }
            },
        ) {
            PageWidth(maxColumns = 1) { columns ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = columns.margin)
                        .padding(bottom = Space.xl),
                ) {
                    val focus = remember { FocusRequester() }
                    // A new Moment starts at its words; one being changed waits to be told what to change.
                    LaunchedEffect(Unit) { if (original == null) focus.requestFocus() }
                    Group(null, null) {
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text(stringResource(R.string.hint_moment_text)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            minLines = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            isError = emptyError,
                            // The error marked by an icon as well as the colour, as Material 3 asks of a field in error.
                            trailingIcon = if (emptyError) {
                                { Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            } else {
                                null
                            },
                            supportingText = if (emptyError) {
                                { Text(stringResource(R.string.error_empty_moment)) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("moment_text"),
                        )
                    }
                    Group(stringResource(R.string.section_photo), null) {
                        PhotoStrip(
                            photos,
                            onAdd = addPhotos,
                            onOpenCamera = openCamera,
                            utility = utility,
                            onToggleUtility = { uri -> utility = if (uri in utility) utility - uri else utility + uri },
                            onRemove = { removed -> photos = photos - removed },
                            onMove = { from, to -> photos = photos.toMutableList().apply { add(to, removeAt(from)) } },
                        )
                    }
                    val note = when {
                        published -> R.string.note_published
                        canPublish -> R.string.note_local_then_publish
                        else -> R.string.note_local_only
                    }
                    val whenRow: Segment = { shapes ->
                        SegmentedListItem(
                            onClick = { editingTime = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = { Text(stringResource(timeSource)) },
                            leadingContent = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            modifier = Modifier.testTag("moment_time"),
                        ) { Text(listOfNotNull(formatDay(stamp(time)), formatTime(stamp(time))).joinToString(" ")) }
                    }
                    val whereRow: Segment = { shapes ->
                        SegmentedListItem(
                            onClick = { editingPlace = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when {
                                            original != null && chosenPlace == originalPlace -> R.string.source_saved
                                            chosenPlace != null && chosenPlace == sharedFrom -> R.string.source_shared
                                            chosenPlace != null -> R.string.source_chosen
                                            photoFix != null -> R.string.source_photo
                                            fix != null -> R.string.source_location
                                            locating -> R.string.place_locating
                                            locationDenied -> R.string.place_no_permission
                                            else -> R.string.place_unknown
                                        },
                                    ),
                                )
                            },
                            leadingContent = { Icon(Icons.Rounded.Place, contentDescription = null) },
                            modifier = Modifier.testTag("moment_place"),
                        ) {
                            Text(
                                when (place) {
                                    is PlaceChoice.Known -> places[place.id]?.name ?: place.id
                                    is PlaceChoice.New -> place.name
                                    PlaceChoice.None -> stringResource(R.string.place_none)
                                },
                            )
                        }
                    }
                    SegmentedGroup(stringResource(R.string.section_when_where), stringResource(note), listOf(whenRow, whereRow))
                    // Deleting is rare, so it waits down here rather than over the Moment; its snackbar can undo it.
                    if (original != null && publishing == null) {
                        SegmentedGroup(
                            null,
                            null,
                            listOf { shapes ->
                                SegmentedListItem(
                                    onClick = {
                                        container.delete(original.uid)
                                        onDeleted()
                                    },
                                    shapes = shapes,
                                    enabled = !saving,
                                    colors = ListItemDefaults.segmentedColors(
                                        containerColor = LocalSurfaceLevel.current.raised.fill(),
                                        contentColor = MaterialTheme.colorScheme.error,
                                        leadingContentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    leadingContent = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                    modifier = Modifier.testTag("delete"),
                                ) { Text(stringResource(R.string.action_delete_moment)) }
                            },
                        )
                    }
                }
            }
        }

        if (editingTime) {
            TimeDialog(time, onDismiss = { editingTime = false }) {
                chosenTime = it
                editingTime = false
            }
        }
        // Over the whole page, back closing it before it leaves the page.
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            editingPlace,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeOut(motion.defaultEffectsSpec()),
        ) {
            PlacePicker(current = place, fix = fix, places = places, search = container.placeSearch, onDismiss = { editingPlace = false }) {
                chosenPlace = it
                editingPlace = false
            }
        }
    }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.action_confirm)) } },
        )
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.confirm_discard)) },
            text = { Text(stringResource(R.string.confirm_discard_note)) },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onBack() }, modifier = Modifier.testTag("discard")) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * A shared place as the Moment's: the known place it is, when one with its name is
 * within a stone's throw; else a new place under its name.
 */
private fun sharedChoice(place: SharedPlace, places: Map<String, Place>): PlaceChoice {
    val same = nearestPlaces(places, place.fix, limit = 3)
        .firstOrNull { (id, metres) -> metres <= SAME_PLACE_METRES && places.getValue(id).name == place.name }
    return same?.let { PlaceChoice.Known(it.first) } ?: PlaceChoice.New(place.name ?: coordinatesName(place.fix), place.fix)
}

/**
 * The photos picked, in a row, each with a button to take it out, and tiles to pick more
 * or open the camera app to take one.
 * The first is the Moment's cover, so they are put in order as Material 3 has content
 * reordered: held and then dragged, the one held lifted under the finger and the others
 * stepping aside; a screen reader moves one earlier or later instead.
 */
@Composable
private fun PhotoStrip(
    photos: List<Uri>,
    onAdd: () -> Unit,
    onOpenCamera: () -> Unit,
    utility: Set<Uri>,
    onToggleUtility: (Uri) -> Unit,
    onRemove: (Uri) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val currentMove by rememberUpdatedState(onMove)
    // The photo held, and how far it has been dragged from where it is laid out.
    var held by remember { mutableStateOf<Uri?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // The photo let go, gliding from under the finger into its place, still over the rest until it is there.
    var settling by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val liftSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val letGo: () -> Unit = {
        val dropped = held
        held = null
        settling = dropped
        settle = scope.launch {
            animate(offset, 0f, animationSpec = settleSpec) { value, _ -> offset = value }
            if (settling == dropped) settling = null
        }
    }
    val drag: (Float) -> Unit = drag@{ by ->
        val key = held?.toString() ?: return@drag
        offset += by
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return@drag
        val middle = (current.offset + current.size / 2 + offset).toInt()
        val target = items.firstOrNull { it.key != key && it.key != ADD_KEY && it.key != CAMERA_KEY && middle in it.offset until it.offset + it.size } ?: return@drag
        // The row keeps its place by its first item, which may be the one moving.
        val first = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        currentMove(current.index, target.index)
        // Still under the finger once laid out where it now goes.
        offset += current.offset - target.offset
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        // Held where it was in the same layout, rather than following the item it was anchored to.
        list.requestScrollToItem(first.first, first.second)
    }
    val photoShape = MaterialTheme.shapes.medium
    val earlier = stringResource(R.string.action_move_earlier)
    val later = stringResource(R.string.action_move_later)
    val options = stringResource(R.string.action_photo_options)
    // The photo whose menu is open, from a tap on it.
    var menuFor by remember { mutableStateOf<Uri?>(null) }
    LazyRow(
        Modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(photos, key = { _, uri -> uri.toString() }) { index, uri ->
            val lifted = held == uri
            val moving = lifted || settling == uri
            // Lifted over the rest at the dragged level, level 4, with the drag's state layer over
            // it, growing into that as it is picked up and settling back as it is put down.
            val lift by animateFloatAsState(if (lifted) 1f else 0f, liftSpec, label = "lift")
            val placement = (if (moving) Modifier.zIndex(1f) else Modifier.animateItem()).graphicsLayer {
                translationX = if (moving) offset else 0f
                val scale = 1f + (LIFTED_SCALE - 1f) * lift
                scaleX = scale
                scaleY = scale
                shadowElevation = Elevation.level4.toPx() * lift
                shape = photoShape
                clip = true
            }
            Box(
                placement.size(PHOTO_SIZE).clip(photoShape)
                    .pointerInput(uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                settle?.cancel()
                                settling = null
                                held = uri
                                offset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag(amount.x)
                            },
                            onDragEnd = letGo,
                            onDragCancel = letGo,
                        )
                    }
                    // A tap opens its menu; a long press is left to the drag above.
                    .pointerInput(uri) { detectTapGestures(onLongPress = {}, onTap = { menuFor = uri }) }
                    .semantics {
                        onClick(options) { menuFor = uri; true }
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(earlier) { onMove(index, index - 1); true }.takeIf { index > 0 },
                            CustomAccessibilityAction(later) { onMove(index, index + 1); true }.takeIf { index < photos.lastIndex },
                        )
                    }
                    .testTag("photo_$index"),
            ) {
                // The site's photos by their URL, the phone's by their Uri.
                AsyncImage(model = if (uri.scheme == "https" || uri.scheme == "http") uri.toString() else uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (lift > 0f) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = DRAGGED_LAYER * lift)))
                if (uri in utility) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(Space.s).size(UTILITY_DOT).clip(CircleShape).background(MediaOverlay.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.HideImage, contentDescription = stringResource(R.string.label_photo_utility), tint = MediaOverlay.content, modifier = Modifier.size(16.dp))
                    }
                }
                DropdownMenu(menuFor == uri, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_photo_utility)) },
                        leadingIcon = { Icon(Icons.Rounded.HideImage, contentDescription = null) },
                        trailingIcon = if (uri in utility) {
                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            menuFor = null
                            onToggleUtility(uri)
                        },
                        modifier = Modifier.semantics { toggleableState = ToggleableState(uri in utility) }.testTag("photo_utility_$index"),
                    )
                }
                // A small dot to see, in a corner of the 48dp Material 3 has anything tapped take.
                Box(
                    Modifier.align(Alignment.TopEnd).size(REMOVE_TARGET)
                        .clickable(
                            interactionSource = null,
                            indication = ripple(bounded = false, radius = REMOVE_TARGET / 2),
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.action_remove_photo),
                        ) { onRemove(uri) }
                        .testTag("remove_photo_$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(REMOVE_DOT).clip(CircleShape).background(MediaOverlay.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove_photo), tint = MediaOverlay.content, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (photos.size < MAX_PHOTOS) {
            item(key = ADD_KEY) {
                AddTile(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.action_add_photos), onAdd, Modifier.animateItem().testTag("add_photos"))
            }
            item(key = CAMERA_KEY) {
                AddTile(Icons.Rounded.PhotoCamera, stringResource(R.string.action_open_camera), onOpenCamera, Modifier.animateItem().testTag("open_camera"))
            }
        }
    }
}

/** A tile at the end of the photos that adds more to them. */
@Composable
private fun AddTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(PHOTO_SIZE).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The day, then the time of day on it, keeping the offset [time] has: the date picker
 * leads on to Material 3's time picker dialog, titled with the day chosen, which can go
 * back to it, and turns from the dial to typing the time and back, as its toggle does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(time: OffsetDateTime, onDismiss: () -> Unit, onPick: (OffsetDateTime) -> Unit) {
    // The date picker counts days as midnights in UTC.
    val dates = rememberDatePickerState(
        initialSelectedDateMillis = time.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val clock = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    var onClock by remember { mutableStateOf(false) }
    // Typing the time where the window is too short for the dial, a phone on its side, as
    // Material 3 has a time picker fall back to its input rather than scroll.
    val short = isCompactHeight()
    var mode by remember { mutableStateOf(if (short) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker) }
    val day = dates.selectedDateMillis?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate() } ?: time.toLocalDate()

    if (!onClock) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onClock = true }, modifier = Modifier.testTag("time_next")) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(dates) }
    } else {
        TimePickerDialog(
            onDismissRequest = onDismiss,
            title = { Text(formatDay(day.toString())) },
            confirmButton = {
                TextButton(onClick = {
                    onPick(OffsetDateTime.of(day, java.time.LocalTime.of(clock.hour, clock.minute), time.offset))
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { onClock = false }) { Text(stringResource(R.string.action_previous)) } },
            modeToggleButton = {
                TimePickerDialogDefaults.DisplayModeToggle(
                    onDisplayModeChange = {
                        mode = if (mode == TimePickerDisplayMode.Picker) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker
                    },
                    displayMode = mode,
                    modifier = Modifier.testTag("time_mode"),
                )
            },
        ) {
            if (mode == TimePickerDisplayMode.Picker) TimePicker(clock) else TimeInput(clock)
        }
    }
}

/**
 * The Moment as a record, its photos copied into the app's store first so it keeps them
 * whatever becomes of the gallery's, and the place it makes, if it makes one. Changing
 * [original], it keeps its id, when it was made and the photos it already had as they
 * were, and [keepPlaces] in place of the one chosen.
 */
private suspend fun buildMoment(
    context: Context,
    container: AppContainer,
    text: String,
    photos: List<Uri>,
    utility: Set<Uri>,
    infos: Map<Uri, PhotoInfo>,
    time: OffsetDateTime,
    place: PlaceChoice,
    original: MomentRecord? = null,
    originalMedia: Map<Uri, MomentMedia> = emptyMap(),
    keepPlaces: List<String>? = null,
): Pair<MomentRecord, Pair<String, Place>?> {
    val now = stamp(OffsetDateTime.now())
    val directory = File(container.localStore.root, "media")
    val media = photos.map { uri ->
        val kept = (uri in utility).takeIf { it }
        originalMedia[uri]?.let { return@map it.copy(utility = kept) }
        val uid = Ulid.next()
        val info = infos[uri] ?: Photos.read(context, uri)
        val copied = Photos.copy(context, uri, directory, uid)
        MomentMedia(
            uid = uid,
            type = MediaType.IMAGE,
            width = info.width,
            height = info.height,
            takenAt = info.takenAt?.let(::stamp),
            utility = kept,
            local = LocalMedia(uri = Uri.fromFile(copied.file).toString(), sha256 = copied.sha256, copied = true),
        )
    }
    val newPlace = (place as? PlaceChoice.New)?.let {
        newPlaceId() to Place(it.name.trim().ifEmpty { coordinatesName(it.fix) }, it.fix.latitude, it.fix.longitude, kind = it.kind?.value, createdAt = now)
    }
    val placeIds = keepPlaces ?: when (place) {
        is PlaceChoice.Known -> listOf(place.id)
        is PlaceChoice.New -> listOfNotNull(newPlace?.first)
        PlaceChoice.None -> emptyList()
    }
    val moment = original?.copy(occurredAt = stamp(time), places = placeIds, text = text.trim(), media = media, updatedAt = now)
        ?: MomentRecord(
            uid = Ulid.next(),
            occurredAt = stamp(time),
            places = placeIds,
            text = text.trim(),
            media = media,
            createdAt = now,
            updatedAt = now,
        )
    return moment to newPlace
}

private const val MAX_PHOTOS = 9
/** How wide the site's photos are fetched for the strip: its tiles, at a phone's density. */
private const val PHOTO_WIDTH = 480
private val PHOTO_SIZE = 96.dp
private val REMOVE_TARGET = 48.dp
private val REMOVE_DOT = 28.dp
private val UTILITY_DOT = 28.dp
private const val ADD_KEY = "add"
private const val CAMERA_KEY = "camera"
/** How much a photo held to move grows, and Material 3's dragged state layer over it. */
private const val LIFTED_SCALE = 1.06f
private const val DRAGGED_LAYER = 0.16f
