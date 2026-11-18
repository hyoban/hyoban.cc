package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.local.PhotoRef
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import cc.hyoban.moments.ui.components.fullWindowDialogProperties
import cc.hyoban.moments.ui.Album
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalTabsHidden
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.components.DialogPane
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.compose.MAX_PHOTOS
import cc.hyoban.moments.ui.design.FloatingBar
import cc.hyoban.moments.ui.design.ExtendedFloatingActionButton
import androidx.compose.ui.draw.alpha
import cc.hyoban.moments.ui.feed.NewMomentButton
import cc.hyoban.moments.ui.theme.MediaOverlay
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateDialog
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LinearProgressIndicator
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 选照片 (39), over the Moment being written, in place of the system's picker: 相册 in its
 * choosing look, every photo with its circle, at most [limit]. The gallery asks for its
 * permission first; refused, [onFallback] opens the system's documents picker instead. [onAdded]
 * has the photos, the camera's and the NAS's saved to the gallery's Here album first.
 */
@Composable
fun PhotoPickerScreen(container: AppContainer, limit: Int, onClose: () -> Unit, onFallback: () -> Unit, onAdded: (List<PhotoRef>) -> Unit) =
    PhotoBrowser(container, limit, album = null, onClose = onClose, onFallback = onFallback, onAdded = onAdded)

/**
 * 相册 (40), the third tab: the same page looked through, without circles until 选择 or a photo
 * held starts choosing. What is chosen is then written into a new Moment ([onWrite], nine at
 * most), shared, saved to the gallery (the camera's and the NAS's) or deleted, from a bar in
 * place of the tabs. [onNew] is the tab's own button, as the timeline's.
 */
@Composable
fun AlbumScreen(container: AppContainer, onNew: () -> Unit, onWrite: () -> Unit) =
    PhotoBrowser(
        container, Int.MAX_VALUE,
        album = AlbumActions(onNew) { uris -> container.offerPhotos(uris); onWrite() },
        onClose = {}, onFallback = {}, onAdded = {},
    )

/** What 相册 does that 选照片 doesn't: its tab's button, and a new Moment with the photos given. */
private class AlbumActions(val onNew: () -> Unit, val onWrite: (List<PhotoRef>) -> Unit)

/**
 * The gallery, the paired camera and the NAS in one page, one grid and one viewer, switched from
 * the title. What is chosen stays chosen across them, numbered in the order chosen. With [album]
 * it is 相册, else 选照片. Leaving it leaves the camera's network.
 */
@Composable
private fun PhotoBrowser(container: AppContainer, limit: Int, album: AlbumActions?, onClose: () -> Unit, onFallback: () -> Unit, onAdded: (List<PhotoRef>) -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val device = cc.hyoban.moments.ui.LocalDevice.current.picker

    val chosen = remember { mutableStateListOf<Pick>() }
    val order by remember { derivedStateOf { chosen.withIndex().associate { (i, p) -> p.key to i } } }
    val full = chosen.size >= limit
    // 选照片 is always choosing; 相册 from 选择 or a photo held until 不选了.
    var choosingAlbum by remember { mutableStateOf(false) }
    val choosing = album == null || choosingAlbum
    val stopChoosing = { choosingAlbum = false; chosen.clear() }
    val toggle: (Pick) -> Unit = { pick ->
        val at = order[pick.key]
        if (at != null) { haptics.toggle(false); chosen.removeAt(at) }
        else if (!full && pick.choosable) { haptics.toggle(true); chosen.add(pick) }
    }
    // A photo held is chosen, and dragged on, those from it to the finger, on top of what was chosen before.
    var heldOver by remember { mutableStateOf<List<Pick>>(emptyList()) }
    val onHold: (Pick) -> Unit = { pick ->
        choosingAlbum = true
        heldOver = chosen.toList()
        if (order[pick.key] == null && pick.choosable && !full) { haptics.toggle(true); chosen.add(pick) }
    }
    val onHoldOver: (List<Pick>) -> Unit = { range ->
        val before = heldOver
        val keys = before.mapTo(HashSet()) { it.key }
        val more = range.filter { it.choosable && it.key !in keys }.take((limit - before.size).coerceAtLeast(0))
        if (before.size + more.size != chosen.size) haptics.tick()
        chosen.clear()
        chosen.addAll(before + more)
    }

    // The photo open in the viewer, and the one it shows once that is deleted.
    var viewing by remember { mutableStateOf<String?>(null) }
    var afterDelete by remember { mutableStateOf<String?>(null) }
    // What a source deleted leaves the choice, and the viewer if it was open on it.
    val deletion = remember {
        Deletion(scope) { gone ->
            chosen.removeAll { it.key in gone }
            if (viewing in gone) viewing = afterDelete
        }
    }

    // The device's own sources first (the phone's gallery and paired camera), then the NAS; the
    // first source's photos listed first, and gone back to when another is given up on.
    var picked by remember { mutableStateOf<Listing?>(null) }
    val own = device.sources(deletion, onFallback, onRefused = { picked = null })
    val nas = rememberNas(container.nas, deletion)
    val sources = own + nas
    val listing = picked ?: sources.first().home
    // Fetched those that come down first, so the bar counts them as they come.
    val fetched = sources.sortedBy { !it.fetches }
    val videos = device.rememberVideos()
    // The map of what is listed, where its source knows where its photos were taken: then the
    // photos shown, in the grid under it and in the viewer, are the opened heap's.
    var mapped by remember { mutableStateOf(false) }
    val places = listing.places
    val onMap = mapped && places != null
    val shown = if (onMap) places!!.picks() else listing.picks()
    // The calendar over the grid: a day picked takes the grid there, and another source forgets it.
    val jump = remember { DateJump() }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(listing, onMap) { jump.target = null }

    // Fetching what goes on: the gallery's as they are, the camera's and the NAS's downloaded to
    // the gallery's Here album first, in the order given, then [then] with them all.
    var running by remember { mutableStateOf<Job?>(null) }
    var runWords by remember { mutableStateOf(Res.string.picker_adding) }
    var runCount by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var within by remember { mutableFloatStateOf(0f) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun run(picks: List<Pick>, words: StringResource, then: (List<PhotoRef>) -> Unit) {
        retry = null
        runWords = words
        runCount = picks.count { it.source.fetches }
        done = 0
        within = 0f
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
    val add: () -> Unit = { run(chosen.toList(), Res.string.picker_adding, onAdded) }
    val write: (List<Pick>) -> Unit = { picks -> run(picks, Res.string.album_saving) { uris -> album?.onWrite(uris); stopChoosing() } }
    val share: ((List<Pick>) -> Unit)? = device.share?.let { hand -> { picks -> run(picks, Res.string.album_saving) { refs -> hand(refs) } } }
    val save: (List<Pick>) -> Unit = { picks -> run(picks.filter { it.source.fetches }, Res.string.album_saving) { if (picks.size > 1) stopChoosing() } }

    val idle = running == null && deletion.job == null
    val sideways = isPhoneLandscape()
    val count = if (onMap) places?.spots?.size else listing.count(shown)
    val subtitle = listOfNotNull(
        count?.let { stringResource(Res.string.camera_photo_count, it) },
        chosen.size.takeIf { it > 0 }?.let { stringResource(Res.string.picker_chosen_count, it) },
        if (album != null && chosen.size > MAX_PHOTOS) stringResource(Res.string.album_nine) else null,
    ).joinToString(" · ").ifEmpty { null }
    val menu = TitleMenu(stringResource(Res.string.picker_switch)) { close ->
        // 相册's corner keeps to two circles under its title in the middle, so the grid and the map switch here.
        if (album != null && places != null) DropdownMenuItem(
            text = { Text(stringResource(if (onMap) Res.string.picker_show_grid else Res.string.picker_show_map)) },
            leadingIcon = { Icon(if (onMap) AppIcons.Photos else AppIcons.Map, contentDescription = null) },
            onClick = { close(); mapped = !mapped },
            modifier = Modifier.testTag("picker_map_toggle"),
        )
        sources.forEach { source ->
            source.choices().forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    leadingIcon = { Icon(choice.icon, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            choice.count?.let { Text("$it", style = AppTheme.typography.caption, color = tokens.tx3) }
                            if (choice.listing == listing) Icon(AppIcons.Check, contentDescription = null)
                        }
                    },
                    onClick = { close(); picked = choice.listing; choice.listing.opened() },
                    modifier = Modifier.testTag(choice.tag),
                )
            }
        }
    }
    val addButton = @Composable { tag: String, first: () -> Unit ->
        Button(onClick = { first(); add() }, modifier = Modifier.testTag(tag)) { Text(stringResource(Res.string.camera_add, chosen.size)) }
    }
    val canAdd = chosen.isNotEmpty() && idle
    // Delete takes what is chosen from the source listed.
    val listed = listing.source
    val listedChosen = chosen.filter { it.source === listed }

    if (album != null) {
        // The tab's button beside the tabs while looking through; choosing, the bar of what to do takes their place.
        val accessory = LocalNavAccessory.current
        val onNew by rememberUpdatedState(album.onNew)
        val newBeside = remember<@Composable () -> Unit> { { NewMomentButton(onClick = { onNew() }) } }
        DisposableEffect(accessory) {
            accessory.value = newBeside
            onDispose { if (accessory.value === newBeside) accessory.value = null }
        }
        val tabsHidden = LocalTabsHidden.current
        LaunchedEffect(choosing) { tabsHidden.value = choosing }
        DisposableEffect(tabsHidden) { onDispose { tabsHidden.value = false } }
        BackHandler(enabled = choosing) { stopChoosing() }
    }

    val actions: @Composable RowScope.() -> Unit = {
        if (!onMap && shown.isNotEmpty()) IconButton(onClick = { picking = true }, modifier = Modifier.testTag("picker_jump")) {
            Icon(AppIcons.Calendar, contentDescription = stringResource(Res.string.picker_jump_to_date))
        }
        if (album == null && places != null) IconButton(onClick = { mapped = !mapped }, modifier = Modifier.testTag("picker_map_toggle")) {
            Icon(if (onMap) AppIcons.Photos else AppIcons.Map, contentDescription = stringResource(if (onMap) Res.string.picker_show_grid else Res.string.picker_show_map))
        }
        if (album == null) {
            if (listedChosen.isNotEmpty() && idle) IconButton(onClick = { listed.delete(listedChosen, fromViewer = false) }, modifier = Modifier.testTag(listed.deleteTag)) {
                Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.camera_delete_chosen, listedChosen.size))
            }
            if (sideways && canAdd) addButton("picker_add") {}
        } else if (choosing) IconButton(onClick = stopChoosing, modifier = Modifier.testTag("album_select_done")) {
            Icon(AppIcons.Close, contentDescription = stringResource(Res.string.album_select_done))
        } else if (shown.isNotEmpty()) IconButton(onClick = { choosingAlbum = true }, modifier = Modifier.testTag("album_select")) {
            Icon(AppIcons.Check, contentDescription = stringResource(Res.string.album_select))
        }
    }
    val content: @Composable BoxScope.() -> Unit = {
        val holds = if (album != null || choosing) onHold else null
        if (onMap) PlacesMap(places!!, sideways) { onNearEnd ->
            Grid(
                shown, order, full, enabled = idle, sideways = false,
                density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                limited = null, empty = false, onToggle = toggle, onNearEnd = onNearEnd,
                circles = choosing, onHold = holds, onHoldOver = onHoldOver,
            ) { viewing = it.key }
        } else listing.Body(shown) { limited, onNearEnd ->
            Grid(
                shown, order, full, enabled = idle, sideways = sideways,
                density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                limited = limited, onToggle = toggle, onNearEnd = onNearEnd, jump = jump,
                circles = choosing, onHold = holds, onHoldOver = onHoldOver,
            ) { viewing = it.key }
        }
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth()) {
            val fetching = runCount.coerceAtLeast(1)
            if (deletion.job != null) ProgressBar(
                stringResource(Res.string.camera_deleting_count, (deletion.done + 1).coerceAtMost(deletion.total), deletion.total),
                deletion.done.toFloat() / deletion.total.coerceAtLeast(1), null,
            ) { deletion.job?.cancel() }
            else if (retry != null) ProgressBar(stringResource(Res.string.camera_download_failed), null, null, retry = retry) {}
            else if (running != null && runCount > 0) ProgressBar(
                stringResource(runWords, (done + 1).coerceAtMost(fetching), fetching), (done + within) / fetching,
                if (album == null) stringResource(Res.string.picker_add_note) else null,
            ) { running?.cancel() }
            else if (album != null && choosing) ChoosingBar(
                chosen, listedChosen, idle,
                onShare = share?.let { { it(chosen.toList()) } },
                saveWords = device.saveWords,
                onSave = { save(chosen.toList()) },
                onDelete = { listed.delete(listedChosen, fromViewer = false) },
                onWrite = { write(chosen.toList()) },
            )
            else if (album == null && !sideways && canAdd) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.gutter), contentAlignment = Alignment.CenterEnd) {
                addButton("picker_add") {}
            }
        }
    }
    CompositionLocalProvider(LocalPickerVideos provides videos) {
        if (album != null) TabPage(listing.title(), Album, subtitle = subtitle, actions = actions, titleMenu = menu, content = content)
        else SubPage(listing.title(), onClose, subtitle = subtitle, close = true, small = onMap, titleMenu = menu, actions = actions, content = content)
    }

    // The viewer turns through what is read, not the NAS's tiles still waiting for theirs.
    val viewable = remember(shown) { shown.filter { it.openable } }
    viewing?.let { key -> viewable.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }?.let { start ->
        val viewer = @Composable {
            CompositionLocalProvider(LocalPickerVideos provides videos) { Viewer(
                container, viewable, start, order, full, enabled = idle && !deletion.busy, busy = deletion.busy, onToggle = toggle,
                addButton = {
                    if (album == null) { if (canAdd) addButton("picker_viewer_add") { viewing = null } }
                    else if (chosen.isNotEmpty() && idle) Button(
                        onClick = { viewing = null; write(chosen.toList()) }, enabled = chosen.size <= MAX_PHOTOS,
                        modifier = Modifier.testTag("album_viewer_write"),
                    ) { Text(stringResource(Res.string.album_write)) }
                },
                // Looked through, 相册's viewer shares, saves and writes the photo shown rather than choosing it.
                lead = if (album != null && !choosing) ({ pick ->
                    // What comes down first shows how far it is on the page, so the viewer makes way.
                    val leaving = { if (pick.source.fetches) viewing = null }
                    if (share != null) IconButton(onClick = { leaving(); share(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_share")) {
                        Icon(AppIcons.Share, contentDescription = stringResource(Res.string.action_share), tint = MediaOverlay.content)
                    }
                    if (pick.source.fetches) IconButton(onClick = { leaving(); save(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_save")) {
                        Icon(AppIcons.Download, contentDescription = stringResource(device.saveWords), tint = MediaOverlay.content)
                    }
                    Spacer(Modifier.weight(1f))
                    if (pick.choosable) Button(onClick = { viewing = null; write(listOf(pick)) }, enabled = idle, modifier = Modifier.testTag("album_viewer_write")) {
                        Text(stringResource(Res.string.album_write_one))
                    }
                }) else null,
                onDelete = { pick ->
                    val at = viewable.indexOfFirst { it.key == pick.key }
                    afterDelete = (viewable.getOrNull(at + 1) ?: viewable.getOrNull(at - 1))?.key
                    pick.source.delete(listOf(pick), fromViewer = true)
                },
                onClose = { viewing = null },
            ) }
        }
        // Over the tabs, as the picker's own dialog already is.
        if (album != null) Dialog(
            onDismissRequest = { viewing = null },
            properties = fullWindowDialogProperties(),
        ) { DialogPane { viewer() } }
        else viewer()
    }

    if (picking) {
        // The days with photos, as far as they are read, dotted.
        val days = remember(shown) { shown.mapNotNullTo(HashSet()) { it.day } }
        DateDialog(
            jump.showing ?: shown.firstNotNullOfOrNull { it.day } ?: LocalDate.now(),
            stringResource(Res.string.picker_jump_to_date),
            onDismiss = { picking = false },
            marked = days,
        ) { day ->
            picking = false
            jump.target = day
        }
    }

    fetched.forEach { it.Dialogs() }
}

/**
 * 相册's bar while choosing, where the tabs were: share, save to the gallery (once the camera's or
 * the NAS's are chosen) and delete (those chosen from the source shown) in a capsule, and 写时刻
 * beside it, set back past the nine a Moment holds.
 */
@Composable
private fun ChoosingBar(
    chosen: List<Pick>,
    listedChosen: List<Pick>,
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
                IconButton(onClick = onDelete, enabled = idle && listedChosen.isNotEmpty(), modifier = Modifier.testTag("album_delete")) {
                    Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.camera_delete_chosen, listedChosen.size))
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
