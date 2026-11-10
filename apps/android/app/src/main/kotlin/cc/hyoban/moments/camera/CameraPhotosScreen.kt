package cc.hyoban.moments.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
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
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 从相机 (39), over the Moment being written: connects to the paired camera (its Wi‑Fi on, joined,
 * the card read), shows its photos by day, newest first, to look at and choose; those chosen
 * are saved to the gallery and handed to the Moment ([onAdded]). Leaving it leaves the camera's network.
 */
@Composable
fun CameraPhotosScreen(onClose: () -> Unit, onAdded: (List<Uri>) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { onClose(); return }
    CameraPhotos(onClose, onAdded)
}

/** The photos chosen, in the order they were, and each one's place among them, by name (a delete moves handles). */
private class Choice {
    val photos = mutableStateListOf<Ptp.ObjectInfo>()
    private val order by derivedStateOf { photos.withIndex().associate { (i, p) -> p.name to i } }
    /** Where [info] is among those chosen, or -1. */
    fun index(info: Ptp.ObjectInfo) = order[info.name] ?: -1
    fun toggle(info: Ptp.ObjectInfo) {
        val at = index(info)
        if (at >= 0) photos.removeAt(at) else photos.add(info)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun CameraPhotos(onClose: () -> Unit, onAdded: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val camera by CameraStore.camera.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var allowed by remember { mutableStateOf(CameraLink.transferAllowed(context)) }
    val ask = rememberCameraPermissionAsk(
        emptyArray(),
        listOf(PermissionFact(AppIcons.Wifi, stringResource(R.string.permission_camera_wifi))),
        onDecline = onClose,
    ) { granted -> if (granted) allowed = true else onClose() }
    LaunchedEffect(Unit) { if (!allowed) ask() }

    val transfer = remember(attempt) { CameraTransfer(context.applicationContext) }
    DisposableEffect(transfer) { onDispose { transfer.close() } }
    LaunchedEffect(transfer, allowed) { if (allowed) transfer.connect() }
    val state by transfer.state.collectAsStateWithLifecycle()

    val chosen = remember(transfer) { Choice() }
    var downloading by remember(transfer) { mutableStateOf<Job?>(null) }
    var done by remember(transfer) { mutableIntStateOf(0) }
    var within by remember(transfer) { mutableFloatStateOf(0f) }
    var failed by remember(transfer) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = state as? CameraTransfer.State.Ready
    val subtitle = listOfNotNull(camera?.name, ready?.let { stringResource(R.string.camera_photo_count, it.total) }).joinToString(" · ")
    val sideways = isPhoneLandscape()
    // The photo looked at full size, by name: more may still be listed ahead of it, and a delete moves handles.
    var viewing by remember(transfer) { mutableStateOf<String?>(null) }

    val add: () -> Unit = {
        failed = false
        downloading = scope.launch {
            try {
                onAdded(transfer.download(chosen.photos.toList()) { index, fraction -> done = index; within = fraction })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
            } finally { downloading = null }
        }
    }
    val canAdd = chosen.photos.isNotEmpty() && downloading == null
    val addButton = @Composable { tag: String, first: () -> Unit ->
        Button(onClick = { first(); add() }, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.camera_add, chosen.photos.size)) }
    }

    SubPage(
        stringResource(R.string.camera_photos_title), onClose, subtitle = subtitle.ifEmpty { null }, close = true,
        // On its side the button goes up into the bar (H39).
        actions = { if (sideways && canAdd) addButton("camera_add") {} },
    ) {
        when (val s = state) {
            is CameraTransfer.State.Connecting -> Steps(s.step)
            is CameraTransfer.State.Failed -> Failed(s.reason) { attempt++ }
            is CameraTransfer.State.Ready -> Grid(transfer, s, chosen, enabled = downloading == null, columns = if (sideways) 8 else 4,
                onOpen = { viewing = it.name })
        }
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth()) {
            if (downloading != null || failed) Surface(Modifier.fillMaxWidth(), color = tokens.surface) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    val count = chosen.photos.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (failed) stringResource(R.string.camera_download_failed)
                            else stringResource(R.string.camera_downloading, (done + 1).coerceAtMost(count), count),
                            style = AppTheme.typography.body, modifier = Modifier.weight(1f),
                        )
                        if (failed) TextButton(onClick = add) { Text(stringResource(R.string.action_retry)) }
                        else TextButton(onClick = { downloading?.cancel() }, modifier = Modifier.testTag("camera_stop")) { Text(stringResource(R.string.camera_stop)) }
                    }
                    if (!failed) {
                        LinearProgressIndicator({ (done + within) / count.coerceAtLeast(1) }, color = tokens.tx)
                        Text(stringResource(R.string.camera_download_note), style = AppTheme.typography.caption, color = tokens.tx2)
                    }
                }
            } else if (!sideways && canAdd) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.gutter), contentAlignment = Alignment.CenterEnd) {
                addButton("camera_add") {}
            }
        }
    }
    val photos = ready?.photos.orEmpty()
    // The photo asked about deleting, and whether its delete is under way.
    var deleting by remember(transfer) { mutableStateOf<Ptp.ObjectInfo?>(null) }
    var busy by remember(transfer) { mutableStateOf(false) }
    viewing?.let { name -> photos.indexOfFirst { it.name == name } }?.takeIf { it >= 0 }?.let { start ->
        Viewer(transfer, photos, start, chosen, enabled = downloading == null && !busy, busy = busy,
            // Added from there, it closes so the download shows.
            addButton = { if (canAdd) addButton("camera_viewer_add") { viewing = null } },
            onDelete = { deleting = it }, onClose = { viewing = null })
    }
    deleting?.let { info ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.camera_delete_title)) },
            text = { Text(stringResource(R.string.camera_delete_body, info.name)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    busy = true
                    // What the viewer turns to once it's gone: the next, else the one before.
                    val at = photos.indexOfFirst { it.name == info.name }
                    val next = (photos.getOrNull(at + 1) ?: photos.getOrNull(at - 1))?.name
                    scope.launch {
                        val gone = transfer.delete(info)
                        busy = false
                        if (gone) {
                            chosen.photos.removeAll { it.name == info.name }
                            viewing = next
                        } else Toast.makeText(context, R.string.camera_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.testTag("camera_delete_confirm")) { Text(stringResource(R.string.camera_delete), color = tokens.danger) }
            },
        )
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

/** [info]'s thumbnail once the camera has sent it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberThumb(transfer: CameraTransfer, info: Ptp.ObjectInfo): State<Bitmap?> =
    produceState(null, info.name) { value = transfer.thumbnail(info) }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Grid(transfer: CameraTransfer, state: CameraTransfer.State.Ready, chosen: Choice, enabled: Boolean, columns: Int, onOpen: (Ptp.ObjectInfo) -> Unit) {
    val haptics = rememberHaptics()
    val today = remember { LocalDate.now() }
    // By the day each was taken, newest first.
    val days = remember(state.photos) { state.photos.groupBy { it.captured?.toLocalDate() }.toList() }
    LazyVerticalGrid(
        GridCells.Fixed(columns),
        Modifier.fillMaxSize().testTag("camera_grid"),
        contentPadding = PaddingValues(top = LocalTopClearance.current, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        days.forEach { (day, photos) ->
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (day) { null -> stringResource(R.string.camera_no_date); today -> stringResource(R.string.camera_today); else -> formatDay(day.toString(), today) },
                    style = AppTheme.typography.caption, color = tokens.tx2,
                    modifier = Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
                )
            }
            items(photos, key = { it.handle }) { info ->
                val index = chosen.index(info)
                val thumb by rememberThumb(transfer, info)
                val label = stringResource(if (info.raw) R.string.camera_raw_description else R.string.camera_photo_description, info.name)
                Box(
                    Modifier.aspectRatio(1f).background(tokens.fill)
                        .then(if (index >= 0) Modifier.border(3.dp, tokens.accent) else Modifier)
                        .semantics { contentDescription = label; selected = index >= 0 }
                        .clickable(onClickLabel = stringResource(R.string.camera_view)) { onOpen(info) },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (info.raw) Text("RAW", style = AppTheme.typography.caption.strong, color = MediaOverlay.content,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    // The circle chooses it, its target the corner's 48dp.
                    else Box(
                        Modifier.align(Alignment.TopEnd).size(48.dp)
                            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = stringResource(R.string.camera_choose)) {
                                haptics.toggle(index < 0)
                                chosen.toggle(info)
                            }
                            .testTag("camera_choose_${info.handle}"),
                        contentAlignment = Alignment.TopEnd,
                    ) { ChoiceMark(index, Modifier.padding(6.dp).size(22.dp)) }
                }
            }
        }
        if (state.listing) item(key = "listing", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) { Spinner() }
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

/**
 * The photos full size over 从相机, black as the photo viewer (03): swiped through, each its
 * thumbnail enlarged until its original is down in the cache, then the original; chosen from
 * here too, and added.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Viewer(
    transfer: CameraTransfer,
    photos: List<Ptp.ObjectInfo>,
    start: Int,
    chosen: Choice,
    enabled: Boolean,
    busy: Boolean,
    addButton: @Composable () -> Unit,
    onDelete: (Ptp.ObjectInfo) -> Unit,
    onClose: () -> Unit,
) {
    val haptics = rememberHaptics()
    val pager = rememberPagerState(initialPage = start) { photos.size }
    BackHandler(onBack = onClose)
    // Light icons over the black while it is open, on the dialog's own window 从相机 is in.
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_viewer")) {
        // Zoomed in, a swipe moves the photo rather than turning the page.
        var zoomed by remember { mutableStateOf(false) }
        // The original of the photo shown, once the pager is still, so swiping past doesn't queue
        // downloads; by name, so a delete that shortens the list asks for the one now shown.
        val shown = photos.getOrNull(pager.currentPage)
        LaunchedEffect(shown?.name, pager.isScrollInProgress) {
            if (!pager.isScrollInProgress && shown != null && shown.photo) transfer.loadOriginal(shown)
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0, userScrollEnabled = !zoomed, key = { photos[it].handle }) { page ->
            val info = photos[page]
            val thumb by rememberThumb(transfer, info)
            val settled = pager.currentPage == page
            val original = transfer.originalOf(info)
            val file = original?.file
            var shape by remember(info.handle) { mutableFloatStateOf(0f) }
            ZoomablePhoto(
                shape.takeIf { it > 0f } ?: thumb?.let { it.width.toFloat() / it.height } ?: 1.5f,
                onZoom = { if (settled) zoomed = it },
            ) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                file?.let {
                    AsyncImage(it, contentDescription = info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        onSuccess = { loaded -> loaded.painter.intrinsicSize.let { size -> if (size.height > 0f) shape = size.width / size.height } })
                }
            }
            if (info.photo && file == null && original != null) Loading(original) { transfer.loadOriginal(info) }
        }
        val current = photos.getOrNull(pager.currentPage)
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("camera_viewer_close")) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), tint = MediaOverlay.content)
            }
            Text("${pager.currentPage + 1} / ${photos.size}", style = AppTheme.typography.body, color = MediaOverlay.content,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { current?.let(onDelete) }, enabled = enabled && current != null, modifier = Modifier.testTag("camera_viewer_delete")) {
                Icon(AppIcons.Trash, contentDescription = stringResource(R.string.camera_delete), tint = MediaOverlay.content)
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
        if (current != null) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                listOfNotNull(current.captured?.toString()?.let { "${formatDay(it)} ${formatTime(it)}" }, current.name).joinToString(" · "),
                style = AppTheme.typography.caption, color = MediaOverlay.content,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val index = chosen.index(current)
                if (current.photo) Row(
                    Modifier.weight(1f).height(48.dp)
                        .clickable(enabled = enabled, role = Role.Checkbox) {
                            haptics.toggle(index < 0)
                            chosen.toggle(current)
                        }
                        .semantics { selected = index >= 0 }
                        .testTag("camera_viewer_choose"),
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
