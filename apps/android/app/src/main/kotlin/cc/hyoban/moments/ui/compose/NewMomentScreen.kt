package cc.hyoban.moments.ui.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
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
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.Group
import cc.hyoban.moments.ui.settings.GroupDivider
import cc.hyoban.moments.ui.settings.rowColors
import cc.hyoban.moments.ui.theme.Space
import coil3.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A Moment written on the phone: its words, the photos picked for it, and when and where,
 * worked out from the photos or else from now and where the phone is, each open to
 * change. Saved, it is kept on the phone, and reaches the site only when the changes are
 * published. Given a Moment to [edit], it starts from that one as it was saved: one of the
 * phone's is kept as changed, as is one of the site's, with the token, until it is published.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMomentScreen(container: AppContainer, onBack: () -> Unit, onSaved: () -> Unit, edit: String? = null) {
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
    val infos = remember { mutableStateMapOf<Uri, PhotoInfo>() }
    val openedAt = remember { OffsetDateTime.now() }
    var chosenTime by remember { mutableStateOf(originalTime) }
    var chosenPlace by remember { mutableStateOf(originalPlace) }
    // The place last shared in, to say where the place came from while it is still that one.
    var sharedFrom by remember { mutableStateOf<PlaceChoice?>(null) }
    var deviceFix by remember { mutableStateOf<Fix?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var geocoded by remember { mutableStateOf<Pair<Fix, String?>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(photos) {
        photos.filter { it !in infos && it !in originalMedia }.forEach { infos[it] = Photos.read(context, it) }
    }

    // When: the earliest photo's time, else when the page was opened.
    val photoTime = photos.mapNotNull { infos[it]?.takenAt }.minByOrNull { it.toInstant() }
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
        if (fix != null && geocoded?.first != fix) geocoded = fix to container.placeSearch.around(context, fix).name
    }
    val suggestedName = fix?.let { at -> geocoded?.takeIf { it.first == at }?.second ?: coordinatesName(at) }
    val place: PlaceChoice = chosenPlace ?: if (fix != null && suggestedName != null) PlaceChoice.New(suggestedName, fix) else PlaceChoice.None

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

    val written = text.isNotBlank() || photos.isNotEmpty()
    // What leaving would lose: anything written, or, for a Moment being changed, any change.
    val changed = if (original == null) {
        written
    } else {
        text != original.text || photos != originalMedia.keys.toList() || chosenTime != originalTime || chosenPlace != originalPlace
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
                    val (moment, newPlace) = buildMoment(context, container, text, photos, infos, time, place, original, originalMedia, places)
                    if (original == null) container.addLocal(moment, newPlace) else container.updateLocal(moment, newPlace)
                }
                onSaved()
            } catch (error: Exception) {
                saving = false
                Toast.makeText(context, context.getString(R.string.error_save, error.message ?: error.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SubPage(
            stringResource(if (original == null) R.string.title_new_moment else R.string.title_edit_moment),
            onBack = leave,
            modifier = Modifier.background(AppColors.page),
            actions = {
                Button(
                    onClick = save,
                    enabled = written && changed && !saving,
                    modifier = Modifier.padding(end = Space.s).testTag("save"),
                ) { Text(stringResource(R.string.action_save)) }
            },
        ) {
            PageWidth { columns ->
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
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("moment_text"),
                        )
                    }
                    Group(stringResource(R.string.section_photo), null) {
                        PhotoStrip(photos, onAdd = addPhotos, onRemove = { removed -> photos = photos - removed })
                    }
                    val note = when {
                        published -> R.string.note_published
                        canPublish -> R.string.note_local_then_publish
                        else -> R.string.note_local_only
                    }
                    Group(stringResource(R.string.section_when_where), stringResource(note)) {
                        ListItem(
                            headlineContent = { Text(listOfNotNull(formatDay(stamp(time)), formatTime(stamp(time))).joinToString(" ")) },
                            supportingContent = { Text(stringResource(timeSource)) },
                            leadingContent = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            colors = rowColors(),
                            modifier = Modifier.clickable { editingTime = true }.testTag("moment_time"),
                        )
                        GroupDivider(56.dp)
                        ListItem(
                            headlineContent = {
                                Text(
                                    when (place) {
                                        is PlaceChoice.Known -> places[place.id]?.name ?: place.id
                                        is PlaceChoice.New -> place.name
                                        PlaceChoice.None -> stringResource(R.string.place_none)
                                    },
                                )
                            },
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
                            colors = rowColors(),
                            modifier = Modifier.clickable { editingPlace = true }.testTag("moment_place"),
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
        AnimatedVisibility(editingPlace, enter = slideInVertically { it / 4 } + fadeIn(), exit = slideOutVertically { it / 4 } + fadeOut()) {
            PlacePicker(current = place, fix = fix, places = places, search = container.placeSearch, onDismiss = { editingPlace = false }) {
                chosenPlace = it
                editingPlace = false
            }
        }
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.confirm_discard)) },
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

/** The photos picked, in a row, each with a button to take it out, and a tile to pick more. */
@Composable
private fun PhotoStrip(photos: List<Uri>, onAdd: () -> Unit, onRemove: (Uri) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(photos, key = { _, uri -> uri.toString() }) { index, uri ->
            Box(Modifier.size(PHOTO_SIZE).clip(MaterialTheme.shapes.medium).testTag("photo_$index")) {
                // The site's photos by their URL, the phone's by their Uri.
                AsyncImage(model = if (uri.scheme == "https" || uri.scheme == "http") uri.toString() else uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                        .clickable(onClickLabel = stringResource(R.string.action_remove_photo)) { onRemove(uri) }
                        .testTag("remove_photo_$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove_photo), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (photos.size < MAX_PHOTOS) {
            item(key = "add") {
                Box(
                    Modifier.size(PHOTO_SIZE).clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onAdd)
                        .testTag("add_photos"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.action_add_photos),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The day, then the time of day on it, keeping the offset [time] has: the date picker
 * leads on to the clock, which is titled with the day chosen and can go back to it.
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
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(formatDay(day.toString())) },
            text = { TimePicker(clock) },
            confirmButton = {
                TextButton(onClick = {
                    onPick(OffsetDateTime.of(day, java.time.LocalTime.of(clock.hour, clock.minute), time.offset))
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { onClock = false }) { Text(stringResource(R.string.action_previous)) } },
        )
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
        originalMedia[uri]?.let { return@map it }
        val uid = Ulid.next()
        val info = infos[uri] ?: Photos.read(context, uri)
        val copied = Photos.copy(context, uri, directory, uid)
        MomentMedia(
            uid = uid,
            type = MediaType.IMAGE,
            width = info.width,
            height = info.height,
            takenAt = info.takenAt?.let(::stamp),
            local = LocalMedia(uri = Uri.fromFile(copied.file).toString(), sha256 = copied.sha256, copied = true),
        )
    }
    val newPlace = (place as? PlaceChoice.New)?.let {
        newPlaceId() to Place(it.name.trim().ifEmpty { coordinatesName(it.fix) }, it.fix.latitude, it.fix.longitude, createdAt = now)
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
