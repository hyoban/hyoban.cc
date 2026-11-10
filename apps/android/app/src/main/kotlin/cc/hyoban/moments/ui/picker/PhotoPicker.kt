package cc.hyoban.moments.ui.picker

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.camera.CameraLink
import cc.hyoban.moments.camera.CameraPhoto
import cc.hyoban.moments.camera.CameraStore
import cc.hyoban.moments.camera.CameraTransfer
import cc.hyoban.moments.camera.rememberCameraPermissionAsk
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PermissionWords
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.rememberPermissionAsk
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LinearProgressIndicator
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.PhotoDetails
import cc.hyoban.moments.ui.viewer.PhotoInfoPanel
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A photo the picker shows: one in the phone's gallery, or one on the paired camera's card. */
private sealed interface Pick {
    val key: String
    val day: LocalDate?
    /** Whether it can be chosen: a camera's RAW is only looked at. */
    val choosable: Boolean

    data class Local(val item: LocalItem) : Pick {
        override val key get() = item.key
        override val day get() = item.day
        override val choosable get() = true
    }

    data class Camera(val photo: CameraPhoto) : Pick {
        override val key get() = "camera/${photo.key}"
        override val day get() = photo.captured?.toLocalDate()
        override val choosable get() = photo.photo
    }
}

/** Where the picker's photos come from: the whole gallery, one of its albums, or the paired camera. */
private sealed interface Source {
    data object All : Source
    data class Album(val id: Long, val name: String) : Source
    data object Camera : Source
}

/**
 * 选照片 (39), over the Moment being written, in place of the system's picker: the phone's
 * gallery and the paired camera in one page, one grid and one viewer, switched from the title.
 * What is chosen stays chosen across them, numbered in the order it goes into the Moment, at
 * most [limit]. The gallery asks for its permission first; refused, [onFallback] opens the
 * system's documents picker instead. [onAdded] has the photos, the camera's saved to the
 * gallery's Here album first. Leaving it leaves the camera's network.
 */
@Composable
fun PhotoPickerScreen(container: AppContainer, limit: Int, onClose: () -> Unit, onFallback: () -> Unit, onAdded: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // The gallery: asked for once, with where its photos were taken.
    var access by remember { mutableStateOf(LocalMedia.access(context)) }
    val galleryAsk = rememberPermissionAsk(
        LocalMedia.permissions + Manifest.permission.ACCESS_MEDIA_LOCATION,
        PermissionWords(
            AppIcons.Photos,
            stringResource(R.string.permission_gallery_title),
            stringResource(R.string.permission_gallery_body),
            stringResource(R.string.permission_gallery_secondary),
            listOf(
                PermissionFact(AppIcons.Image, stringResource(R.string.permission_gallery_local)),
                PermissionFact(AppIcons.Shield, stringResource(R.string.permission_photo_candidate)),
            ),
        ),
        isGranted = { LocalMedia.access(it) != GalleryAccess.NONE },
        onDecline = onFallback,
    ) { granted ->
        access = LocalMedia.access(context)
        if (!granted) onFallback()
    }
    LaunchedEffect(Unit) { if (access == GalleryAccess.NONE) galleryAsk() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = LocalMedia.access(context) }
    // 更改: the system's choice of photos again, on Android 14 and later, or all of them.
    val reselect = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = LocalMedia.access(context) }
    val items by remember(access) { if (access == GalleryAccess.NONE) flowOf(emptyList()) else LocalMedia.items(context) }
        .collectAsStateWithLifecycle(emptyList())
    val albums = remember(items) { LocalMedia.albums(items) }

    // The camera, reached only once it is the source, and kept until the page closes.
    val camera by CameraStore.camera.collectAsStateWithLifecycle()
    val cameraSupported = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && CameraLink.supported(context) }
    var source by remember { mutableStateOf<Source>(Source.All) }
    var transfer by remember { mutableStateOf<CameraTransfer?>(null) }
    val connect: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            transfer?.close()
            transfer = CameraTransfer(context.applicationContext).also { scope.launch { it.connect() } }
        }
    }
    val cameraAsk = rememberCameraPermissionAsk(
        emptyArray(),
        listOf(PermissionFact(AppIcons.Wifi, stringResource(R.string.permission_camera_wifi))),
        onDecline = { source = Source.All },
    ) { granted -> if (granted) connect() else source = Source.All }
    val currentTransfer by rememberUpdatedState(transfer)
    DisposableEffect(Unit) { onDispose { currentTransfer?.close() } }
    val cameraState = transfer?.state?.collectAsStateWithLifecycle()?.value

    val chosen = remember { mutableStateListOf<Pick>() }
    val order by remember { derivedStateOf { chosen.withIndex().associate { (i, p) -> p.key to i } } }
    val full = chosen.size >= limit
    val toggle: (Pick) -> Unit = { pick ->
        val at = order[pick.key]
        if (at != null) { haptics.toggle(false); chosen.removeAt(at) }
        else if (!full && pick.choosable) { haptics.toggle(true); chosen.add(pick) }
    }

    val shown: List<Pick> = when (val s = source) {
        Source.All -> remember(items) { items.map { Pick.Local(it) } }
        is Source.Album -> remember(items, s.id) { items.filter { it.albumId == s.id }.map { Pick.Local(it) } }
        Source.Camera -> (cameraState as? CameraTransfer.State.Ready)?.photos?.let { photos -> remember(photos) { photos.map { Pick.Camera(it) } } }.orEmpty()
    }

    // Adding: the gallery's as they are, the camera's downloaded first, in the order chosen.
    var adding by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var within by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    val cameraChosen = chosen.filterIsInstance<Pick.Camera>()
    val add: () -> Unit = {
        failed = false
        adding = scope.launch {
            try {
                val fromCamera = chosen.filterIsInstance<Pick.Camera>()
                val saved = if (fromCamera.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) emptyList()
                    else transfer!!.download(fromCamera.map { it.photo }) { index, fraction -> done = index; within = fraction }
                val byKey = fromCamera.map { it.key }.zip(saved).toMap()
                onAdded(chosen.mapNotNull { pick ->
                    when (pick) {
                        is Pick.Local -> LocalMedia.forMoment(context, pick.item.uri)
                        is Pick.Camera -> byKey[pick.key]
                    }
                })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
            } finally { adding = null }
        }
    }

    // Deleting from the camera: the chosen ones from the grid, or the one in the viewer.
    var deleting by remember { mutableStateOf<List<CameraPhoto>?>(null) }
    var removing by remember { mutableStateOf<Job?>(null) }
    var removed by remember { mutableIntStateOf(0) }
    var toRemove by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Moving one of the gallery's to its bin: the system asks, so the app doesn't too.
    var trashed by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var afterDelete by remember { mutableStateOf<String?>(null) }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val gone = trashed.map { it.key }.toSet()
        trashed = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            chosen.removeAll { it.key in gone }
            if (viewing in gone) viewing = afterDelete
        }
    }
    // One request for them all, so the system asks once.
    val moveToBin: (List<LocalItem>) -> Unit = { these ->
        runCatching {
            val request = MediaStore.createTrashRequest(context.contentResolver, these.map { it.uri }, true)
            trashed = these
            trash.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { Toast.makeText(context, R.string.picker_trash_failed, Toast.LENGTH_SHORT).show() }
    }

    val idle = adding == null && removing == null
    val sideways = isPhoneLandscape()
    val title = when (val s = source) {
        Source.All -> stringResource(R.string.picker_all)
        is Source.Album -> s.name
        Source.Camera -> camera?.name ?: stringResource(R.string.camera_model)
    }
    val count = when (source) {
        Source.Camera -> (cameraState as? CameraTransfer.State.Ready)?.photos?.size
        else -> shown.size
    }
    val subtitle = listOfNotNull(
        count?.let { stringResource(R.string.camera_photo_count, it) },
        chosen.size.takeIf { it > 0 }?.let { stringResource(R.string.picker_chosen_count, it) },
    ).joinToString(" · ").ifEmpty { null }
    val menu = TitleMenu(stringResource(R.string.picker_switch)) { close ->
        @Composable
        fun item(label: String, number: Int?, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, tag: String, pick: () -> Unit) =
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        number?.let { Text("$it", style = AppTheme.typography.caption, color = tokens.tx3) }
                        if (on) Icon(AppIcons.Check, contentDescription = null)
                    }
                },
                onClick = { close(); pick() },
                modifier = Modifier.testTag(tag),
            )
        item(stringResource(R.string.picker_all), items.size, AppIcons.Photos, source == Source.All, "picker_all") { source = Source.All }
        albums.forEach { album ->
            item(album.name, album.count, AppIcons.Folder, (source as? Source.Album)?.id == album.id, "picker_album_${album.id}") { source = Source.Album(album.id, album.name) }
        }
        val paired = camera
        if (paired != null && cameraSupported) {
            item(paired.name, null, AppIcons.CameraBody, source == Source.Camera, "picker_camera") {
                source = Source.Camera
                if (transfer == null) cameraAsk()
            }
        }
    }
    val addButton = @Composable { tag: String, first: () -> Unit ->
        Button(onClick = { first(); add() }, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.camera_add, chosen.size)) }
    }
    val canAdd = chosen.isNotEmpty() && idle
    val canDeleteCamera = source == Source.Camera && cameraChosen.isNotEmpty() && idle
    // The bar's delete takes what is chosen from the source shown: the gallery's, or the camera's.
    val localChosen = chosen.filterIsInstance<Pick.Local>()
    val canTrashLocal = source != Source.Camera && localChosen.isNotEmpty() && idle

    SubPage(
        title, onClose, subtitle = subtitle, close = true, titleMenu = menu,
        actions = {
            if (canDeleteCamera) IconButton(onClick = { deleting = cameraChosen.map { it.photo } }, modifier = Modifier.testTag("camera_delete_chosen")) {
                Icon(AppIcons.Trash, contentDescription = stringResource(R.string.camera_delete_chosen, cameraChosen.size))
            }
            if (canTrashLocal) IconButton(onClick = { moveToBin(localChosen.map { it.item }) }, modifier = Modifier.testTag("picker_trash_chosen")) {
                Icon(AppIcons.Trash, contentDescription = stringResource(R.string.camera_delete_chosen, localChosen.size))
            }
            if (sideways && canAdd) addButton("picker_add") {}
        },
    ) {
        if (source == Source.Camera) when (val s = cameraState) {
            null, is CameraTransfer.State.Connecting -> Steps((s as? CameraTransfer.State.Connecting)?.step ?: CameraTransfer.Step.WIFI_ON)
            is CameraTransfer.State.Failed -> Failed(s.reason) { connect() }
            is CameraTransfer.State.Ready -> Grid(shown, order, full, transfer, enabled = idle, columns = if (sideways) 8 else 4, limited = null, onToggle = toggle) { viewing = it.key }
        } else Grid(
            shown, order, full, transfer, enabled = idle, columns = if (sideways) 8 else 4,
            limited = if (access == GalleryAccess.CHOSEN) ({ Limited(items.size) { reselect.launch(LocalMedia.permissions) } }) else null,
            onToggle = toggle,
        ) { viewing = it.key }
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth()) {
            if (removing != null) ProgressBar(stringResource(R.string.camera_deleting_count, (removed + 1).coerceAtMost(toRemove), toRemove), removed.toFloat() / toRemove.coerceAtLeast(1), null) { removing?.cancel() }
            else if (adding != null && cameraChosen.isNotEmpty() || failed) {
                val n = cameraChosen.size.coerceAtLeast(1)
                if (failed) ProgressBar(stringResource(R.string.camera_download_failed), null, null, retry = add) {}
                else ProgressBar(stringResource(R.string.picker_adding, (done + 1).coerceAtMost(n), n), (done + within) / n, stringResource(R.string.picker_add_note)) { adding?.cancel() }
            } else if (!sideways && canAdd) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.gutter), contentAlignment = Alignment.CenterEnd) {
                addButton("picker_add") {}
            }
        }
    }

    viewing?.let { key -> shown.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }?.let { start ->
        Viewer(
            container, transfer, shown, start, order, full, enabled = idle && !busy, busy = busy, onToggle = toggle,
            addButton = { if (canAdd) addButton("picker_viewer_add") { viewing = null } },
            onDelete = { pick ->
                val at = shown.indexOfFirst { it.key == pick.key }
                afterDelete = (shown.getOrNull(at + 1) ?: shown.getOrNull(at - 1))?.key
                when (pick) {
                    is Pick.Camera -> deleting = listOf(pick.photo)
                    is Pick.Local -> moveToBin(listOf(pick.item))
                }
            },
            onClose = { viewing = null },
        )
    }

    deleting?.let { these ->
        val one = these.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (one != null) stringResource(R.string.camera_delete_title) else stringResource(R.string.camera_delete_many_title, these.size)) },
            text = { Text(if (one != null) stringResource(R.string.camera_delete_body, one.name) else stringResource(R.string.camera_delete_many_body)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val camera = transfer ?: return@TextButton
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@TextButton
                    if (one != null && viewing != null) {
                        busy = true
                        scope.launch {
                            val gone = camera.delete(one)
                            busy = false
                            if (gone) {
                                chosen.removeAll { it is Pick.Camera && it.photo.content == one.content }
                                viewing = afterDelete
                            } else Toast.makeText(context, R.string.camera_delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // One at a time, each leaving the choice once the camera says it's gone;
                        // those it couldn't delete stay chosen.
                        removed = 0
                        toRemove = these.size
                        removing = scope.launch {
                            var missed = 0
                            try {
                                these.forEach { photo ->
                                    if (camera.delete(photo)) chosen.removeAll { it is Pick.Camera && it.photo.content == photo.content } else missed++
                                    removed++
                                }
                            } finally {
                                removing = null
                                if (missed > 0) Toast.makeText(context, context.getString(R.string.camera_delete_missed, missed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, modifier = Modifier.testTag("camera_delete_confirm")) { Text(stringResource(R.string.camera_delete), color = tokens.danger) }
            },
        )
    }
}

/** The bar at the foot of the page while something goes on: what, how far, and stopping it; or failed, to try again. */
@Composable
private fun ProgressBar(words: String, fraction: Float?, note: String?, retry: (() -> Unit)? = null, onStop: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = tokens.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(words, style = AppTheme.typography.body, modifier = Modifier.weight(1f))
                if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.action_retry)) }
                else TextButton(onClick = onStop, modifier = Modifier.testTag("picker_stop")) { Text(stringResource(R.string.camera_stop)) }
            }
            if (fraction != null) LinearProgressIndicator({ fraction }, color = tokens.tx)
            note?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2) }
        }
    }
}

/** Only some of the gallery may be seen: says so, and [onChange] asks the system again. */
@Composable
private fun Limited(count: Int, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.xs)
            .background(tokens.fill, AppTheme.shapes.medium).padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.picker_limited, count), style = AppTheme.typography.body, color = tokens.tx2, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange, modifier = Modifier.testTag("picker_limited_change")) { Text(stringResource(R.string.picker_change)) }
    }
}

@Composable
private fun Steps(step: CameraTransfer.Step) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current).padding(horizontal = Space.gutter, vertical = Space.s)) {
        listOf(
            CameraTransfer.Step.WIFI_ON to R.string.camera_step_wifi_on,
            CameraTransfer.Step.JOINING to R.string.camera_step_joining,
            CameraTransfer.Step.READING to R.string.camera_step_reading,
        ).forEach { (s, label) ->
            val past = s.ordinal < step.ordinal
            val now = s == step
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    when {
                        now -> Spinner()
                        past -> Icon(AppIcons.Check, contentDescription = null, tint = tokens.tx2)
                        else -> Icon(AppIcons.Photos, contentDescription = null, tint = tokens.tx3)
                    }
                }
                Text(stringResource(label), style = AppTheme.typography.body, color = if (now) tokens.tx else if (past) tokens.tx2 else tokens.tx3)
            }
        }
        Text(stringResource(R.string.camera_join_note), style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.padding(top = Space.s))
    }
}

@Composable
private fun Failed(reason: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = LocalTopClearance.current + Space.xl * 3).padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(AppIcons.CameraBody, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(48.dp))
        Text(stringResource(reason), style = AppTheme.typography.body)
        Text(stringResource(R.string.camera_failed_note), style = AppTheme.typography.caption, color = tokens.tx3)
        Box(Modifier.padding(top = Space.l)) { Button(onClick = onRetry, modifier = Modifier.testTag("camera_retry")) { Text(stringResource(R.string.action_retry)) } }
    }
}

/** The gallery's thumbnails, by the media store's id, shared by the grid and the viewer. */
private val localThumbs = LruCache<String, Bitmap>(300)

/** [pick]'s thumbnail, at [size] pixels for the gallery's, as the camera sent it for the camera's. */
@Composable
private fun rememberThumb(pick: Pick, transfer: CameraTransfer?, size: Int = 320): State<Bitmap?> {
    val context = LocalContext.current
    val cacheKey = "${pick.key}@$size"
    return produceState(localThumbs.get(cacheKey), cacheKey) {
        if (value != null) return@produceState
        value = when (pick) {
            is Pick.Local -> withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(pick.item.uri, Size(size, size), null) }.getOrNull()
            }?.also { localThumbs.put(cacheKey, it) }
            is Pick.Camera -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) transfer?.thumbnail(pick.photo) else null
        }
    }
}

/** What a tile says in its corner: a video's length, 实况, or RAW. */
@Composable
private fun badge(pick: Pick): String? {
    val context = LocalContext.current
    return when (pick) {
        is Pick.Camera -> if (pick.photo.raw) "RAW" else null
        is Pick.Local -> if (pick.item.video) pick.item.duration?.let(::clock) else {
            val live by produceState(false, pick.key) { value = withContext(Dispatchers.IO) { LocalMedia.isMotion(context, pick.item) } }
            if (live) stringResource(R.string.label_live) else null
        }
    }
}

private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun Grid(
    shown: List<Pick>,
    order: Map<String, Int>,
    full: Boolean,
    transfer: CameraTransfer?,
    enabled: Boolean,
    columns: Int,
    limited: (@Composable () -> Unit)?,
    onToggle: (Pick) -> Unit,
    onOpen: (Pick) -> Unit,
) {
    val today = remember { LocalDate.now() }
    // By the day each was taken, newest first.
    val days = remember(shown) { shown.groupBy { it.day }.toList() }
    LazyVerticalGrid(
        GridCells.Fixed(columns),
        Modifier.fillMaxSize().testTag("picker_grid"),
        contentPadding = PaddingValues(top = LocalTopClearance.current, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (limited != null) item(key = "limited", span = { GridItemSpan(maxLineSpan) }) { limited() }
        if (shown.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.picker_empty), style = AppTheme.typography.body, color = tokens.tx3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Space.xl * 3))
        }
        days.forEach { (day, picks) ->
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (day) { null -> stringResource(R.string.camera_no_date); today -> stringResource(R.string.camera_today); else -> formatDay(day.toString(), today) },
                    style = AppTheme.typography.caption, color = tokens.tx2,
                    modifier = Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
                )
            }
            items(picks, key = { it.key }) { pick ->
                val index = order[pick.key] ?: -1
                val thumb by rememberThumb(pick, transfer)
                val corner = badge(pick)
                val name = when (pick) { is Pick.Local -> pick.item.name.orEmpty(); is Pick.Camera -> pick.photo.name }
                val label = when {
                    pick is Pick.Camera && pick.photo.raw -> stringResource(R.string.camera_raw_description, name)
                    pick is Pick.Local && pick.item.video -> stringResource(R.string.picker_video_description, name)
                    else -> stringResource(R.string.camera_photo_description, name)
                }
                // At the limit, what can't be chosen any more is set back.
                val setBack = full && index < 0
                Box(
                    Modifier.aspectRatio(1f).background(tokens.fill)
                        .then(if (index >= 0) Modifier.border(3.dp, tokens.accent) else Modifier)
                        .semantics { contentDescription = label; selected = index >= 0 }
                        .clickable(onClickLabel = stringResource(R.string.camera_view)) { onOpen(pick) },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (setBack) 0.38f else 1f)) }
                    corner?.let {
                        Text(it, style = AppTheme.typography.caption.strong, color = MediaOverlay.content, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                    // The circle chooses it, its target the corner's 48dp.
                    if (pick.choosable) Box(
                        Modifier.align(Alignment.TopEnd).size(48.dp)
                            .clickable(enabled = enabled && !setBack, role = Role.Checkbox, onClickLabel = stringResource(R.string.camera_choose)) { onToggle(pick) }
                            .testTag("picker_choose_${pick.key}"),
                        contentAlignment = Alignment.TopEnd,
                    ) { ChoiceMark(index, Modifier.padding(6.dp).size(22.dp).alpha(if (setBack) 0.38f else 1f)) }
                }
            }
        }
    }
}

/** How far an original is: the spinner, how much is down of how much, and how fast; or that it failed, to try again. */
@Composable
private fun Loading(original: CameraTransfer.Original, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
        if (original.failed) {
            Text(stringResource(R.string.camera_download_failed), style = AppTheme.typography.body, color = MediaOverlay.content)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), color = MediaOverlay.content) }
            return@Column
        }
        Spinner(color = MediaOverlay.content)
        val seconds = (SystemClock.elapsedRealtime() - original.started) / 1000f
        val perSecond = if (seconds > 0.3f) (original.done / seconds).toLong() else 0L
        Text(
            stringResource(R.string.camera_loading, Formatter.formatShortFileSize(context, original.done), Formatter.formatShortFileSize(context, original.size),
                Formatter.formatShortFileSize(context, perSecond)),
            style = AppTheme.typography.caption, color = MediaOverlay.content,
        )
    }
}

/** Whether a photo is chosen: an empty ring, or the accent with its place among those chosen. */
@Composable
private fun ChoiceMark(index: Int, modifier: Modifier) {
    Box(
        modifier.then(if (index >= 0) Modifier.background(tokens.accent, CircleShape) else Modifier.background(MediaOverlay.control, CircleShape).border(2.dp, MediaOverlay.content, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (index >= 0) Text("${index + 1}", style = AppTheme.typography.caption.strong, color = tokens.onAccent)
    }
}

/** What [pick] says of itself for the info panel: what the list knows, and the EXIF once it can be read. */
@Composable
private fun rememberDetails(pick: Pick, transfer: CameraTransfer?): PhotoDetails? {
    val context = LocalContext.current
    val cardName = stringResource(R.string.picker_camera_card)
    val original = (pick as? Pick.Camera)?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) transfer?.originalOf(it.photo)?.file else null }
    return produceState<PhotoDetails?>(null, pick.key, original) {
        value = when (pick) {
            is Pick.Local -> {
                val item = pick.item
                val base = PhotoDetails(
                    takenAt = Instant.ofEpochMilli(item.taken).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                    name = item.name, format = PhotoDetails.format(item.mime, item.name),
                    width = item.width, height = item.height, bytes = item.bytes, album = item.album,
                )
                value = base
                if (item.video) base else PhotoDetails.readLocal(context, base, LocalMedia.forMoment(context, item.uri))
            }
            is Pick.Camera -> {
                val photo = pick.photo
                val base = PhotoDetails(
                    takenAt = photo.captured?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
                    name = photo.name, format = PhotoDetails.format(photo.mime, photo.name), bytes = photo.size, album = cardName,
                )
                if (original != null) PhotoDetails.read(base) { original.inputStream() } else base
            }
        }
    }.value
}

/**
 * The photos full size, black as the photo viewer (03): swiped through, chosen and added from
 * here too. A camera's photo is its thumbnail enlarged until its original is down in the cache.
 * Swiped up, the photo makes room for its info (PhotoInfo); swiped down, the info goes, else the viewer.
 */
@Composable
private fun Viewer(
    container: AppContainer,
    transfer: CameraTransfer?,
    shown: List<Pick>,
    start: Int,
    order: Map<String, Int>,
    full: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Pick) -> Unit,
    addButton: @Composable () -> Unit,
    onDelete: (Pick) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { shown.size }
    var info by remember { mutableStateOf(false) }
    BackHandler { if (info) info = false else onClose() }
    // Light icons over the black while it is open, on the dialog's own window the picker is in.
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
    val sideways = isPhoneLandscape()
    val opened by animateFloatAsState(if (info) 1f else 0f, AppTheme.motionScheme.defaultSpatialSpec(), label = "info")
    var drag by remember { mutableFloatStateOf(0f) }
    val current = shown.getOrNull(pager.currentPage)
    val details = current?.let { rememberDetails(it, transfer) }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("picker_viewer")) {
        // Zoomed in, a swipe moves the photo rather than turning the page.
        var zoomed by remember { mutableStateOf(false) }
        // The original of the camera's photo shown, once the pager is still, so swiping past doesn't queue downloads.
        LaunchedEffect(current?.key, pager.isScrollInProgress) {
            val pick = current as? Pick.Camera ?: return@LaunchedEffect
            if (!pager.isScrollInProgress && pick.photo.photo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) transfer?.loadOriginal(pick.photo)
        }
        val layout: @Composable (@Composable (Modifier) -> Unit, @Composable (Modifier) -> Unit) -> Unit = { photo, panel ->
            if (sideways) Row(Modifier.fillMaxSize()) {
                photo(Modifier.weight(1f).fillMaxHeight())
                if (opened > 0f) panel(Modifier.width(360.dp * opened).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                photo(Modifier.fillMaxWidth().weight(1f))
                if (opened > 0f) panel(Modifier.fillMaxWidth().weight(0.0001f + 1.4f * opened))
            }
        }
        layout({ modifier ->
            HorizontalPager(
                pager,
                modifier.statusBarsPadding().draggable(
                    orientation = Orientation.Vertical,
                    enabled = !zoomed,
                    state = rememberDraggableState { drag += it },
                    onDragStopped = { velocity ->
                        val up = drag < -80f || velocity < -1500f
                        val down = drag > 80f || velocity > 1500f
                        drag = 0f
                        when {
                            up -> info = true
                            down && info -> info = false
                            down -> onClose()
                        }
                    },
                ),
                beyondViewportPageCount = 0, userScrollEnabled = !zoomed, key = { shown[it].key },
            ) { page ->
                val pick = shown[page]
                val settled = pager.currentPage == page
                ViewerPage(pick, transfer) { if (settled) zoomed = it }
            }
        }) { modifier -> PhotoInfoPanel(container, details, modifier, note = stringResource(R.string.picker_info_camera_note).takeIf { current is Pick.Camera && details?.make == null && details?.focal == null }) }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("picker_viewer_close")) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), tint = MediaOverlay.content)
            }
            Text("${pager.currentPage + 1} / ${shown.size}", style = AppTheme.typography.body, color = MediaOverlay.content,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            val local = current is Pick.Local
            IconButton(onClick = { current?.let(onDelete) }, enabled = enabled && current != null, modifier = Modifier.testTag("picker_viewer_delete")) {
                Icon(AppIcons.Trash, contentDescription = stringResource(if (local) R.string.picker_trash else R.string.camera_delete), tint = MediaOverlay.content)
            }
        }
        if (busy) Column(
            Modifier.fillMaxSize().background(MediaOverlay.bar),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
        ) {
            Spinner(color = MediaOverlay.content)
            Text(stringResource(R.string.camera_deleting), style = AppTheme.typography.caption, color = MediaOverlay.content)
        }
        if (current != null && !info) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val at = details?.takenAt
                Text(
                    listOfNotNull(at?.let { "${formatDay(it.toLocalDate().toString())} ${"%02d:%02d".format(it.hour, it.minute)}" },
                        when (current) { is Pick.Local -> current.item.album; is Pick.Camera -> current.photo.name }).joinToString(" · "),
                    style = AppTheme.typography.caption, color = MediaOverlay.content, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { info = true }, modifier = Modifier.testTag("picker_viewer_info")) {
                    Icon(AppIcons.Info, contentDescription = stringResource(R.string.photo_info), tint = MediaOverlay.content)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val index = order[current.key] ?: -1
                if (current.choosable) Row(
                    Modifier.weight(1f).height(48.dp)
                        .clickable(enabled = enabled && (index >= 0 || !full), role = Role.Checkbox) { onToggle(current) }
                        .semantics { selected = index >= 0 }
                        .testTag("picker_viewer_choose"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    ChoiceMark(index, Modifier.size(26.dp))
                    Text(stringResource(if (index >= 0) R.string.camera_chosen else R.string.camera_choose), style = AppTheme.typography.body, color = MediaOverlay.content)
                } else Text(stringResource(R.string.camera_raw_kept), style = AppTheme.typography.body, color = MediaOverlay.content, modifier = Modifier.weight(1f))
                addButton()
            }
        }
    }
}

/** One photo in the viewer: the gallery's from the file itself, the camera's as above. */
@Composable
private fun ViewerPage(pick: Pick, transfer: CameraTransfer?, onZoom: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (pick) {
            is Pick.Local -> {
                val item = pick.item
                val small by rememberThumb(pick, transfer)
                // A video shows a frame of it; the gallery plays it.
                val large by rememberThumb(pick, transfer, size = if (item.video) 1440 else 320)
                val shape = if (item.width != null && item.height != null && item.height > 0) item.width.toFloat() / item.height else null
                ZoomablePhoto(shape ?: small?.let { it.width.toFloat() / it.height } ?: 1f, onZoom) {
                    (if (item.video) large else small)?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    if (!item.video) AsyncImage(item.uri, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (item.video) Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(48.dp))
            }
            is Pick.Camera -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || transfer == null) return@Box
                val info = pick.photo
                val thumb by rememberThumb(pick, transfer)
                val original = transfer.originalOf(info)
                val file = original?.file
                var shape by remember(pick.key) { mutableFloatStateOf(0f) }
                ZoomablePhoto(shape.takeIf { it > 0f } ?: thumb?.let { it.width.toFloat() / it.height } ?: 1.5f, onZoom) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    file?.let {
                        AsyncImage(it, contentDescription = info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                            onSuccess = { loaded -> loaded.painter.intrinsicSize.let { size -> if (size.height > 0f) shape = size.width / size.height } })
                    }
                }
                if (info.photo && file == null && original != null) Loading(original) { transfer.loadOriginal(info) }
            }
        }
    }
}

