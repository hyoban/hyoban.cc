package cc.hyoban.moments.ui.compose

import cc.hyoban.moments.plugins.nas
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.dividerColor
import androidx.compose.foundation.layout.fillMaxHeight
import cc.hyoban.moments.ui.components.railPadding
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ChipDefaults
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.LocalContentColor
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextStyle
import cc.hyoban.moments.ui.design.PressIndication
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.data.local.PhotoRules
import cc.hyoban.moments.data.local.toPhotoRef
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.horizontalScroll
import cc.hyoban.moments.ui.design.Sticker
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.Around
import cc.hyoban.moments.data.local.Masking
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Mask
import cc.hyoban.moments.data.local.sha256Of
import cc.hyoban.moments.data.local.PhotoInfo
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.Refusal
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.parseStamp
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.local.stamp
import androidx.compose.foundation.layout.FlowRow
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.ui.viewer.MomentMediaViewer
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.picker.lesserThanNas
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.data.spec.showcase
import cc.hyoban.moments.ui.components.BadgeKind
import cc.hyoban.moments.ui.components.MediaBadge
import cc.hyoban.moments.plugins.Plugins
import androidx.compose.runtime.key
import cc.hyoban.moments.ui.components.isCompactHeight
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.Opacity
import cc.hyoban.moments.ui.settings.PageCaption
import cc.hyoban.moments.ui.settings.PageRows
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import coil3.compose.AsyncImage
import java.io.File
import java.net.URI
import java.time.Instant
import cc.hyoban.moments.data.spec.Entries
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
 * and linked to it, or the bill alone, as the ledger's 记一笔 opens it ([alone], the part written alone).
 *
 * At a [stay] of the footprints, tapped on the map, it starts at the stay's start and at its place,
 * the phone's own within reach or a new one named after what is around it; saved with nothing
 * written it is that stay's check-in, until its end, as 保存为签到 keeps one.
 */
@Composable
fun NewMomentScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    edit: String? = null,
    onDeleted: () -> Unit = onBack,
    alone: String? = null,
    stay: cc.hyoban.moments.ui.StayAt? = null,
    /** The trip whose plan a new Moment is written into: planned, at its first day's morning. */
    plan: String? = null,
) {
    val device = cc.hyoban.moments.ui.LocalDevice.current
    val scope = rememberCoroutineScope()
    val places by container.repository.places.collectAsStateWithLifecycle()

    // The Moment being changed, and its photos, time and place as it was saved.
    val canPublish = container.canPublish
    // A module's own record kept by its place alone (a check-in) opens as a Moment with nothing
    // but its place, and is kept as one while it has nothing more.
    val ownStop = remember(edit) { edit?.takeIf { container.repository.moment(it) == null }?.let { Plugins.stopAsMoment(container, it) } }
    val original = remember(edit) {
        (edit?.let { container.repository.moment(it) } ?: ownStop)
            ?.takeIf { container.repository.isLocal(it.uid) || canPublish }
    }
    val openedAsStop = original != null && ownStop != null
    val keeper = placeAlone
    val published = original?.published != null
    // Each photo it had by where it is shown from: the phone's copy, or the site's still.
    val originalMedia = remember(original) {
        original?.media.orEmpty().associateBy { media ->
            (media.local?.uri ?: container.mediaUrls.still(media, PHOTO_WIDTH) ?: "media:${media.uid}").toPhotoRef()
        }
    }
    val originalTime = remember(original) { original?.let { parseStamp(it.occurredAt) } }
    val originalPlace = remember(original) { original?.let { it.places.firstOrNull()?.let(PlaceChoice::Known) ?: PlaceChoice.None } }

    var text by rememberSaveable { mutableStateOf(original?.text.orEmpty()) }
    var photos by remember { mutableStateOf(originalMedia.keys.toList()) }
    // The photos kept for the record, never picked for a cover: see MomentMedia.utility.
    val originalUtility = remember(originalMedia) { originalMedia.filterValues { !it.showcase }.keys }
    var utility by remember { mutableStateOf(originalUtility) }
    val infos = remember { mutableStateMapOf<PhotoRef, PhotoInfo>() }
    // The motion photos added as photos alone, their clips left out, and those already asked about.
    var stills by remember { mutableStateOf(emptySet<PhotoRef>()) }
    // The photos 打码 is on for, each with its patches: a photo saved with them has them still,
    // kept beside the app's copy as it was picked.
    val originalMasks = remember(originalMedia) {
        originalMedia.mapNotNull { (ref, media) -> media.local?.masks?.takeIf { it.isNotEmpty() }?.let { ref to it } }.toMap()
    }
    var masks by remember { mutableStateOf(originalMasks) }
    // 换成 NAS 原图: each photo put in for a published one, by the one it stands in for, whose alt
    // text it keeps; and the one being fetched.
    var replaced by remember { mutableStateOf(emptyMap<PhotoRef, MomentMedia>()) }
    var replacing by remember { mutableStateOf<PhotoRef?>(null) }
    // A saved photo linked to the NAS put in again as its original, live or a still as chosen
    // there, by the photo it stands in for, to go back to that one when chosen back.
    var swappedLive by remember { mutableStateOf(emptyMap<PhotoRef, PhotoRef>()) }
    // Where the patches are drawn from: a photo saved with them, the copy as it was picked.
    val unmaskedOf: (PhotoRef) -> PhotoRef = { ref -> originalMedia[ref]?.local?.unmasked?.toPhotoRef() ?: ref }
    // The patch chosen in the viewer, for the bar to change its style, and the style new ones take,
    // a face's and a plate's each, once one of that kind has been chosen there (none being the
    // mosaic); until then each takes a sticker of its kind at random.
    var chosenMask by remember { mutableStateOf<Pair<PhotoRef, Mask>?>(null) }
    // A face's (false) and a plate's (true) style once chosen.
    var maskStyles by remember { mutableStateOf(emptyMap<Boolean, String?>()) }
    val styleFor: (List<Mask>, Boolean) -> String? = { beside, plate ->
        if (plate in maskStyles) maskStyles[plate] else Sticker.random(beside.mapNotNull { it.sticker }, plate)
    }
    // The photos new here whose faces and plates have been looked for: each is covered as it comes
    // in, the switch only turning it off, and saving waits for those still being looked at.
    var scanned by remember { mutableStateOf(emptySet<PhotoRef>()) }
    val coverFound: suspend (List<PhotoRef>) -> Unit = { refs ->
        for (ref in refs) {
            if (ref in scanned) continue
            val found = Masking.find(ref)
            if (ref in scanned) continue
            scanned = scanned + ref
            if (found != null && !found.isEmpty && ref !in masks) masks = masks + (ref to found.patches(emptyList(), styleFor))
        }
    }
    val showMessage = rememberShowMessage()
    var askedMotion by remember { mutableStateOf(emptySet<PhotoRef>()) }
    /** Private: kept on the phone and in the API for its owner, never on the site. */
    var private by rememberSaveable { mutableStateOf(original?.hidden ?: false) }
    val openedAt = remember { OffsetDateTime.now() }
    // Written into a plan, it starts on the plan's first day, at nine unless the plan starts later.
    val planTrip = remember(plan) { plan?.let { id -> container.shownTrips.value.firstOrNull { it.id == id } } }
    val planTime = remember(planTrip) {
        planTrip?.let { trip ->
            trip.startAt?.let(OffsetDateTime::parse)
                ?: java.time.LocalDate.parse(trip.start).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime()
        }
    }
    var chosenTime by remember { mutableStateOf(originalTime ?: planTime) }
    // When it ended, for one that lasted: as saved or chosen once [endChosen], none among them; else
    // the end worked out with the time.
    val originalEnd = remember(original) { original?.end?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } }
    var chosenEnd by remember { mutableStateOf(originalEnd) }
    var endChosen by remember { mutableStateOf(original != null || planTime != null) }
    // Where an end kept through a start chosen by hand came from, said on as it was.
    var endKeptFrom by remember { mutableStateOf<StringResource?>(null) }
    var editingEnd by remember { mutableStateOf(false) }
    /** Planned: written into a plan rather than lived, set by hand, and from the start when written from a plan. */
    var planned by rememberSaveable { mutableStateOf(original?.planned == true || planTrip != null) }
    /** Locked: a planned one, or a check-in still to come, settled, so 和 AI 规划 leaves it as it is. */
    var locked by rememberSaveable { mutableStateOf(original?.locked == true) }
    /** How a planned one is reached from the place before it: driving unless chosen. */
    var reachedBy by rememberSaveable { mutableStateOf(original?.reachedBy) }
    var choosingWay by remember { mutableStateOf(false) }
    val stayFix = remember(stay) { stay?.let { Fix(it.latitude, it.longitude) } }
    val stayTime = remember(stay) { stay?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it.start), java.time.ZoneId.systemDefault()) } }
    val stayEnd = remember(stay) { stay?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it.end), java.time.ZoneId.systemDefault()) } }
    // At a stay, the phone's own place there, as the footprints' list names it.
    val stayPlace = remember(stay) {
        stayFix?.let { at ->
            cc.hyoban.moments.data.local.nearestPlaces(places, at, limit = 1).firstOrNull()
                ?.takeIf { (_, metres) -> metres <= cc.hyoban.moments.data.local.SAME_PLACE_METRES }?.let { (id, _) -> PlaceChoice.Known(id) }
        }
    }
    var chosenPlace by remember { mutableStateOf(originalPlace) }
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

    // What a new one writes: the Moment, the part it was opened for alone (a bill), or both, never neither.
    val withMoment = original != null || alone == null

    LaunchedEffect(photos) {
        photos.filter { it !in infos && it !in originalMedia }.forEach { ref ->
            // An iPhone's live photo from the NAS has its clip beside it, which comes along.
            infos[ref] = Photos.read(ref).let { info -> if (info.video) info else container.nas.liveClip(ref)?.let { info.copy(motionClip = it) } ?: info }
        }
        // What the site can't take, a video too big or of a type it doesn't keep, is left out
        // as soon as it is read, saying why.
        val refused = photos.mapNotNull { uri -> infos[uri]?.let(PhotoRules::refusal)?.let { uri to it } }
        if (refused.isNotEmpty()) {
            photos = photos - refused.map { it.first }.toSet()
            saveError = refused.map { it.second }.distinct().joinToString("\n") { textOf(refusalText(it)) }
        }
    }
    // A saved photo linked to the NAS goes by the NAS original's EXIF, its place included, which the
    // published copy and the phone's have lost; read once and kept, and only ever on the device.
    val nasDetails = remember { mutableStateMapOf<PhotoRef, cc.hyoban.moments.ui.viewer.PhotoDetails>() }
    LaunchedEffect(originalMedia) {
        if (!container.nas.configured) return@LaunchedEffect
        originalMedia.forEach { (ref, media) ->
            if (media.nas == null || media.type == MediaType.VIDEO || ref in nasDetails) return@forEach
            try {
                nasDetails[ref] = container.mediaDetails.read(media, original?.occurredAt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                cc.hyoban.moments.logWarning("Moments", "nas: details not read", e)
            }
        }
    }
    // New photos, read, each has its faces and plates covered.
    val unscanned = if (!Masking.available) emptyList() else
        photos.filter { it !in originalMedia && it !in scanned && infos[it]?.let { info -> !info.video } == true }
    LaunchedEffect(unscanned) { coverFound(unscanned) }
    // Motion photos just added, to be asked whether they go in as photos.
    val unaskedMotion = photos.filter { infos[it]?.motion == true && it !in askedMotion }
    val motionPhotos = photos.filter { infos[it]?.motion == true }.toSet()
    val videoPhotos = photos.filter { infos[it]?.video == true || originalMedia[it]?.type == MediaType.VIDEO }.toSet()
    // The photo open in the viewer, by where it is in the row.
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }

    // When: as chosen or saved, else as worked out from the photos (those saved with it too), the
    // stay or now; a saved one whose photos say nothing keeps its saved time.
    val inferred = inferredTime(
        taken = photos.mapNotNull { infos[it]?.takenAt ?: nasDetails[it]?.takenAt ?: originalMedia[it]?.takenAt?.let(::parseStamp) },
        saved = photos.mapNotNull { infos[it]?.savedAt },
        stay = stayTime,
        now = openedAt,
        editing = original != null,
        stayEnd = stayEnd,
    )
    val time = chosenTime ?: inferred?.start ?: originalTime ?: openedAt
    val timeSource = when {
        original != null && chosenTime == originalTime -> Res.string.source_saved
        chosenTime != null -> Res.string.source_chosen
        inferred != null -> inferred.source.label
        else -> Res.string.source_saved
    }
    // An end is only ever after the start.
    val end = (if (endChosen) chosenEnd else inferred?.end)?.takeIf { it.isAfter(time) && !sameMinute(it, time) }
    val endSource = when {
        original != null && endChosen && chosenEnd == originalEnd -> Res.string.source_saved
        endChosen -> endKeptFrom ?: Res.string.source_chosen
        inferred?.end != null -> inferred.source.label
        else -> null
    }

    // Where: just where the first photo that says was taken, as a new place named after what
    // is around it, else none until one is chosen; the map finds the phone when asked. A saved
    // photo linked to the NAS says where by the NAS's original, as one put in from it (换成 NAS 原图)
    // does by its own; a saved Moment keeps its place all the same, only offering that one.
    val photoFix = photos.firstNotNullOfOrNull { infos[it]?.fix ?: nasDetails[it]?.fix }
    val fix = photoFix ?: stayFix
    LaunchedEffect(fix) {
        if (fix != null && geocoded?.first != fix) geocoded = fix to container.placeSearch.around(fix)
    }
    val around = geocoded?.takeIf { it.first == fix }?.second
    val suggestedName = fix?.let { at -> around?.name ?: coordinatesName(at) }
    val inferredPlace = inferredPlace(stayPlace, photoFix, stayFix, suggestedName, around?.kind)
    // A saved one whose new photos say nothing keeps its saved place.
    val place: PlaceChoice = chosenPlace ?: inferredPlace?.first ?: originalPlace ?: PlaceChoice.None

    // Photos shared in from the gallery, now or while this page is open, join the Moment's.
    val sharedPhotos by container.sharedPhotos.collectAsStateWithLifecycle()
    LaunchedEffect(sharedPhotos) {
        if (original != null || sharedPhotos.isEmpty()) return@LaunchedEffect
        photos = (photos + container.takeSharedPhotos()).distinct().take(MAX_PHOTOS)
    }

    // 选照片 (39), over this page, as the device has it: the phone's gallery and paired camera in one, a computer's files.
    var picking by remember { mutableStateOf(false) }
    val addPhotos = { picking = true }
    if (picking) device.PhotoPicker(container, limit = MAX_PHOTOS - photos.size, onDismiss = { picking = false }) { added ->
        photos = (photos + added).distinct().take(MAX_PHOTOS)
        picking = false
    }
    // The tile opens the phone's camera app itself, with all it can do; what it keeps to the
    // gallery meanwhile joins the Moment's photos, in the order taken, as it is kept. One taken
    // off again isn't put back. One the camera writes again (a live photo's still, then the
    // photo with its clip) is read again, and looked at for faces and plates again.
    var cameraSince by remember { mutableStateOf<Long?>(null) }
    var fromCamera by remember { mutableStateOf(emptyMap<PhotoRef, Long>()) }
    LaunchedEffect(cameraSince) {
        val since = cameraSince ?: return@LaunchedEffect
        device.cameraShots(since).collect { shots ->
            val fresh = shots.filter { it.ref !in fromCamera }.map { it.ref }
            val rewritten = shots.filter { shot -> fromCamera[shot.ref].let { it != null && it != shot.bytes } }.map { it.ref }
            fromCamera = fromCamera + shots.associate { it.ref to it.bytes }
            rewritten.filter { it in photos }.forEach { ref ->
                infos[ref] = Photos.read(ref)
                scanned = scanned - ref
                masks = masks - ref
            }
            if (fresh.isNotEmpty()) photos = (photos + fresh).distinct().take(MAX_PHOTOS)
        }
    }
    // Back from the camera, what it is still saving is waited for a little, then no longer, so
    // the gallery's later photos stay out.
    LifecycleResumeEffect(cameraSince) {
        val waiting = cameraSince?.let { scope.launch { delay(CAMERA_SETTLE_MILLIS); cameraSince = null } }
        onPauseOrDispose { waiting?.cancel() }
    }
    val openCamera: () -> Unit = {
        val since = System.currentTimeMillis()
        if (device.openCamera()) cameraSince = cameraSince ?: since
        else saveError = textOf(Res.string.error_no_camera)
    }

    val written = text.isNotBlank() || photos.isNotEmpty()
    // Nothing written, nothing eaten, but somewhere: a module's own record there (a check-in), while that module is on.
    val composerSettings = LocalSettings.current
    val aloneOn = cc.hyoban.moments.plugins.Plugins.registry.on(cc.hyoban.moments.plugins.Points.placeAlone, composerSettings::isOn).isNotEmpty()
    // The parts written with it, as their modules have them: what it cost, a bill; each asked
    // every composition, the ones before it saying what was spent.
    val parts = remember { cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.Points.composerParts) }
    var spent: Spent? = null
    val drafts = parts.map { part ->
        key(part.key) {
            part.draft(ComposerScope(container, original, withMoment, alone, time, spent)).also { draft -> draft.spent?.let { spent = it } }
        }
    }
    val keptAlone = withMoment && aloneOn && !written && drafts.none { it.holds } && place != PlaceChoice.None
    // What may be locked: a planned one, or any check-in; no longer either, it is unlocked.
    val lockable = planned || keptAlone
    val keptLocked = locked && lockable
    // A known place's kind is the place's to change, not the Moment's.
    val newKind = (chosenPlace as? PlaceChoice.Known)?.kind
    val momentChanged = original == null ||
        text != original.text || photos != originalMedia.keys.toList() || utility.intersect(photos.toSet()) != originalUtility ||
        masks.filter { (ref, laid) -> ref in photos && laid.isNotEmpty() } != originalMasks.filterKeys { it in photos } ||
        chosenTime != originalTime || end != originalEnd || ((chosenPlace as? PlaceChoice.Known)?.copy(kind = null) ?: chosenPlace) != originalPlace || drafts.any { it.changed } ||
        private != original.hidden || planned != (original.planned == true) || keptLocked != (original.locked == true) || reachedBy.takeIf { planned } != original.reachedBy
    // What leaving would lose: anything written, or, for a Moment being changed, any change.
    val changed = if (original == null) (withMoment && (written || drafts.any { it.holds })) || drafts.any { it.written } else momentChanged || newKind != null
    val leave = { if (changed) confirmingDiscard = true else onBack() }
    BackHandler(enabled = changed && !saving) { confirmingDiscard = true }

    // Kept on the phone, one of the site's as changed too; none of it goes to the API until
    // the changes are published.
    // A part kept elsewhere (a bill, only the phone's, saved straight to the ledger) is kept after the Moment, linked to it.
    val save: () -> Unit = save@{
        if (saving) return@save
        // The parts are checked first, so that nothing is kept while one can't be.
        if (!drafts.all { it.prepare { error -> saveError = error } }) return@save
        // One kept at a stay has its uid made from the stay, so keeping it again changes that one;
        // the module may refuse it (another over the stay is that visit).
        val stayKept = stay?.takeIf { original == null && keptAlone && keeper != null }
        val stayUid = stayKept?.let { keeper!!.stayUid(it) }
        val refused = stayKept?.let { keeper!!.stayRefused(container, it, stayUid!!) }
        if (refused != null) {
            saveError = textOf(refused)
            return@save
        }
        saving = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    // No new photo goes in before its faces and plates have been looked for.
                    if (withMoment) coverFound(unscanned)
                    val moment = if (withMoment) {
                        val (built, newPlace) = buildMoment(container, text, photos, utility, stills, masks, infos, time, end, place, private, original, originalMedia, stayUid, replaced)
                        // Planned or lived, as the bar's menu says; with what its parts hold.
                        val moment = drafts.fold(built) { into, draft -> draft.into(into) }.copy(planned = true.takeIf { planned }, locked = true.takeIf { keptLocked }, reachedBy = reachedBy.takeIf { planned })
                        // Kept as what it now is, a Moment or a check-in, named after its place when that moved.
                        if (original == null || momentChanged) {
                            val venue = when (place) {
                                is PlaceChoice.Known -> places[place.id]?.name
                                is PlaceChoice.New -> place.name
                                PlaceChoice.None -> null
                            }
                            val moved = (place as? PlaceChoice.Known)?.id != (originalPlace as? PlaceChoice.Known)?.id || place is PlaceChoice.New
                            val entry = keptEntry(moment, venue, moved)
                            container.saveEntry(if (stayKept == null) entry else keeper!!.atStay(entry, stayKept), newPlace)
                            // What follows keeping it at the stay (the bills paid there, with no place, may take this one).
                            if (stayKept != null) moment.places.firstOrNull()?.let { keeper!!.keptAtStay(container, it, stayKept) }
                        }
                        // A known place's kind changed in the picker, kept as a change of its own.
                        (place as? PlaceChoice.Known)?.kind?.let { container.repository.setPlaceKind(place.id, it.value) }
                        moment
                    } else null
                    drafts.forEach { it.saved(moment) }
                }
                onSaved()
            } catch (error: Exception) {
                saving = false
                saveError = textOf(Res.string.error_save, failureReason(error))
            }
        }
    }

    LaunchedEffect(written, keptAlone) { if (written || keptAlone) emptyError = false }
    // A full-screen dialog: an X to leave, whether it is public in the middle of the bar, and
    // the ink 保存, never greyed out, telling what is missing rather than refusing to be tapped.
    // A bill alone is typed on the keypad, pinned under its fields, as iCost has it.
    val aloneDraft = drafts.firstOrNull { it.keypad }
    val keypad = aloneDraft != null
    val sideways = isPhoneLandscape()
    Box(Modifier.fillMaxSize()) {
        SubPage(
            stringResource(
                when {
                    !withMoment -> drafts.firstNotNullOfOrNull { it.aloneTitle } ?: Res.string.title_new_moment
                    original != null && keptAlone && keeper != null -> keeper.editTitle
                    original != null -> Res.string.title_edit_moment
                    keptAlone && keeper != null -> keeper.newTitle
                    else -> Res.string.title_new_moment
                },
            ),
            onBack = leave,
            // On a computer: pictures dropped on it or pasted (⌘V) join it, and ⌘↩ saves it (D07).
            modifier = Modifier.background(AppColors.page)
                .dropPhotos { added -> photos = (photos + added).distinct().take(MAX_PHOTOS) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> {
                            if (!saving && !keypad) when {
                                withMoment && !written && !keptAlone -> emptyError = true
                                original != null && !changed -> onBack()
                                else -> save()
                            }
                            true
                        }
                        Key.V -> {
                            val pasted = if (withMoment) clipboardPhotos() else emptyList()
                            if (pasted.isNotEmpty()) photos = (photos + pasted).distinct().take(MAX_PHOTOS)
                            pasted.isNotEmpty()
                        }
                        else -> false
                    }
                },
            close = true,
            small = keypad,
            centre = if (withMoment) {
                { Visibility(private, planned, onPlanned = { planned = it }, locked = keptLocked, onLocked = if (lockable) { value: Boolean -> locked = value } else null) { private = it } }
            } else {
                null
            },
            // A bill on the keypad has its 保存 at the keypad's foot, the page's one main action: none up here.
            actions = {
                if (!keypad) Button(
                    onClick = {
                        when {
                            withMoment && !written && !keptAlone -> emptyError = true
                            original != null && !changed -> onBack()
                            else -> save()
                        }
                    },
                    enabled = !saving,
                    contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
                    modifier = Modifier.padding(end = Space.xs).testTag("save"),
                ) { Text(stringResource(Res.string.action_save)) }
            },
        ) {
            val focus = remember { FocusRequester() }
            // A new Moment starts at its words; one being changed waits to be told what to change.
            LaunchedEffect(Unit) { if (original == null && withMoment) focus.requestFocus() }
            val words: @Composable () -> Unit = {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(Res.string.hint_moment_text)) },
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
                        { Text(stringResource(if (aloneOn && keeper != null) keeper.emptyError else Res.string.error_empty_moment)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter - ROW_PADDING).focusRequester(focus).testTag("moment_text"),
                )
            }
            val details: @Composable () -> Unit = {
                if (withMoment) {
                PhotoStrip(
                    photos,
                    onAdd = addPhotos,
                    onOpenCamera = openCamera.takeIf { device.hasCamera },
                    utility = utility,
                    masked = masks.filterValues { it.isNotEmpty() }.keys,
                    motion = motionPhotos,
                    videos = videoPhotos,
                    stills = stills,
                    onOpen = { viewing = it },
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
                    ) { Text(listOfNotNull(formatDay(stamp(time)), formatTime(stamp(time))).joinToString(" ")) }
                    // Its end, for one that lasted: the time alone on the start's day.
                    SegmentedListItem(
                        onClick = { editingEnd = true },
                        shapes = shapes,
                        colors = segmentColors(),
                        supportingContent = { Text(listOfNotNull(stringResource(Res.string.time_end), endSource?.let { stringResource(it) }).joinToString(" ")) },
                        leadingContent = { Icon(AppIcons.Clock, contentDescription = null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                        modifier = Modifier.testTag("moment_end"),
                    ) {
                        if (end == null) {
                            Text(stringResource(Res.string.time_end_none), color = tokens.tx2)
                        } else {
                            Text(listOfNotNull(formatDay(stamp(end)).takeUnless { end.toLocalDate() == time.toLocalDate() }, formatTime(stamp(end))).joinToString(" "))
                        }
                    }
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
                                        original != null && chosenPlace == originalPlace -> Res.string.source_saved
                                        chosenPlace != null -> Res.string.source_chosen
                                        inferredPlace != null -> inferredPlace.second.label
                                        original != null -> Res.string.source_saved
                                        else -> Res.string.place_unchosen
                                    },
                                )).joinToString(" "),
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
                                PlaceChoice.None -> stringResource(Res.string.place_none)
                            },
                        )
                    }
                    // A planned one's way from the place before it, for its trip's legs to be worked out by.
                    if (planned) {
                        SegmentedListItem(
                            onClick = { choosingWay = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = { Text(stringResource(Res.string.reached_by_note)) },
                            leadingContent = { Icon(AppIcons.Route, contentDescription = null) },
                            trailingContent = {
                                Box {
                                    Icon(AppIcons.CaretDown, contentDescription = null)
                                    DropdownMenu(choosingWay, onDismissRequest = { choosingWay = false }) {
                                        listOf("drive", "walk", "bike", "train").forEach { way ->
                                            val chosen = (reachedBy ?: "drive") == way
                                            DropdownMenuItem(
                                                text = { Text(cc.hyoban.moments.ui.publish.reachedByName(way)) },
                                                modifier = if (chosen) Modifier.background(tokens.accentSoft) else Modifier,
                                                trailingIcon = if (chosen) {
                                                    { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                                                } else {
                                                    null
                                                },
                                                onClick = {
                                                    choosingWay = false
                                                    reachedBy = way.takeUnless { it == "drive" }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("moment_reached_by"),
                        ) { Text(cc.hyoban.moments.ui.publish.reachedByName(reachedBy)) }
                    }
                }
                // 加一项: the parts in a row, each opening its fields when chosen, a tick in place of
                // its icon; only those of modules that are on (记录项与公开).
                if (drafts.any { it.offered }) {
                    PageCaption(stringResource(Res.string.section_add_item))
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        drafts.forEach { it.Chips() }
                    }
                }
                }
                // Each part's fields: what it cost under the Moment, a bill's after it or alone.
                drafts.forEach { it.Fields(keypad) }
                // Deleting is rare, so it waits down here rather than over the Moment; its snackbar can undo it.
                if (original != null && publishing == null) {
                    PageRows(Modifier.padding(top = Space.xl)) { shapes ->
                        SegmentedListItem(
                            onClick = {
                                container.delete(original.uid, if (openedAsStop && keeper != null) keeper.deleted else Res.string.message_moment_deleted)
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
                        ) { Text(stringResource(if (openedAsStop && keeper != null) keeper.deleteAction else Res.string.action_delete_moment)) }
                    }
                }
            }
            // On a phone on its side the words have a column of their own at the start, the photos,
            // when and where and what else is added scrolling beside them, a hairline between (H07).
            if (sideways && withMoment && !keypad) {
                Row(Modifier.fillMaxSize().imePadding().railPadding()) {
                    Column(Modifier.width(WORDS_COLUMN).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current, bottom = Space.xl)) {
                        words()
                    }
                    Box(Modifier.padding(top = LocalTopClearance.current).fillMaxHeight().width(1.dp).background(dividerColor()))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current, bottom = Space.xl)) {
                        details()
                    }
                }
            } else {
                PageWidth(maxColumns = 1) { columns ->
                    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(horizontal = columns.margin)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = LocalTopClearance.current, bottom = if (keypad) Space.s else Space.xl),
                        ) {
                            if (withMoment) words()
                            details()
                        }
                        aloneDraft?.Keypad(onSave = { if (!saving) save() })
                    }
                }
            }
        }

        if (editingTime) {
            // Back to the time worked out, its end with it, once another is chosen or saved.
            val reset = inferred?.takeIf { !sameMinute(it.start, time) || endChosen && end != it.end }?.let { worked ->
                stringResource(worked.source.reset) to {
                    chosenTime = null
                    endChosen = false
                    endKeptFrom = null
                    editingTime = false
                }
            }
            TimeDialog(time, onDismiss = { editingTime = false }, reset = reset) {
                // The end it had stays, while it is still after the start.
                if (!endChosen) {
                    endKeptFrom = endSource
                    chosenEnd = end
                    endChosen = true
                }
                chosenTime = it
                editingTime = false
            }
        }
        if (editingEnd) {
            // None, for a Moment of a moment.
            val clear = if (end == null) null else stringResource(Res.string.time_end_clear) to {
                chosenEnd = null
                endChosen = true
                endKeptFrom = null
                editingEnd = false
            }
            val endTooEarly = stringResource(Res.string.error_end_before_start)
            TimeDialog(end ?: time, onDismiss = { editingEnd = false }, reset = clear) {
                if (!it.isAfter(time) || sameMinute(it, time)) {
                    showMessage(endTooEarly)
                } else {
                    chosenEnd = it
                    endChosen = true
                    endKeptFrom = null
                    editingEnd = false
                }
            }
        }
        // Over the whole page, back closing it before it leaves the page.
        val motion = AppTheme.motionScheme
        AnimatedVisibility(
            editingPlace,
            enter = slideInVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutVertically(motion.defaultSpatialSpec()) { it / 4 } + fadeOut(motion.defaultEffectsSpec()),
        ) {
            // Back to the place the photos or the stay give, once another is chosen or saved.
            val reset = inferredPlace?.takeIf { (inferred, _) -> inferred != place }?.let { (_, source) ->
                stringResource(source.reset) to {
                    chosenPlace = null
                    editingPlace = false
                }
            }
            PlacePicker(current = place, fix = fix, places = places, search = container.placeSearch, onDismiss = { editingPlace = false }, reset = reset) {
                chosenPlace = it
                editingPlace = false
            }
        }
        // The photos picked in the viewer, fading over the page as a Moment's do, each drawn as the
        // Moment will show it; kept off covers, gone in as a photo alone or taken out from its bars.
        AnimatedVisibility(
            viewing != null && photos.isNotEmpty(),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
        ) {
            val start = remember { (viewing ?: 0).coerceIn(0, photos.lastIndex.coerceAtLeast(0)) }
            val media = photos.map { ref -> draftMedia(ref, originalMedia[ref], infos[ref], ref in videoPhotos, ref in utility, live = ref !in stills && ref !in masks) }
                // A photo saved with patches is shown as it was picked, the patches over it to change.
                .map { media -> media.local?.unmasked?.let { media.copy(local = media.local.copy(uri = it)) } ?: media }
            val refs = media.indices.associateBy { media[it].uid }.mapValues { photos[it.value] }
            // The NAS's original of the photo at [ref], linked to it by [link], put in its place, [live]
            // with its clip if the NAS keeps one, to be uploaded anew as [old] was; [arrived] says what
            // else goes with it, or false to leave [ref] as it is.
            val putInNasOriginal: (PhotoRef, cc.hyoban.moments.data.spec.NasLink, MomentMedia, Boolean, suspend (PhotoRef) -> Boolean) -> Unit = { ref, link, old, live, arrived ->
                replacing = ref
                scope.launch {
                    try {
                        val fresh = container.nas.downloadLinked(link, live)
                        if (!arrived(fresh)) return@launch
                        photos = photos.map { if (it == ref) fresh else it }
                        if (ref in utility) utility = utility - ref + fresh
                        replaced = replaced + (fresh to old)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        cc.hyoban.moments.logWarning("Moments", "nas: original not put in", e)
                        showMessage(textOf(Res.string.message_replace_failed))
                    } finally { replacing = null }
                }
            }
            MomentMediaViewer(
                container,
                media,
                start,
                // A motion photo picked and not yet saved plays its clip from its own file, where it
                // starts; any other as a saved one does ([draftMedia]).
                motionSource = { shown ->
                    val ref = refs[shown.uid]
                    val start = ref?.takeIf { it !in originalMedia && shown.type == MediaType.LIVE_PHOTO }?.let { infos[it]?.motionStart }
                    if (start != null) cc.hyoban.moments.ui.viewer.MotionSource(ref.toString(), start)
                    else container.mediaUrls.motion(shown)?.let { cc.hyoban.moments.ui.viewer.MotionSource(it) }
                },
                onBack = { viewing = null },
                caption = { shown ->
                    if (chosenMask?.first == refs[shown.uid]) return@MomentMediaViewer
                    val at = refs[shown.uid]?.let { infos[it]?.takenAt }
                    Text(
                        at?.let { listOfNotNull(formatDay(stamp(it)), formatTime(stamp(it))).joinToString(" ") }.orEmpty(),
                        style = AppTheme.typography.caption,
                        color = MediaOverlay.contentVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.s),
                    )
                },
                actions = { shown ->
                    val ref = refs[shown.uid] ?: return@MomentMediaViewer
                    // A patch chosen: the bar is its styles, the one it has marked, and taking it away.
                    val chosen = chosenMask?.takeIf { it.first == ref }?.second
                    if (chosen != null) {
                        val restyle: (String?) -> Unit = { style ->
                            val restyled = chosen.copy(sticker = style)
                            masks = masks + (ref to masks[ref].orEmpty().map { if (it == chosen) restyled else it })
                            chosenMask = ref to restyled
                            maskStyles = maskStyles + (chosen.plate to style)
                        }
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            StyleChoice(chosen.sticker == null, stringResource(Res.string.style_mosaic), "style_mosaic", { restyle(null) }) {
                                Icon(AppIcons.Mosaic, contentDescription = null, tint = MediaOverlay.content)
                            }
                            for (sticker in Sticker.forPlate(chosen.plate)) {
                                StickerChoice(sticker, chosen.sticker == sticker.key) { restyle(sticker.key) }
                            }
                        }
                        Tooltip(stringResource(Res.string.action_remove_mask)) {
                            IconButton(
                                onClick = {
                                    masks = masks + (ref to masks[ref].orEmpty() - chosen)
                                    chosenMask = null
                                },
                                modifier = Modifier.testTag("mask_remove"),
                            ) { Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.action_remove_mask), tint = MediaOverlay.content) }
                        }
                        return@MomentMediaViewer
                    }
                    // A photo pixelated goes in as a still: its clip would show what is covered, so
                    // its switch stays on, and says why. One put in for a saved photo goes back to
                    // that one, as it was, when switched back.
                    val back = swappedLive[ref]
                    if (ref in motionPhotos || back != null) {
                        val masked = ref in masks
                        BarToggle(
                            on = masked || ref in stills,
                            icon = AppIcons.LiveOff,
                            label = stringResource(if (masked && back == null) Res.string.action_photo_still_masked else Res.string.action_photo_still),
                            tag = "viewer_still",
                            enabled = !masked || back != null,
                        ) {
                            if (back != null) {
                                photos = photos.map { if (it == ref) back else it }
                                if (ref in utility) utility = utility - ref + back
                                replaced = replaced - ref
                                swappedLive = swappedLive - ref
                                stills = stills - ref
                                masks = masks - ref
                            } else stills = if (ref in stills) stills - ref else stills + ref
                        }
                    }
                    // A saved photo linked to the NAS: its clip can't be added to or taken from the
                    // site's copy, so the NAS's original goes in for it, as 换成 NAS 原图's does, live
                    // or a still as chosen, and is uploaded anew.
                    val linked = originalMedia[ref]?.takeIf { it.type != MediaType.VIDEO && it.nas != null && container.nas.configured }
                    if (linked != null) {
                        val masked = ref in masks
                        val live = linked.type == MediaType.LIVE_PHOTO
                        BarToggle(
                            on = masked || !live,
                            icon = AppIcons.LiveOff,
                            label = stringResource(if (masked) Res.string.action_photo_still_masked else Res.string.action_photo_still),
                            tag = "viewer_still",
                            enabled = !masked && replacing == null,
                        ) {
                            putInNasOriginal(ref, linked.nas!!, linked, !live) { fresh ->
                                val info = Photos.read(fresh).let { info -> container.nas.liveClip(fresh)?.let { info.copy(motionClip = it) } ?: info }
                                if (!live && !info.motion) {
                                    showMessage(textOf(Res.string.message_nas_not_live))
                                    return@putInNasOriginal false
                                }
                                infos[fresh] = info
                                askedMotion = askedMotion + fresh
                                // Going in live it is covered as it comes in, as a photo picked is; a
                                // still of a live photo public unpatched isn't.
                                if (live) {
                                    stills = stills + fresh
                                    scanned = scanned + fresh
                                }
                                swappedLive = swappedLive + (fresh to ref)
                                true
                            }
                        }
                    }
                    // On a photo new here, or one saved here and not yet published: a published one is the site's as it is.
                    val maskable = originalMedia[ref]?.let { it.published == null && it.local?.copied == true } ?: true
                    if (Masking.available && maskable && ref !in videoPhotos) {
                        BarToggle(ref in masks, AppIcons.Mosaic, stringResource(Res.string.action_photo_mask), "viewer_faces") {
                            if (ref in masks) {
                                masks = masks - ref
                                if (chosenMask?.first == ref) chosenMask = null
                                return@BarToggle
                            }
                            masks = masks + (ref to emptyList())
                            scope.launch {
                                val found = Masking.find(unmaskedOf(ref))
                                // Turned off meanwhile, it stays off; patches laid meanwhile stay beside those found.
                                val laid = masks[ref] ?: return@launch
                                masks = masks + (ref to found?.patches(laid, styleFor).orEmpty() + laid)
                                when {
                                    found == null -> showMessage(textOf(Res.string.message_masking_unavailable))
                                    found.isEmpty && laid.isEmpty() -> showMessage(textOf(Res.string.message_nothing_to_mask))
                                }
                            }
                        }
                    }
                    BarToggle(ref in utility, AppIcons.PhotoOff, stringResource(Res.string.action_photo_utility), "viewer_utility") {
                        utility = if (ref in utility) utility - ref else utility + ref
                    }
                },
                top = { shown ->
                    val ref = refs[shown.uid] ?: return@MomentMediaViewer
                    // 换成 NAS 原图: a published photo linked to the NAS, put back as the NAS's original,
                    // a photo new here: its faces and plates covered as it comes in, uploaded anew.
                    val link = originalMedia[ref]?.takeIf { it.published != null }?.nas?.takeIf { container.nas.configured }
                    // Offered only where the site's copy is less than the NAS's original.
                    val lesser = link != null && androidx.compose.runtime.key(ref) { rememberLesserThanNas(container, originalMedia.getValue(ref), link) }
                    if (link != null && lesser) Tooltip(stringResource(Res.string.action_replace_nas), below = true) {
                        IconButton(
                            onClick = {
                                val old = originalMedia.getValue(ref)
                                putInNasOriginal(ref, link, old, old.type == MediaType.LIVE_PHOTO) { fresh ->
                                    // It goes in as the old one went: a live photo with its clip, as it was
                                    // public unpatched, so neither asked about nor covered; a still as a
                                    // still, covered as it comes in, as the old one may have been.
                                    askedMotion = askedMotion + fresh
                                    if (old.type == MediaType.LIVE_PHOTO) scanned = scanned + fresh
                                    else if (old.type == MediaType.IMAGE) stills = stills + fresh
                                    true
                                }
                            },
                            enabled = replacing == null,
                            modifier = Modifier.testTag("viewer_replace_nas"),
                        ) {
                            if (replacing == ref) Spinner(Modifier.size(20.dp), color = MediaOverlay.content)
                            else Icon(AppIcons.Swap, contentDescription = stringResource(Res.string.action_replace_nas), tint = MediaOverlay.content)
                        }
                    }
                    val label = stringResource(if (ref in videoPhotos) Res.string.action_remove_video else Res.string.action_remove_photo)
                    IconButton(
                        onClick = {
                            photos = photos - ref
                            if (photos.isEmpty()) viewing = null
                        },
                        modifier = Modifier.testTag("viewer_remove"),
                    ) { Icon(AppIcons.Trash, contentDescription = label, tint = MediaOverlay.content) }
                },
                over = { shown ->
                    val ref = refs[shown.uid]
                    val laid = ref?.let { masks[it] }
                    if (ref != null && laid != null) {
                        MaskEditor(
                            unmaskedOf(ref),
                            laid,
                            selected = chosenMask?.takeIf { it.first == ref }?.second,
                            style = styleFor,
                            onSelect = { mask -> chosenMask = mask?.let { ref to it } },
                            onChange = { masks = masks + (ref to it) },
                        )
                    }
                },
            )
        }
    }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text(stringResource(Res.string.action_confirm)) } },
        )
    }
    if (unaskedMotion.isNotEmpty()) {
        val answer: (Boolean) -> Unit = { asPhotos ->
            if (asPhotos) stills = stills + unaskedMotion
            askedMotion = askedMotion + unaskedMotion
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text(stringResource(Res.string.confirm_motion)) },
            text = { Text(pluralStringResource(Res.plurals.confirm_motion_note, unaskedMotion.size, unaskedMotion.size)) },
            confirmButton = {
                TextButton(onClick = { answer(true) }, modifier = Modifier.testTag("motion_as_photos")) {
                    Text(stringResource(Res.string.action_add_as_photos))
                }
            },
            dismissButton = {
                TextButton(onClick = { answer(false) }, modifier = Modifier.testTag("motion_keep_live")) {
                    Text(stringResource(Res.string.action_keep_live))
                }
            },
        )
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(Res.string.confirm_discard)) },
            text = { Text(stringResource(Res.string.confirm_discard_note)) },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onBack() }, modifier = Modifier.testTag("discard")) {
                    Text(stringResource(Res.string.action_discard), color = tokens.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(Res.string.action_keep_writing)) } },
        )
    }
}

/**
 * [ref] as the viewer draws it before it is saved: as it was where it is [original], else the
 * picked file itself, a video played from it and, while it goes in [live], a live photo: a motion
 * photo's clip played from that file, an iPhone's from the clip beside it, as a saved one keeps it.
 */
private fun draftMedia(ref: PhotoRef, original: MomentMedia?, info: PhotoInfo?, video: Boolean, utility: Boolean, live: Boolean = false): MomentMedia =
    original?.copy(utility = utility.takeIf { it }) ?: MomentMedia(
        uid = "draft:$ref",
        type = when {
            video -> MediaType.VIDEO
            live && info?.motion == true -> MediaType.LIVE_PHOTO
            else -> MediaType.IMAGE
        },
        width = info?.width,
        height = info?.height,
        utility = utility.takeIf { it },
        local = LocalMedia(
            uri = ref.toString(),
            videoUri = if (video) ref.toString() else info?.motionClip?.takeIf { live }?.toPhotoRef()?.toString(),
        ),
    )

/**
 * Whether [media]'s published copy is less than the NAS's original it is linked to
 * ([cc.hyoban.moments.ui.picker.lesserThanNas]).
 */
@Composable
private fun rememberLesserThanNas(container: AppContainer, media: MomentMedia, link: cc.hyoban.moments.data.spec.NasLink): Boolean {
    // Hidden until the NAS's original says it is larger.
    return androidx.compose.runtime.produceState(false, media.uid, link) {
        value = container.lesserThanNas(media)
    }.value
}

/** One of a patch's styles in the viewer's bar, the one it has marked with a dot under it. */
@Composable
private fun StyleChoice(on: Boolean, label: String, tag: String, onChoose: () -> Unit, content: @Composable () -> Unit) {
    Tooltip(label) {
        IconButton(
            onClick = onChoose,
            modifier = Modifier.semantics { selected = on }.testTag(tag),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                content()
                if (on) Box(Modifier.align(Alignment.BottomCenter).size(4.dp).background(MediaOverlay.content, CircleShape))
            }
        }
    }
}

@Composable
private fun StickerChoice(sticker: Sticker, on: Boolean, onChoose: () -> Unit) {
    val label = stringResource(
        when (sticker) {
            Sticker.CAT -> Res.string.sticker_cat
            Sticker.DOG -> Res.string.sticker_dog
            Sticker.BEAR -> Res.string.sticker_bear
            Sticker.RABBIT -> Res.string.sticker_rabbit
            Sticker.SMILE -> Res.string.sticker_smile
            Sticker.FLOWER -> Res.string.sticker_flower
            Sticker.LYING_CAT -> Res.string.sticker_lying_cat
            Sticker.DACHSHUND -> Res.string.sticker_dachshund
            Sticker.CATERPILLAR -> Res.string.sticker_caterpillar
            Sticker.FISH -> Res.string.sticker_fish
        },
    )
    StyleChoice(on, label, "style_${sticker.key}", onChoose) { StickerImage(sticker, 30.dp) }
}

/** One of the viewer's switches for the photo shown: on, it stands filled. */
@Composable
private fun BarToggle(on: Boolean, icon: ImageVector, label: String, tag: String, enabled: Boolean = true, onToggle: () -> Unit) {
    Tooltip(label) {
        IconButton(
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier.semantics { toggleableState = ToggleableState(on) }.testTag(tag),
        ) {
            Box(
                Modifier.size(36.dp).alpha(if (enabled) 1f else Opacity.disabled)
                    .background(if (on) MediaOverlay.content else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = if (on) Color.Black else MediaOverlay.content)
            }
        }
    }
}

/** How wide the words stand at the start on a phone on its side, the rest of the Moment beside them. */
private val WORDS_COLUMN = 360.dp


/**
 * The photos picked, in a row, each with a button to take it out, a motion photo marked
 * as live unless it goes in as a photo, and tiles to pick more (the gallery and the paired
 * camera, in 选照片) or open the camera app to take one. A tap opens one in the viewer, where
 * it is kept off covers or goes in as a photo alone.
 * The first is the Moment's cover, so they are put in order as Material 3 has content
 * reordered: held and then dragged, the one held lifted under the finger and the others
 * stepping aside, the row scrolling on near its ends (Reorderable's); a screen reader moves one
 * earlier or later instead.
 */
@Composable
private fun PhotoStrip(
    photos: List<PhotoRef>,
    onAdd: () -> Unit,
    onOpenCamera: (() -> Unit)?,
    utility: Set<PhotoRef>,
    masked: Set<PhotoRef>,
    motion: Set<PhotoRef>,
    videos: Set<PhotoRef>,
    stills: Set<PhotoRef>,
    onOpen: (Int) -> Unit,
    onRemove: (PhotoRef) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val haptics = rememberHaptics()
    // Only the photos are moved; the tiles to add more after them stay where they are.
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        onMove(from.index, to.index)
        haptics.tick()
    }
    val liftSpec = AppTheme.motionScheme.fastSpatialSpec<Float>()
    val photoShape = AppTheme.shapes.medium
    val earlier = stringResource(Res.string.action_move_earlier)
    val later = stringResource(Res.string.action_move_later)
    val open = stringResource(Res.string.action_view)
    LazyRow(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(photos, key = { _, uri -> uri.toString() }) { index, uri ->
            // Where it is now: a photo keeps its key as it is moved, so a tap reads its place afresh.
            val at by rememberUpdatedState(index)
            ReorderableItem(reorder, key = uri.toString()) { lifted ->
                // Lifted over the rest at the dragged level, level 4, with the drag's state layer over
                // it, growing into that as it is picked up and settling back as it is put down.
                val lift by animateFloatAsState(if (lifted) 1f else 0f, liftSpec, label = "lift")
                val placement = Modifier.graphicsLayer {
                    val scale = 1f + (LIFTED_SCALE - 1f) * lift
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = ShadowElevation.toPx() * lift
                    shape = photoShape
                    clip = true
                }
                Box(
                    placement.size(PHOTO_SIZE).clip(photoShape)
                        // A tap opens it in the viewer. Outside the drag below, so the drag sees each
                        // move first and, consuming them, keeps a hold-and-drag from ending as a tap;
                        // a long press is left to it (a tap detector's own long press would consume
                        // the moves and call the drag off).
                        .pointerInput(uri) { detectTapGestures(onTap = { onOpen(at) }) }
                        .longPressDraggableHandle(onDragStarted = { haptics.lift() })
                        .semantics {
                            onClick(open) { onOpen(index); true }
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(earlier) { onMove(index, index - 1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction(later) { onMove(index, index + 1); true }.takeIf { index < photos.lastIndex },
                            )
                        }
                        .testTag("photo_$index"),
                ) {
                    // The site's photos by their URL, the phone's by their Uri.
                    AsyncImage(model = cc.hyoban.moments.data.local.imageModel(uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (lift > 0f) Box(Modifier.fillMaxSize().background(tokens.tx.copy(alpha = DRAGGED_LAYER * lift)))
                    // At the top, as a Moment's live photo has it: its bottom corners are the cover's and the patches' marks.
                    if (uri in motion && uri !in stills && uri !in masked) MediaBadge(BadgeKind.Live, Modifier.align(Alignment.TopStart).padding(Space.s))
                    if (uri in videos) MediaBadge(BadgeKind.Video(), Modifier.align(Alignment.TopStart).padding(Space.s))
                    if (uri in utility) {
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(Space.s).size(UTILITY_DOT).clip(CircleShape).background(MediaOverlay.control),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.PhotoOff, contentDescription = stringResource(Res.string.label_photo_utility), tint = MediaOverlay.content, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (uri in masked) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(Space.s).size(UTILITY_DOT).clip(CircleShape).background(MediaOverlay.control),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Mosaic, contentDescription = stringResource(Res.string.label_photo_masked), tint = MediaOverlay.content, modifier = Modifier.size(16.dp))
                        }
                    }
                    // A small dot to see, in a corner of the 48dp Material 3 has anything tapped take.
                    Box(
                        Modifier.align(Alignment.TopEnd).size(REMOVE_TARGET)
                            .clickable(
                                interactionSource = null,
                                indication = PressIndication,
                                role = Role.Button,
                                onClickLabel = stringResource(if (uri in videos) Res.string.action_remove_video else Res.string.action_remove_photo),
                            ) { onRemove(uri) }
                            .testTag("remove_photo_$index"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(REMOVE_DOT).clip(CircleShape).background(MediaOverlay.control),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Close, contentDescription = stringResource(if (uri in videos) Res.string.action_remove_video else Res.string.action_remove_photo), tint = MediaOverlay.content, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        if (photos.size < MAX_PHOTOS) {
            item(key = ADD_KEY) {
                AddTile(AppIcons.PhotoAdd, stringResource(Res.string.action_add_photos), onAdd, Modifier.animateItem().testTag("add_photos"))
            }
            // Only where the device has a camera app of its own to open.
            if (onOpenCamera != null) item(key = CAMERA_KEY) {
                AddTile(AppIcons.Camera, stringResource(Res.string.action_open_camera), onOpenCamera, Modifier.animateItem().testTag("open_camera"))
            }
        }
    }
}

/**
 * Whether the Moment is public, in the middle of the bar, a tap opening the two to choose from;
 * under a hairline, whether it is [planned], a check of its own, which the bar then says too.
 */
@Composable
private fun Visibility(private: Boolean, planned: Boolean, onPlanned: (Boolean) -> Unit, locked: Boolean, onLocked: ((Boolean) -> Unit)?, onPrivate: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                val seen = stringResource(if (private) Res.string.visibility_private else Res.string.visibility_public)
                val said = listOfNotNull(
                    seen,
                    stringResource(Res.string.trip_planned).takeIf { planned },
                    stringResource(Res.string.moment_lock).takeIf { locked },
                )
                Text(said.joinToString(" "))
            },
            leadingIcon = { Icon(if (private) AppIcons.EyeOff else AppIcons.Eye, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(AppIcons.CaretDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.semantics { role = Role.DropdownList }.testTag("moment_private"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(false to AppIcons.Eye, true to AppIcons.EyeOff).forEach { (choice, icon) ->
                // The chosen one on the accent's tint, its icon in the accent, besides its tick.
                val chosen = choice == private
                DropdownMenuItem(
                    text = { Text(stringResource(if (choice) Res.string.visibility_private else Res.string.visibility_public)) },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = if (chosen) tokens.accent else LocalContentColor.current) },
                    modifier = if (chosen) Modifier.background(tokens.accentSoft) else Modifier,
                    trailingIcon = if (chosen) {
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.trip_planned)) },
                leadingIcon = { Icon(AppIcons.Calendar, contentDescription = null, tint = if (planned) tokens.accent else LocalContentColor.current) },
                modifier = (if (planned) Modifier.background(tokens.accentSoft) else Modifier).testTag("moment_planned"),
                trailingIcon = if (planned) {
                    { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                } else {
                    null
                },
                onClick = {
                    open = false
                    onPlanned(!planned)
                },
            )
            // Settled, for 和 AI 规划 to leave as it is: a planned one, or a check-in still to come.
            if (onLocked != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.moment_lock)) },
                    leadingIcon = { Icon(AppIcons.Lock, contentDescription = null, tint = if (locked) tokens.accent else LocalContentColor.current) },
                    modifier = (if (locked) Modifier.background(tokens.accentSoft) else Modifier).testTag("moment_locked"),
                    trailingIcon = if (locked) {
                        { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onLocked(!locked)
                    },
                )
            }
        }
    }
}


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
fun TimeDialog(time: OffsetDateTime, onDismiss: () -> Unit, reset: Pair<String, () -> Unit>? = null, onPick: (OffsetDateTime) -> Unit) =
    DateTimeDialog(time.toLocalDate(), time.hour, time.minute, onDismiss, reset) { day, hour, minute ->
        onPick(OffsetDateTime.of(day, java.time.LocalTime.of(hour, minute), time.offset))
    }

/** Where the editor's own time for a Moment came from: its helper row's words, and its way back to it. */
enum class TimeSource(val label: StringResource, val reset: StringResource) {
    PHOTO(Res.string.source_photo, Res.string.time_use_photo),
    STAY(Res.string.source_stay, Res.string.time_use_stay),
    NOW(Res.string.source_now, Res.string.time_use_now),
}

/** A time the editor worked out: when it starts, when it ends if it lasted, and where that came from. */
data class InferredTime(val start: OffsetDateTime, val end: OffsetDateTime?, val source: TimeSource)

/**
 * The time the editor works out for a Moment by itself: from the earliest photo's to the latest's,
 * else, when none says, the times the photos' files were made ([saved]), else a new one's [stay]
 * (to [stayEnd]) or [now]. One being [editing] has only its photos' to go by, and none when they
 * say nothing. Photos all taken in one minute give no end.
 */
fun inferredTime(
    taken: List<OffsetDateTime>,
    saved: List<OffsetDateTime>,
    stay: OffsetDateTime?,
    now: OffsetDateTime,
    editing: Boolean,
    stayEnd: OffsetDateTime? = null,
): InferredTime? {
    val photos = taken.ifEmpty { saved }
    val first = photos.minByOrNull { it.toInstant() }
    val last = photos.maxByOrNull { it.toInstant() }
    if (first != null) return InferredTime(first, last?.takeUnless { sameMinute(it, first) }, TimeSource.PHOTO)
    if (editing) return null
    return stay?.let { InferredTime(it, stayEnd?.takeIf { end -> end.isAfter(it) && !sameMinute(end, it) }, TimeSource.STAY) }
        ?: InferredTime(now, null, TimeSource.NOW)
}

/** Where the editor's own place for a Moment came from: its helper row's words, and its way back to it. */
enum class PlaceOrigin(val label: StringResource, val reset: StringResource) {
    PHOTO(Res.string.source_photo, Res.string.place_use_photo),
    STAY(Res.string.source_stay, Res.string.place_use_stay),
}

/**
 * The place the editor works out for a Moment by itself: at a stay, the phone's own place there
 * ([atStay]); else a new place where the first photo that says was taken, or else the stay is,
 * named [name] after what is around it once that is known. None without either.
 */
fun inferredPlace(atStay: PlaceChoice.Known?, photoFix: Fix?, stayFix: Fix?, name: String?, kind: PlaceKind?): Pair<PlaceChoice, PlaceOrigin>? {
    if (atStay != null) return atStay to PlaceOrigin.STAY
    val fix = photoFix ?: stayFix ?: return null
    return PlaceChoice.New(name ?: return null, fix, kind) to if (photoFix != null) PlaceOrigin.PHOTO else PlaceOrigin.STAY
}

/** Whether two times are the same to the minute, as the time dialog picks them. */
fun sameMinute(a: OffsetDateTime, b: OffsetDateTime): Boolean =
    a.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES) == b.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)

/**
 * The Moment as a record, its photos and videos copied into the app's store first so it
 * keeps them whatever becomes of the gallery's, a motion photo split into its still and clip
 * unless it is one of [stills], and the place it makes, if it makes one. Changing
 * [original], it keeps its id, when it was made and the photos it already had as they
 * were. Its place is the one chosen, as a Moment is at one place at most.
 */
private suspend fun buildMoment(
    container: AppContainer,
    text: String,
    photos: List<PhotoRef>,
    utility: Set<PhotoRef>,
    stills: Set<PhotoRef>,
    masks: Map<PhotoRef, List<Mask>>,
    infos: Map<PhotoRef, PhotoInfo>,
    time: OffsetDateTime,
    end: OffsetDateTime?,
    place: PlaceChoice,
    private: Boolean,
    original: MomentRecord? = null,
    originalMedia: Map<PhotoRef, MomentMedia> = emptyMap(),
    uid: String? = null,
    replaced: Map<PhotoRef, MomentMedia> = emptyMap(),
): Pair<MomentRecord, Pair<String, Place>?> {
    val now = recordTime()
    val directory = File(container.localStore.root, "media")
    val media = photos.map { uri ->
        val kept = (uri in utility).takeIf { it }
        originalMedia[uri]?.let { media ->
            val local = media.local
            val patches = masks[uri].orEmpty()
            if (media.published != null || local == null || !local.copied || patches == local.masks.orEmpty()) return@map media.copy(utility = kept)
            // 打码 changed on a photo saved here: drawn again from the copy as it was picked.
            val source = File(URI(local.unmasked ?: local.uri))
            val drawnBefore = local.unmasked?.let { File(URI(local.uri)) }
            if (patches.isEmpty()) {
                drawnBefore?.delete()
                val size = Photos.read(source.toPhotoRef())
                return@map media.copy(
                    utility = kept,
                    width = size.width,
                    height = size.height,
                    local = local.copy(uri = source.toPhotoRef().toString(), sha256 = sha256Of(source), unmasked = null, masks = null),
                )
            }
            // Its clip would show what is covered: a photo with patches is a still.
            local.videoUri?.let { File(URI(it)).delete() }
            val drawn = Masking.pixelate(source, File(directory, "${media.uid}-${Ulid.next()}.jpg"), patches)
            drawnBefore?.delete()
            val size = Photos.read(drawn.file.toPhotoRef())
            return@map media.copy(
                type = MediaType.IMAGE,
                width = size.width,
                height = size.height,
                utility = kept,
                local = local.copy(
                    uri = drawn.file.toPhotoRef().toString(),
                    videoUri = null,
                    sha256 = drawn.sha256,
                    unmasked = source.toPhotoRef().toString(),
                    masks = patches,
                ),
            )
        }
        val uid = Ulid.next()
        val info = infos[uri] ?: Photos.read(uri)
        // A motion photo is split either way, so a photo alone is its still without the clip
        // trailing after it; a video gets a frame of itself as its still.
        val patches = masks[uri].orEmpty().takeIf { !info.video }.orEmpty()
        val copied = Photos.copy(uri, directory, uid, splitMotion = !info.video, poster = info.video)
            .let { photo ->
                // A clip kept beside the still, as an iPhone's, is copied beside it as a split one is.
                val clip = info.motionClip?.takeIf { !info.video && photo.video == null && it.isFile } ?: return@let photo
                photo.copy(video = clip.copyTo(File(directory, "$uid.${clipExtension(clip)}"), overwrite = true))
            }
            .let { photo ->
                if (info.video || (uri !in stills && patches.isEmpty())) return@let photo
                photo.video?.delete()
                photo.copy(video = null)
            }
        // 打码: the patches drawn into a copy of their own, the copy as picked kept beside it to change them again.
        val drawn = if (patches.isEmpty()) null else Masking.pixelate(copied.file, File(directory, "$uid-${Ulid.next()}.jpg"), patches)
        // A pixelated copy is upright and may be smaller than what was picked.
        val size = drawn?.let { Photos.read(it.file.toPhotoRef()) } ?: info
        MomentMedia(
            uid = uid,
            // In for a published one (换成 NAS 原图), it says what that one said.
            alt = replaced[uri]?.alt.orEmpty(),
            type = when {
                info.video -> MediaType.VIDEO
                copied.video != null -> MediaType.LIVE_PHOTO
                else -> MediaType.IMAGE
            },
            width = size.width,
            height = size.height,
            takenAt = info.takenAt?.let(::stamp),
            utility = kept,
            nas = container.nas.linkOf(uri),
            local = LocalMedia(
                uri = (drawn ?: copied).file.toPhotoRef().toString(),
                videoUri = copied.video?.let { it.toPhotoRef().toString() },
                sha256 = (drawn ?: copied).sha256,
                copied = true,
                unmasked = drawn?.let { copied.file.toPhotoRef().toString() },
                masks = patches.takeIf { drawn != null },
            ),
        )
    }
    val newPlace = (place as? PlaceChoice.New)?.let {
        newPlaceId() to it.toPlace(now)
    }
    val placeIds = when (place) {
        is PlaceChoice.Known -> listOf(place.id)
        is PlaceChoice.New -> listOfNotNull(newPlace?.first)
        PlaceChoice.None -> emptyList()
    }
    val moment = original?.copy(occurredAt = stamp(time), end = end?.let(::stamp), places = placeIds, text = text.trim(), media = media, hidden = private, updatedAt = now)
        ?: MomentRecord(
            uid = uid ?: Ulid.next(),
            occurredAt = stamp(time),
            end = end?.let(::stamp),
            places = placeIds,
            text = text.trim(),
            media = media,
            hidden = private,
            createdAt = now,
            updatedAt = now,
        )
    return moment to newPlace
}

/** What says why a picked file was left out. */
private fun refusalText(refusal: Refusal): StringResource = when (refusal) {
    Refusal.NOT_MEDIA -> Res.string.error_not_media
    Refusal.VIDEO_TYPE -> Res.string.error_video_type
    Refusal.VIDEO_TOO_BIG -> Res.string.error_video_too_big
}

/** The side padding of a row, outside the page's gutter so its icon and words line up with the words above. */
private val ROW_PADDING = 16.dp
/** How many photos and videos a Moment holds. */
const val MAX_PHOTOS = 9

/** How long, back from the camera app, what it is still saving is waited for. */
private const val CAMERA_SETTLE_MILLIS = 15_000L

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

/** A clip's extension by its first box's brand: QuickTime's `qt  `, else MP4. */
private fun clipExtension(clip: File): String {
    val head = runCatching { clip.inputStream().use { it.readNBytes(12) } }.getOrNull() ?: return "mp4"
    return if (head.size == 12 && String(head, 4, 8, Charsets.ISO_8859_1) == "ftypqt  ") "mov" else "mp4"
}
