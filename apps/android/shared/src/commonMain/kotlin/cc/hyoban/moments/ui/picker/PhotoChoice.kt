package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalTabsHidden
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.components.DialogPane
import cc.hyoban.moments.ui.components.fullWindowDialogProperties
import cc.hyoban.moments.ui.compose.MAX_PHOTOS
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ExtendedFloatingActionButton
import cc.hyoban.moments.ui.design.FloatingBar
import cc.hyoban.moments.ui.design.Haptics
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LinearProgressIndicator
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What is chosen of the photos shown and what is done with it, wherever they are shown: 相册's and
 * 选照片's page, and a heap's photos on 地图. Chosen in order, at most [limit]; [alwaysChoosing]
 * (选照片) has every photo's circle out, else choosing starts from 选择 or a photo held and ends at
 * 不选了. With [onWrite] what is chosen goes into a new Moment, is shared, saved or deleted from a
 * bar in place of the tabs ([Foot]); without, the page adds it itself through [run].
 */
@Stable
class PhotoChoice internal constructor(
    private val scope: CoroutineScope,
    private val haptics: Haptics,
    val limit: Int,
    val alwaysChoosing: Boolean,
    val device: PickerDevice,
    val onWrite: ((List<PhotoRef>) -> Unit)?,
) {
    val chosen = mutableStateListOf<Pick>()
    val order by derivedStateOf { chosen.withIndex().associate { (i, p) -> p.key to i } }
    val full get() = chosen.size >= limit

    private var started by mutableStateOf(false)
    val choosing get() = alwaysChoosing || started

    fun start() { started = true }

    fun stop() {
        started = false
        chosen.clear()
    }

    fun toggle(pick: Pick) {
        val at = order[pick.key]
        if (at != null) { haptics.toggle(false); chosen.removeAt(at) }
        else if (!full && pick.choosable) { haptics.toggle(true); chosen.add(pick) }
    }

    // A photo held is chosen, and dragged on, those from it to the finger, on top of what was chosen before.
    private var heldOver: List<Pick> = emptyList()

    fun hold(pick: Pick) {
        if (!pick.choosable) return
        started = true
        heldOver = chosen.toList()
        if (order[pick.key] == null && !full) { haptics.toggle(true); chosen.add(pick) }
    }

    fun holdOver(range: List<Pick>) {
        val before = heldOver
        val keys = before.mapTo(HashSet()) { it.key }
        val more = range.filter { it.choosable && it.key !in keys }.take((limit - before.size).coerceAtLeast(0))
        if (before.size + more.size != chosen.size) haptics.tick()
        chosen.clear()
        chosen.addAll(before + more)
    }

    /** The photo open in the viewer, and the one it shows once that is deleted. */
    var viewing by mutableStateOf<String?>(null)
    var afterDelete by mutableStateOf<String?>(null)

    /** What a source deleted leaves the choice, and the viewer if it was open on it. */
    val deletion = Deletion(scope) { gone ->
        chosen.removeAll { it.key in gone }
        if (viewing in gone) viewing = afterDelete
    }

    /** The sources the photos come from, as the page has them now, for fetching in order. */
    var sources: List<PhotoSource> = emptyList()

    // Fetching what goes on: the gallery's as they are, the camera's and the NAS's downloaded and
    // kept on the device first, in the order given, then on with them all.
    var running by mutableStateOf<Job?>(null)
        private set
    var runWords by mutableStateOf(Res.string.picker_adding)
        private set
    var runCount by mutableIntStateOf(0)
        private set
    var done by mutableIntStateOf(0)
        private set
    var within by mutableFloatStateOf(0f)
        private set
    var retry by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun run(picks: List<Pick>, words: StringResource, then: (List<PhotoRef>) -> Unit) {
        retry = null
        runWords = words
        runCount = picks.count { it.source.fetches }
        done = 0
        within = 0f
        val fetched = (sources + picks.map { it.source }).distinct().sortedBy { !it.fetches }
        running = scope.launch {
            try {
                val byKey = mutableMapOf<String, PhotoRef>()
                var before = 0
                fetched.forEach { source ->
                    val these = picks.filter { it.source === source }
                    if (these.isEmpty()) return@forEach
                    val offset = before
                    byKey += these.map { it.key }.zip(source.fetch(these) { index, fraction -> done = offset + index; within = fraction })
                    if (source.fetches) before += these.size
                }
                then(picks.mapNotNull { byKey[it.key] })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retry = { run(picks, words, then) }
            } finally { running = null }
        }
    }

    val idle get() = running == null && deletion.job == null

    fun write(picks: List<Pick>) = run(picks, Res.string.album_saving) { refs -> onWrite?.invoke(refs); stop() }

    val share: ((List<Pick>) -> Unit)? = device.share?.let { hand -> { picks -> run(picks, Res.string.album_saving) { refs -> hand(refs) } } }

    fun save(picks: List<Pick>) = run(picks.filter { it.source.fetches }, Res.string.album_saving) { if (picks.size > 1) stop() }

    /** Keeps the NAS's original of a published photo linked to it, as the NAS's own are kept. */
    fun saveNasOriginal(pick: Pick) {
        val link = pick.nasLink ?: return
        val nas = sources.filterIsInstance<NasSource>().firstOrNull() ?: return
        save(listOf(NasPick(nas.nas.photo(link), nas)))
    }

    /** Shares a published photo as the Moment's viewer does. */
    fun shareOwn(pick: Pick) {
        scope.launch { pick.shareOwn() }
    }
}

@Composable
fun rememberPhotoChoice(limit: Int, alwaysChoosing: Boolean, onWrite: ((List<PhotoRef>) -> Unit)?): PhotoChoice {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val device = cc.hyoban.moments.ui.LocalDevice.current.picker
    return remember { PhotoChoice(scope, haptics, limit, alwaysChoosing, device, onWrite) }
}

/** While choosing, the tabs give way to the bar of what to do, and back lets the choice go first. */
@Composable
fun PhotoChoice.TabsGiveWay() {
    val tabsHidden = LocalTabsHidden.current
    LaunchedEffect(choosing) { tabsHidden.value = choosing }
    DisposableEffect(tabsHidden) { onDispose { tabsHidden.value = false } }
    BackHandler(enabled = choosing && !alwaysChoosing) { stop() }
}

/**
 * The foot of where the photos are: how far a delete or a fetch is, with stopping it, or why it
 * failed; else, choosing, the bar of what to do with those chosen (shares, saves, deletes those of
 * [deletable]'s source, writes); else [rest].
 */
@Composable
fun PhotoChoice.Foot(deletable: PhotoSource?, note: String? = null, rest: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        val fetching = runCount.coerceAtLeast(1)
        val retry = retry
        when {
            deletion.job != null -> ProgressBar(
                stringResource(Res.string.camera_deleting_count, (deletion.done + 1).coerceAtMost(deletion.total), deletion.total),
                deletion.done.toFloat() / deletion.total.coerceAtLeast(1), null,
            ) { deletion.job?.cancel() }
            retry != null -> ProgressBar(stringResource(Res.string.camera_download_failed), null, null, retry = retry) {}
            running != null && runCount > 0 -> ProgressBar(
                stringResource(runWords, (done + 1).coerceAtMost(fetching), fetching), (done + within) / fetching, note,
            ) { running?.cancel() }
            onWrite != null && choosing -> {
                val deletes = chosen.filter { it.source === deletable && it.deletable }
                ChoosingBar(
                    chosen, deletes, idle,
                    onShare = share?.let { { it(chosen.toList()) } },
                    saveWords = device.saveWords,
                    onSave = { save(chosen.toList()) },
                    onDelete = { deletable?.delete(deletes, fromViewer = false) },
                    onWrite = { write(chosen.toList()) },
                )
            }
            else -> rest()
        }
    }
}

/**
 * The viewer over [shown] once a photo is opened, turning through those read. Looked through (not
 * choosing, with [onWrite]), its foot shares, saves and writes the photo shown, or for a published
 * one shares it and opens its Moment ([onMoment]); choosing, it chooses, with [addButton] beside.
 * [overTabs] draws it in a window of its own over the tabs.
 */
@Composable
fun PhotoChoice.ViewerOver(
    container: AppContainer,
    shown: List<Pick>,
    videos: PickerVideos,
    overTabs: Boolean,
    onMoment: ((String) -> Unit)? = null,
    addButton: @Composable () -> Unit = {},
) {
    val viewable = remember(shown) { shown.filter { it.openable } }
    val start = viewing?.let { key -> viewable.indexOfFirst { it.key == key } }?.takeIf { it >= 0 } ?: return
    val viewer = @Composable {
        CompositionLocalProvider(LocalPickerVideos provides videos) {
            Viewer(
                container, viewable, start, order, full, enabled = idle && !deletion.busy, busy = deletion.busy, onToggle = ::toggle,
                addButton = {
                    if (onWrite == null) addButton()
                    else if (chosen.isNotEmpty() && idle) Button(
                        onClick = { viewing = null; write(chosen.toList()) }, enabled = chosen.size <= MAX_PHOTOS,
                        modifier = Modifier.testTag("album_viewer_write"),
                    ) { Text(stringResource(Res.string.album_write)) }
                },
                lead = if (onWrite != null && !choosing) ({ pick ->
                    val moment = pick.moment
                    if (moment != null) {
                        IconButton(onClick = { shareOwn(pick) }, modifier = Modifier.testTag("album_viewer_share")) {
                            Icon(AppIcons.Share, contentDescription = stringResource(Res.string.action_share), tint = MediaOverlay.content)
                        }
                        if (pick.nasLink != null) IconButton(
                            onClick = { viewing = null; saveNasOriginal(pick) }, enabled = idle, modifier = Modifier.testTag("album_viewer_nas_original"),
                        ) {
                            Icon(AppIcons.Nas, contentDescription = stringResource(Res.string.album_save_nas_original), tint = MediaOverlay.content)
                        }
                        Spacer(Modifier.weight(1f))
                        if (onMoment != null) Button(onClick = { viewing = null; onMoment(moment) }, modifier = Modifier.testTag("album_viewer_moment")) {
                            Text(stringResource(Res.string.album_open_moment))
                        }
                    } else {
                        // What comes down first shows how far it is on the page, so the viewer makes way.
                        val leaving = { if (pick.source.fetches) viewing = null }
                        val share = share
                        if (share != null) IconButton(onClick = { leaving(); share(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_share")) {
                            Icon(AppIcons.Share, contentDescription = stringResource(Res.string.action_share), tint = MediaOverlay.content)
                        }
                        if (pick.source.fetches) IconButton(onClick = { leaving(); save(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_save")) {
                            Icon(AppIcons.Download, contentDescription = stringResource(device.saveWords), tint = MediaOverlay.content)
                        }
                        val linked = pick.linkedMoment
                        if (linked != null && onMoment != null) IconButton(onClick = { viewing = null; onMoment(linked) }, modifier = Modifier.testTag("album_viewer_linked_moment")) {
                            Icon(AppIcons.Cloud, contentDescription = stringResource(Res.string.album_open_moment), tint = MediaOverlay.content)
                        }
                        Spacer(Modifier.weight(1f))
                        if (pick.choosable) Button(onClick = { viewing = null; write(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_write")) {
                            Text(stringResource(Res.string.album_write_one))
                        }
                    }
                }) else null,
                onDelete = { pick ->
                    val at = viewable.indexOfFirst { it.key == pick.key }
                    afterDelete = (viewable.getOrNull(at + 1) ?: viewable.getOrNull(at - 1))?.key
                    pick.source.delete(listOf(pick), fromViewer = true)
                },
                onClose = { viewing = null },
            )
        }
    }
    if (overTabs) Dialog(onDismissRequest = { viewing = null }, properties = fullWindowDialogProperties()) { DialogPane { viewer() } }
    else viewer()
}

/**
 * 相册's bar while choosing, where the tabs were: share, save to the device (once the camera's or
 * the NAS's are chosen) and delete ([deletes], those chosen from the source shown) in a capsule,
 * and 写时刻 beside it, set back past the nine a Moment holds.
 */
@Composable
private fun ChoosingBar(
    chosen: List<Pick>,
    deletes: List<Pick>,
    idle: Boolean,
    onShare: (() -> Unit)?,
    saveWords: StringResource,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onWrite: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Space.s), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FloatingBar {
                if (onShare != null) IconButton(onClick = onShare, enabled = idle && chosen.isNotEmpty(), modifier = Modifier.testTag("album_share")) {
                    Icon(AppIcons.Share, contentDescription = stringResource(Res.string.action_share))
                }
                if (chosen.any { it.source.fetches }) IconButton(onClick = onSave, enabled = idle, modifier = Modifier.testTag("album_save")) {
                    Icon(AppIcons.Download, contentDescription = stringResource(saveWords))
                }
                IconButton(onClick = onDelete, enabled = idle && deletes.isNotEmpty(), modifier = Modifier.testTag("album_delete")) {
                    Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.camera_delete_chosen, deletes.size))
                }
            }
            Spacer(Modifier.width(Space.m))
            // The tab's ink button grown to hold its words, where 记录 stood; set back while it can't write.
            val canWrite = idle && chosen.isNotEmpty() && chosen.size <= MAX_PHOTOS
            ExtendedFloatingActionButton(
                text = { Text(stringResource(Res.string.album_write)) },
                icon = { Icon(AppIcons.Plus, contentDescription = null) },
                onClick = { if (canWrite) onWrite() },
                modifier = Modifier.alpha(if (canWrite) 1f else 0.38f).testTag("album_write"),
            )
        }
    }
}

/** The bar at the foot of the page while something goes on: what, how far, and stopping it; or failed, to try again. */
@Composable
private fun ProgressBar(words: String, fraction: Float?, note: String?, retry: (() -> Unit)? = null, onStop: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = tokens.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(words, style = AppTheme.typography.body, modifier = Modifier.weight(1f))
                if (retry != null) TextButton(onClick = retry) { Text(stringResource(Res.string.action_retry)) }
                else TextButton(onClick = onStop, modifier = Modifier.testTag("picker_stop")) { Text(stringResource(Res.string.camera_stop)) }
            }
            if (fraction != null) LinearProgressIndicator({ fraction }, color = tokens.tx)
            note?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2) }
        }
    }
}
