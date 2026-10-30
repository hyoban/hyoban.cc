package cc.hyoban.moments.ui.compose

import androidx.core.net.toUri
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ChipDefaults
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.LocalContentColor
import cc.hyoban.moments.ui.design.LocalTextStyle
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import cc.hyoban.moments.ui.design.PressIndication
import cc.hyoban.moments.ui.design.DateTimeDialog
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Row
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
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.TextField
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Around
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PhotoInfo
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.Refusal
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
import cc.hyoban.moments.data.spec.FoodBill
import cc.hyoban.moments.data.spec.FoodVerdict
import androidx.compose.foundation.layout.FlowRow
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.data.spec.MomentTopic
import cc.hyoban.moments.ui.format.topicLabel
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentFood
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.data.spec.showcase
import cc.hyoban.moments.ui.components.LiveBadge
import cc.hyoban.moments.ui.ledger.BillFields
import cc.hyoban.moments.ui.ledger.BillKeypad
import cc.hyoban.moments.ui.ledger.keypadFits
import cc.hyoban.moments.ui.ledger.EnsureBook
import cc.hyoban.moments.ui.ledger.LedgerEntryArgs
import cc.hyoban.moments.ui.ledger.mealCategory
import cc.hyoban.moments.ui.ledger.rememberBillDraft
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.design.MultiChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.components.isCompactHeight
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.formatAmount
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.format.verdictLabel
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.ShadowElevation
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
 *
 * A new one can have a bill written with it, kept in the ledger at the Moment's time and place
 * and linked to it, or the bill alone, as the ledger's 记一笔 opens it ([startWithBill]).
 */
@Composable
fun NewMomentScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    edit: String? = null,
    onDeleted: () -> Unit = onBack,
    startWithBill: Boolean = false,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val places by container.repository.places.collectAsStateWithLifecycle()

    // The Moment being changed, and its photos, time and place as it was saved.
    val canPublish = container.canPublish
    val original = remember(edit) { edit?.let { container.repository.moment(it) }?.takeIf { it.published == null || canPublish } }
    val published = original?.published != null
    // Each photo it had by where it is shown from: the phone's copy, or the site's still.
    val originalMedia = remember(original) {
        original?.media.orEmpty().associateBy { media ->
            (media.local?.uri ?: container.mediaUrls.still(media, PHOTO_WIDTH) ?: "media:${media.uid}").toUri()
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
    // The motion photos added as photos alone, their clips left out, and those already asked about.
    var stills by remember { mutableStateOf(emptySet<Uri>()) }
    var askedMotion by remember { mutableStateOf(emptySet<Uri>()) }
    // What was eaten, as typed: the dishes in one field, the bill's yuan and people in two.
    val originalFood = original?.food
    var dishes by rememberSaveable { mutableStateOf(originalFood?.dishes.orEmpty().joinToString("、")) }
    var verdict by rememberSaveable { mutableStateOf(originalFood?.verdict) }
    var amount by rememberSaveable { mutableStateOf(originalFood?.bill?.amount?.let(::formatAmount).orEmpty()) }
    var people by rememberSaveable { mutableStateOf(originalFood?.bill?.people?.toString().orEmpty()) }
    /** Whether 美食 was added from the 加一项 row: open for a Moment that has a meal. */
    var withFood by rememberSaveable { mutableStateOf(originalFood != null) }
    val food = foodOf(dishes, verdict, amount, people, originalFood)
    var topic by rememberSaveable { mutableStateOf(original?.topic) }
    /** Whether 类型 was added from the 加一项 row: open for a Moment that has one. */
    var withTopic by rememberSaveable { mutableStateOf(original?.topic != null) }
    /** Private: kept on the phone and in the API for its owner, never on the site. */
    var private by rememberSaveable { mutableStateOf(original?.hidden ?: false) }
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

    // What a new one writes: the Moment, a bill, or both, never neither.
    val withMoment = original != null || !startWithBill
    var withBill by rememberSaveable { mutableStateOf(original == null && startWithBill) }
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val bill = rememberBillDraft(ledger, null, null, null, LedgerEntryArgs())
    EnsureBook(container, enabled = withBill)

    LaunchedEffect(photos) {
        photos.filter { it !in infos && it !in originalMedia }.forEach { infos[it] = Photos.read(context, it) }
        // What the site can't take, a video too big or of a type it doesn't keep, is left out
        // as soon as it is read, saying why.
        val refused = photos.mapNotNull { uri -> infos[uri]?.let(Photos::refusal)?.let { uri to it } }
        if (refused.isNotEmpty()) {
            photos = photos - refused.map { it.first }.toSet()
            saveError = refused.map { it.second }.distinct().joinToString("\n") { resources.getString(refusalText(it)) }
        }
    }
    // Motion photos just added, to be asked whether they go in as photos.
    val unaskedMotion = photos.filter { infos[it]?.motion == true && it !in askedMotion }

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
    LaunchedEffect(withMoment) {
        // A Moment being changed has its place; the map finds the phone when asked. A bill
        // alone leaves it until the Moment is to be written too.
        if (original != null || !withMoment || deviceFix != null || locating) return@LaunchedEffect
        if (LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            locate()
        } else {
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }

    // The documents picker rather than the photo picker: only what it hands over keeps where
    // the photo or video was taken.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        photos = (photos + picked).distinct().take(MAX_PHOTOS)
    }
    val pickPhotos = { picker.launch(arrayOf("image/*", "video/*")) }
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
            .onFailure { saveError = resources.getString(R.string.error_no_camera) }
    }

    val written = text.isNotBlank() || photos.isNotEmpty()
    val billWritten = withBill && (bill.amount.isNotBlank() || bill.note.isNotBlank())
    // The bill's time is the Moment's, while there is one.
    val billAt = if (withMoment) time else OffsetDateTime.parse(bill.time)
    // A meal's amount and category go to its bill, until the bill's own are chosen.
    var mirroredAmount by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(amount) {
        if (bill.amount == mirroredAmount) {
            bill.amount = amount.trim()
            mirroredAmount = bill.amount
        }
    }
    LaunchedEffect(food != null, withBill, ledger) {
        if (food != null && withBill && bill.kind == TxnKind.EXPENSE && bill.category == null) bill.category = mealCategory(ledger)
    }
    // A known place's kind is the place's to change, not the Moment's.
    val newKind = (chosenPlace as? PlaceChoice.Known)?.kind
    val momentChanged = original == null ||
        text != original.text || photos != originalMedia.keys.toList() || utility.intersect(photos.toSet()) != originalUtility ||
        chosenTime != originalTime || ((chosenPlace as? PlaceChoice.Known)?.copy(kind = null) ?: chosenPlace) != originalPlace || food != originalFood ||
        topic != original.topic || private != original.hidden
    // What leaving would lose: anything written, or, for a Moment being changed, any change.
    val changed = if (original == null) (withMoment && (written || food != null || topic != null)) || billWritten else momentChanged || newKind != null
    val leave = { if (changed) confirmingDiscard = true else onBack() }
    BackHandler(enabled = changed && !saving) { confirmingDiscard = true }

    // Kept on the phone, one of the site's as changed too; none of it goes to the API until
    // the changes are published.
    // The bill is only the phone's, saved straight to the ledger, linked to the Moment written with it.
    val save: () -> Unit = save@{
        if (saving) return@save
        // The bill is checked first, so that nothing is kept while it can't be.
        val txn = if (withBill) bill.toTxn(ledger, billAt, trips, null, null, null) { saveError = resources.getString(it) } ?: return@save else null
        saving = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    val moment = if (withMoment) {
                        val (moment, newPlace) = buildMoment(context, container, text, photos, utility, stills, infos, time, place, food, topic, private, original, originalMedia)
                        when {
                            original == null -> container.addLocal(moment, newPlace)
                            momentChanged -> container.updateLocal(moment, newPlace)
                        }
                        // A known place's kind changed in the picker, kept as a change of its own.
                        (place as? PlaceChoice.Known)?.kind?.let { container.repository.setPlaceKind(place.id, it.value) }
                        moment
                    } else null
                    txn?.let { container.ledger.saveTxn(if (moment != null) it.copy(moment = moment.uid, place = moment.places.firstOrNull()) else it) }
                }
                onSaved()
            } catch (error: Exception) {
                saving = false
                saveError = resources.getString(R.string.error_save, failureReason(context, error))
            }
        }
    }

    LaunchedEffect(written) { if (written) emptyError = false }
    // A full-screen dialog: an X to leave, whether it is public in the middle of the bar, and
    // the ink 保存, never greyed out, telling what is missing rather than refusing to be tapped.
    // A bill alone is typed on the keypad, pinned under its fields, as iCost has it.
    val keypad = withBill && !withMoment && keypadFits()
    Box(Modifier.fillMaxSize()) {
        SubPage(
            stringResource(
                when {
                    original != null -> R.string.title_edit_moment
                    !withMoment -> R.string.ledger_new_bill
                    else -> R.string.title_new_moment
                },
            ),
            onBack = leave,
            modifier = Modifier.background(AppColors.page),
            close = true,
            small = keypad,
            centre = if (withMoment) {
                { Visibility(private) { private = it } }
            } else {
                null
            },
            // A bill on the keypad has its 保存 at the keypad's foot, the page's one main action: none up here.
            actions = {
                if (!keypad) Button(
                    onClick = {
                        when {
                            withMoment && !written -> emptyError = true
                            original != null && !changed -> onBack()
                            else -> save()
                        }
                    },
                    enabled = !saving,
                    contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
                    modifier = Modifier.padding(end = Space.xs).testTag("save"),
                ) { Text(stringResource(R.string.action_save)) }
            },
        ) {
            PageWidth(maxColumns = 1) { columns ->
                Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = columns.margin)) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = if (keypad) Space.s else Space.xl),
                ) {
                    val focus = remember { FocusRequester() }
                    // A new Moment starts at its words; one being changed waits to be told what to change.
                    LaunchedEffect(Unit) { if (original == null && withMoment) focus.requestFocus() }
                    if (withMoment) {
                    // The words on the page itself, on the gutter, the field's own padding outside it.
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.hint_moment_text)) },
                        textStyle = AppTheme.typography.bodyReading,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                        isError = emptyError,
                        // The error marked by an icon as well as the colour.
                        trailingIcon = if (emptyError) {
                            { Icon(AppIcons.Alert, contentDescription = null, tint = tokens.danger) }
                        } else {
                            null
                        },
                        supportingText = if (emptyError) {
                            { Text(stringResource(R.string.error_empty_moment)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter - ROW_PADDING).focusRequester(focus).testTag("moment_text"),
                    )
                    PhotoStrip(
                        photos,
                        onAdd = addPhotos,
                        onOpenCamera = openCamera,
                        utility = utility,
                        onToggleUtility = { uri -> utility = if (uri in utility) utility - uri else utility + uri },
                        motion = photos.filter { infos[it]?.motion == true }.toSet(),
                        videos = photos.filter { infos[it]?.video == true || originalMedia[it]?.type == MediaType.VIDEO }.toSet(),
                        stills = stills,
                        onToggleStill = { uri -> stills = if (uri in stills) stills - uri else stills + uri },
                        onRemove = { removed -> photos = photos - removed },
                        onMove = { from, to -> photos = photos.toMutableList().apply { add(to, removeAt(from)) } },
                        modifier = Modifier.padding(top = Space.xs),
                    )
                    // When and where, rows on the page, their icons on the words' edge above.
                    PageRows(Modifier.padding(top = Space.l + Space.xs)) { shapes ->
                        SegmentedListItem(
                            onClick = { editingTime = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = { Text(stringResource(timeSource)) },
                            leadingContent = { Icon(AppIcons.Clock, contentDescription = null) },
                            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                            modifier = Modifier.testTag("moment_time"),
                        ) { Text(listOfNotNull(formatDay(stamp(time)), formatTime(stamp(time))).joinToString("\u2003")) }
                        SegmentedListItem(
                            onClick = { editingPlace = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = {
                                // Its kind, as it will be once saved, then where the place came from.
                                val kind = when (place) {
                                    is PlaceChoice.Known -> place.kind ?: PlaceKind.of(places[place.id]?.kind)
                                    is PlaceChoice.New -> place.kind
                                    PlaceChoice.None -> null
                                }
                                Text(
                                    listOfNotNull(kind?.let { stringResource(it.label) }, stringResource(
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
                                    )).joinToString("\u2003"),
                                )
                            },
                            leadingContent = { Icon(AppIcons.Pin, contentDescription = null) },
                            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
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
                    // 加一项: the plugins in a row, each opening its fields when chosen, a tick in place of its icon.
                    PageCaption(stringResource(R.string.section_add_item))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        AddChip(withFood, AppIcons.Food, stringResource(R.string.section_food), "add_food") {
                            withFood = !withFood
                            if (!withFood) {
                                dishes = ""; verdict = null; amount = ""; people = ""
                            }
                        }
                        AddChip(withBill, AppIcons.Receipt, stringResource(R.string.section_bill), "add_bill") { withBill = !withBill }
                        AddChip(withTopic, AppIcons.Category, stringResource(R.string.section_topic), "add_topic") {
                            withTopic = !withTopic
                            if (!withTopic) topic = null
                        }
                    }
                    if (withFood) FoodGroup(
                        dishes = dishes,
                        onDishes = { dishes = it },
                        verdict = verdict,
                        onVerdict = { verdict = it },
                        amount = amount,
                        onAmount = { amount = it },
                        people = people,
                        onPeople = { people = it },
                    )
                    }
                    if (withBill) {
                        if (withMoment) PageCaption(stringResource(R.string.section_bill))
                        BillFields(container, ledger, bill, billAt, null, null, withMoment = withMoment, keypad = keypad)
                    }
                    if (withMoment && withTopic) TopicGroup(topic) { topic = it }
                    // Deleting is rare, so it waits down here rather than over the Moment; its snackbar can undo it.
                    if (original != null && publishing == null) {
                        PageRows(Modifier.padding(top = Space.xl)) { shapes ->
                            SegmentedListItem(
                                onClick = {
                                    container.delete(original.uid)
                                    onDeleted()
                                },
                                shapes = shapes,
                                enabled = !saving,
                                colors = ListItemDefaults.segmentedColors(
                                    contentColor = tokens.danger,
                                    leadingContentColor = tokens.danger,
                                ),
                                leadingContent = { Icon(AppIcons.Trash, contentDescription = null) },
                                modifier = Modifier.testTag("delete"),
                            ) { Text(stringResource(R.string.action_delete_moment)) }
                        }
                    }
                }
                if (keypad) BillKeypad(container, ledger, bill, billAt, null, onSave = { if (!saving) save() })
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
        val motion = AppTheme.motionScheme
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
    if (unaskedMotion.isNotEmpty()) {
        val answer: (Boolean) -> Unit = { asPhotos ->
            if (asPhotos) stills = stills + unaskedMotion
            askedMotion = askedMotion + unaskedMotion
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text(stringResource(R.string.confirm_motion)) },
            text = { Text(pluralStringResource(R.plurals.confirm_motion_note, unaskedMotion.size, unaskedMotion.size)) },
            confirmButton = {
                TextButton(onClick = { answer(true) }, modifier = Modifier.testTag("motion_as_photos")) {
                    Text(stringResource(R.string.action_add_as_photos))
                }
            },
            dismissButton = {
                TextButton(onClick = { answer(false) }, modifier = Modifier.testTag("motion_keep_live")) {
                    Text(stringResource(R.string.action_keep_live))
                }
            },
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
 * The photos picked, in a row, each with a button to take it out, a motion photo marked
 * as live unless it goes in as a photo, and tiles to pick more or open the camera app to
 * take one.
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
    motion: Set<Uri>,
    videos: Set<Uri>,
    stills: Set<Uri>,
    onToggleStill: (Uri) -> Unit,
    onRemove: (Uri) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
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
    val settleSpec = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    val liftSpec = AppTheme.motionScheme.fastSpatialSpec<Float>()
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
    val photoShape = AppTheme.shapes.medium
    val earlier = stringResource(R.string.action_move_earlier)
    val later = stringResource(R.string.action_move_later)
    val options = stringResource(R.string.action_photo_options)
    // The photo whose menu is open, from a tap on it.
    var menuFor by remember { mutableStateOf<Uri?>(null) }
    LazyRow(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = Space.gutter),
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
                shadowElevation = ShadowElevation.toPx() * lift
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
                if (lift > 0f) Box(Modifier.fillMaxSize().background(tokens.tx.copy(alpha = DRAGGED_LAYER * lift)))
                if (uri in motion && uri !in stills) LiveBadge(Modifier.align(Alignment.TopStart).padding(Space.s))
                if (uri in videos) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(Space.s).size(UTILITY_DOT).clip(CircleShape).background(MediaOverlay.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Play, contentDescription = stringResource(R.string.media_video), tint = MediaOverlay.content, modifier = Modifier.size(18.dp))
                    }
                }
                if (uri in utility) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(Space.s).size(UTILITY_DOT).clip(CircleShape).background(MediaOverlay.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.PhotoOff, contentDescription = stringResource(R.string.label_photo_utility), tint = MediaOverlay.content, modifier = Modifier.size(16.dp))
                    }
                }
                DropdownMenu(menuFor == uri, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_photo_utility)) },
                        leadingIcon = { Icon(AppIcons.PhotoOff, contentDescription = null) },
                        trailingIcon = if (uri in utility) {
                            { Icon(AppIcons.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            menuFor = null
                            onToggleUtility(uri)
                        },
                        modifier = Modifier.semantics { toggleableState = ToggleableState(uri in utility) }.testTag("photo_utility_$index"),
                    )
                    if (uri in motion) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_photo_still)) },
                            leadingIcon = { Icon(AppIcons.LiveOff, contentDescription = null) },
                            trailingIcon = if (uri in stills) {
                                { Icon(AppIcons.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                menuFor = null
                                onToggleStill(uri)
                            },
                            modifier = Modifier.semantics { toggleableState = ToggleableState(uri in stills) }.testTag("photo_still_$index"),
                        )
                    }
                }
                // A small dot to see, in a corner of the 48dp Material 3 has anything tapped take.
                Box(
                    Modifier.align(Alignment.TopEnd).size(REMOVE_TARGET)
                        .clickable(
                            interactionSource = null,
                            indication = PressIndication,
                            role = Role.Button,
                            onClickLabel = stringResource(if (uri in videos) R.string.action_remove_video else R.string.action_remove_photo),
                        ) { onRemove(uri) }
                        .testTag("remove_photo_$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(REMOVE_DOT).clip(CircleShape).background(MediaOverlay.control),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Close, contentDescription = stringResource(if (uri in videos) R.string.action_remove_video else R.string.action_remove_photo), tint = MediaOverlay.content, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (photos.size < MAX_PHOTOS) {
            item(key = ADD_KEY) {
                AddTile(AppIcons.PhotoAdd, stringResource(R.string.action_add_photos), onAdd, Modifier.animateItem().testTag("add_photos"))
            }
            item(key = CAMERA_KEY) {
                AddTile(AppIcons.Camera, stringResource(R.string.action_open_camera), onOpenCamera, Modifier.animateItem().testTag("open_camera"))
            }
        }
    }
}

/** Rows on the page itself, with space rather than lines between them, their icons on the gutter. */
@Composable
private fun PageRows(modifier: Modifier = Modifier, rows: @Composable (ListItemShapes) -> Unit) {
    val shapes = ListItemShapes(AppTheme.shapes.medium)
    Column(modifier.padding(horizontal = Space.gutter - ROW_PADDING)) { rows(shapes) }
}

/** What heads a part of the page, in the third grey on the gutter, well clear of what is over it. */
@Composable
private fun PageCaption(title: String) {
    Text(
        title,
        style = AppTheme.typography.caption,
        color = tokens.tx3,
        modifier = Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs).semantics { heading() },
    )
}

/** One of 加一项's plugins: its icon, a tick once added. */
@Composable
private fun AddChip(added: Boolean, icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = added,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(if (added) AppIcons.Check else icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.testTag(tag),
    )
}

/** Whether the Moment is public, in the middle of the bar, a tap opening the two to choose from. */
@Composable
private fun Visibility(private: Boolean, onPrivate: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(if (private) R.string.visibility_private else R.string.visibility_public)) },
            leadingIcon = { Icon(if (private) AppIcons.EyeOff else AppIcons.Eye, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(AppIcons.CaretDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics { role = Role.DropdownList }.testTag("moment_private"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(false to AppIcons.Eye, true to AppIcons.EyeOff).forEach { (choice, icon) ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (choice) R.string.visibility_private else R.string.visibility_public)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    trailingIcon = if (choice == private) {
                        { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onPrivate(choice)
                    },
                )
            }
        }
    }
}

/** What the Moment is mostly about, one of the chips; the chosen one tapped again leaves it unset. */
@Composable
private fun TopicGroup(topic: String?, onTopic: (String?) -> Unit) {
    val label = stringResource(R.string.section_topic)
    PageCaption(label)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        MomentTopic.entries.forEach { entry ->
            val selected = entry.value == topic
            FilterChip(
                selected = selected,
                onClick = { onTopic(if (selected) null else entry.value) },
                label = { Text(stringResource(topicLabel(entry))) },
                leadingIcon = if (selected) {
                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                modifier = Modifier.testTag("topic_${entry.value}"),
            )
        }
    }
}

/**
 * What was eaten, rows on the page: the dishes, how they were, one of three in a segmented control
 * that a second tap clears, and what the meal cost and how many it fed, with each one's share.
 */
@Composable
private fun FoodGroup(
    dishes: String,
    onDishes: (String) -> Unit,
    verdict: String?,
    onVerdict: (String?) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    people: String,
    onPeople: (String) -> Unit,
) {
    PageCaption(stringResource(R.string.section_food))
    PageRows { _ ->
        ListItem(
            leadingContent = { Icon(AppIcons.Food, contentDescription = null) },
            headlineContent = {
                RowField(dishes, onDishes, stringResource(R.string.hint_food_dishes), AppTheme.typography.body, KeyboardOptions(imeAction = ImeAction.Next), Modifier.fillMaxWidth().testTag("food_dishes"))
            },
        )
        ListItem(
            leadingContent = { Icon(AppIcons.FaceGood, contentDescription = null) },
            headlineContent = {
                // One verdict or none: a second tap on the chosen one takes it back.
                MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(ChipDefaults.Height)) {
                    FoodVerdict.entries.forEach { choice ->
                        SegmentedButton(
                            checked = choice.value == verdict,
                            onCheckedChange = { onVerdict(if (it) choice.value else null) },
                            modifier = Modifier.testTag("food_${choice.value}"),
                        ) { Text(stringResource(verdictLabel(choice)), maxLines = 1) }
                    }
                }
            },
        )
        // Each one's share once both are known, after how many.
        val share = amount.toDoubleOrNull()?.let { yuan -> people.toIntOrNull()?.takeIf { it > 1 }?.let { yuan / it } }
        ListItem(
            leadingContent = { Icon(AppIcons.Receipt, contentDescription = null) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¥", style = AppTheme.typography.body)
                    RowField(
                        amount,
                        { typed -> onAmount(typed.filter { it.isDigit() || it == '.' }) },
                        stringResource(R.string.label_food_amount),
                        AppTheme.typography.body,
                        KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        Modifier.weight(1f).testTag("food_amount"),
                    )
                }
            },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowField(
                        people,
                        { typed -> onPeople(typed.filter { it.isDigit() }.take(3)) },
                        stringResource(R.string.label_food_people),
                        LocalTextStyle.current.copy(fontFamily = AppTheme.typography.body.fontFamily),
                        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        Modifier.testTag("food_people"),
                        fit = true,
                    )
                    if (people.isNotEmpty()) Text(" " + stringResource(R.string.unit_food_people))
                    if (share != null) Text("\u2003" + stringResource(R.string.food_share, formatAmount(share)))
                }
            },
        )
    }
}

/**
 * A field that is a row's words alone, its hint in the third grey while empty; one that [fit]s
 * is as wide as what it holds, with room for the cursor, so words can follow it on its line.
 */
@Composable
private fun RowField(value: String, onValueChange: (String) -> Unit, hint: String, style: TextStyle, keyboardOptions: KeyboardOptions, modifier: Modifier, fit: Boolean = false) {
    val color = LocalContentColor.current
    val measurer = rememberTextMeasurer()
    val width = if (fit) with(LocalDensity.current) { measurer.measure(value.ifEmpty { hint }, style).size.width.toDp() + CURSOR_ROOM } else null
    BasicTextField(
        value,
        onValueChange,
        if (width != null) modifier.width(width) else modifier,
        textStyle = style.merge(TextStyle(color = color)),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(tokens.tx),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = style, color = tokens.tx3, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * The food as typed, kept with whatever else [original] said: the dishes split at commas,
 * enumeration commas and new lines, a bill only with its yuan, and nothing when nothing is said.
 */
internal fun foodOf(dishes: String, verdict: String?, amount: String, people: String, original: MomentFood?): MomentFood? {
    val names = dishes.split(DISH_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
    val bill = amount.toDoubleOrNull()?.takeIf { it >= 0 }?.let { yuan ->
        val fed = people.toIntOrNull()?.takeIf { it > 0 }
        original?.bill?.copy(amount = yuan, people = fed) ?: FoodBill(yuan, fed)
    }
    if (names.isEmpty() && verdict == null && bill == null) return null
    return original?.copy(dishes = names, verdict = verdict, bill = bill) ?: MomentFood(names, verdict, bill)
}

private val DISH_SEPARATORS = Regex("[,，、;；\\n]")

/** A tile at the end of the photos that adds more to them. */
@Composable
private fun AddTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(PHOTO_SIZE).clip(AppTheme.shapes.medium)
            .background(tokens.fill)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tokens.tx2)
    }
}

/** The day and the time of day on it, in one dialog, keeping the offset [time] has. */
@Composable
internal fun TimeDialog(time: OffsetDateTime, onDismiss: () -> Unit, onPick: (OffsetDateTime) -> Unit) =
    DateTimeDialog(time.toLocalDate(), time.hour, time.minute, onDismiss) { day, hour, minute ->
        onPick(OffsetDateTime.of(day, java.time.LocalTime.of(hour, minute), time.offset))
    }

/**
 * The Moment as a record, its photos and videos copied into the app's store first so it
 * keeps them whatever becomes of the gallery's, a motion photo split into its still and clip
 * unless it is one of [stills], and the place it makes, if it makes one. Changing
 * [original], it keeps its id, when it was made and the photos it already had as they
 * were. Its place is the one chosen, as a Moment is at one place at most.
 */
private suspend fun buildMoment(
    context: Context,
    container: AppContainer,
    text: String,
    photos: List<Uri>,
    utility: Set<Uri>,
    stills: Set<Uri>,
    infos: Map<Uri, PhotoInfo>,
    time: OffsetDateTime,
    place: PlaceChoice,
    food: MomentFood?,
    topic: String?,
    private: Boolean,
    original: MomentRecord? = null,
    originalMedia: Map<Uri, MomentMedia> = emptyMap(),
): Pair<MomentRecord, Pair<String, Place>?> {
    val now = stamp(OffsetDateTime.now())
    val directory = File(container.localStore.root, "media")
    val media = photos.map { uri ->
        val kept = (uri in utility).takeIf { it }
        originalMedia[uri]?.let { return@map it.copy(utility = kept) }
        val uid = Ulid.next()
        val info = infos[uri] ?: Photos.read(context, uri)
        // A motion photo is split either way, so a photo alone is its still without the clip
        // trailing after it; a video gets a frame of itself as its still.
        val copied = Photos.copy(context, uri, directory, uid, splitMotion = !info.video, poster = info.video)
            .let { photo ->
                if (info.video || uri !in stills) return@let photo
                photo.video?.delete()
                photo.copy(video = null)
            }
        MomentMedia(
            uid = uid,
            type = when {
                info.video -> MediaType.VIDEO
                copied.video != null -> MediaType.LIVE_PHOTO
                else -> MediaType.IMAGE
            },
            width = info.width,
            height = info.height,
            takenAt = info.takenAt?.let(::stamp),
            utility = kept,
            local = LocalMedia(
                uri = Uri.fromFile(copied.file).toString(),
                videoUri = copied.video?.let { Uri.fromFile(it).toString() },
                sha256 = copied.sha256,
                copied = true,
            ),
        )
    }
    val newPlace = (place as? PlaceChoice.New)?.let {
        newPlaceId() to Place(it.name.trim().ifEmpty { coordinatesName(it.fix) }, it.fix.latitude, it.fix.longitude, kind = it.kind?.value, createdAt = now)
    }
    val placeIds = when (place) {
        is PlaceChoice.Known -> listOf(place.id)
        is PlaceChoice.New -> listOfNotNull(newPlace?.first)
        PlaceChoice.None -> emptyList()
    }
    val moment = original?.copy(occurredAt = stamp(time), places = placeIds, text = text.trim(), media = media, food = food, topic = topic, hidden = private, updatedAt = now)
        ?: MomentRecord(
            uid = Ulid.next(),
            occurredAt = stamp(time),
            places = placeIds,
            text = text.trim(),
            media = media,
            food = food,
            hidden = private,
            topic = topic,
            createdAt = now,
            updatedAt = now,
        )
    return moment to newPlace
}

/** What says why a picked file was left out. */
private fun refusalText(refusal: Refusal): Int = when (refusal) {
    Refusal.NOT_MEDIA -> R.string.error_not_media
    Refusal.VIDEO_TYPE -> R.string.error_video_type
    Refusal.VIDEO_TOO_BIG -> R.string.error_video_too_big
}

/** The side padding of a row, outside the page's gutter so its icon and words line up with the words above. */
private val ROW_PADDING = 16.dp
private const val MAX_PHOTOS = 9
private val CURSOR_ROOM = 2.dp
/** How wide the site's photos are fetched for the strip: its tiles, at a phone's density. */
private const val PHOTO_WIDTH = 480
private val PHOTO_SIZE = 88.dp
private val REMOVE_TARGET = 48.dp
private val REMOVE_DOT = 28.dp
private val UTILITY_DOT = 28.dp
private const val ADD_KEY = "add"
private const val CAMERA_KEY = "camera"
/** How much a photo held to move grows, and Material 3's dragged state layer over it. */
private const val LIFTED_SCALE = 1.06f
private const val DRAGGED_LAYER = 0.16f
