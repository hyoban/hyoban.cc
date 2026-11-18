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
 * place of the tabs. [onNew] is the tab's own button, as the timeline's; a published photo opens
 * its Moment with [onMoment].
 */
@Composable
fun AlbumScreen(container: AppContainer, onNew: () -> Unit, onWrite: () -> Unit, onMoment: (String) -> Unit, onEdit: (String) -> Unit = onMoment) =
    PhotoBrowser(
        container, Int.MAX_VALUE,
        album = AlbumActions(onNew, onMoment, onEdit) { uris -> container.offerPhotos(uris); onWrite() },
        onClose = {}, onFallback = {}, onAdded = {},
    )

/** What 相册 does that 选照片 doesn't: its tab's button, a published photo's Moment and its editor, and a new Moment with the photos given. */
private class AlbumActions(val onNew: () -> Unit, val onMoment: (String) -> Unit, val onEdit: (String) -> Unit, val onWrite: (List<PhotoRef>) -> Unit)

/**
 * The gallery, the paired camera, the published photos (相册's alone) and the NAS in one page, one
 * grid and one viewer, switched from the title. What is chosen stays chosen across them, numbered
 * in the order chosen. With [album] it is 相册, else 选照片. Leaving it leaves the camera's network.
 */
@Composable
private fun PhotoBrowser(container: AppContainer, limit: Int, album: AlbumActions?, onClose: () -> Unit, onFallback: () -> Unit, onAdded: (List<PhotoRef>) -> Unit) {
    val choice = rememberPhotoChoice(limit, alwaysChoosing = album == null, onWrite = album?.onWrite)
    val device = choice.device

    // The device's own sources (the phone's gallery and paired camera), the published photos after
    // the gallery, then the NAS; the first listed first, and gone back to when another is given up on.
    var picked by remember { mutableStateOf<Listing?>(null) }
    val own = device.sources(choice.deletion, onFallback, onRefused = { picked = null })
    val nas = rememberNas(container, choice.deletion)
    val published = if (album != null) rememberPublished(container) else null
    val sources = when {
        own.isEmpty() -> listOfNotNull(nas, published)
        else -> listOfNotNull(own.first(), published) + own.drop(1) + nas
    }
    choice.sources = sources
    val listing = picked ?: sources.first().home
    val videos = device.rememberVideos()
    // The filter, one for 相册 and 选照片, kept on the phone; each listing narrowed by what it can tell.
    val filter = remember(container.settings.photoFilter) { PhotoFilter.decode(container.settings.photoFilter) }
    val setFilter: (PhotoFilter) -> Unit = { container.settings.changePhotoFilter(it.encode()) }
    val facets = listing.facets
    val filtered = remember(filter, facets) { filter.within(facets) }
    var filtering by remember { mutableStateOf(false) }
    val shown = listing.shown(filtered)
    // The calendar over the grid: a day picked takes the grid there, and another source forgets it.
    val jump = remember { DateJump() }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(listing) { jump.target = null }

    val add: () -> Unit = { choice.run(choice.chosen.toList(), Res.string.picker_adding, onAdded) }
    val chosen = choice.chosen
    val idle = choice.idle
    val choosing = choice.choosing
    val sideways = isPhoneLandscape()
    // Filtering, the count is always said, as its button stands beside it.
    val count = listing.count(shown) ?: shown.size.takeIf { filtered.active }
    val subtitle = listOfNotNull(
        count?.let { stringResource(Res.string.camera_photo_count, it) },
        chosen.size.takeIf { it > 0 }?.let { stringResource(Res.string.picker_chosen_count, it) },
        if (album != null && chosen.size > MAX_PHOTOS) stringResource(Res.string.album_nine) else null,
    ).joinToString(" · ").ifEmpty { null }
    val menu = TitleMenu(stringResource(Res.string.picker_switch)) { close ->
        sources.forEach { source ->
            source.choices().forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            item.count?.let { Text("$it", style = AppTheme.typography.caption, color = tokens.tx3) }
                            if (item.listing == listing) Icon(AppIcons.Check, contentDescription = null)
                        }
                    },
                    onClick = { close(); picked = item.listing; item.listing.opened() },
                    modifier = Modifier.testTag(item.tag),
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
        choice.TabsGiveWay()
    }

    val actions: @Composable RowScope.() -> Unit = {
        if (shown.isNotEmpty()) IconButton(onClick = { picking = true }, modifier = Modifier.testTag("picker_jump")) {
            Icon(AppIcons.Calendar, contentDescription = stringResource(Res.string.picker_jump_to_date))
        }
        if (album == null) {
            if (listedChosen.isNotEmpty() && idle) IconButton(onClick = { listed.delete(listedChosen, fromViewer = false) }, modifier = Modifier.testTag(listed.deleteTag)) {
                Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.camera_delete_chosen, listedChosen.size))
            }
            if (sideways && canAdd) addButton("picker_add") {}
        } else if (choosing) IconButton(onClick = choice::stop, modifier = Modifier.testTag("album_select_done")) {
            Icon(AppIcons.Close, contentDescription = stringResource(Res.string.album_select_done))
        } else if (shown.any { it.choosable }) IconButton(onClick = choice::start, modifier = Modifier.testTag("album_select")) {
            Icon(AppIcons.Check, contentDescription = stringResource(Res.string.album_select))
        }
    }
    val content: @Composable BoxScope.() -> Unit = {
        listing.Body(shown) { limited, onNearEnd ->
            // The conditions chosen head the grid, under the gallery's limited access where it is.
            val head: (@Composable () -> Unit)? = if (limited == null && !filtered.active) null else ({
                Column {
                    limited?.invoke()
                    FilterConditions(filter, facets, setFilter)
                }
            })
            Grid(
                shown, choice.order, choice.full, enabled = idle, sideways = sideways,
                density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                limited = head, onToggle = choice::toggle, onNearEnd = onNearEnd, jump = jump,
                emptyContent = if (filtered.active) ({ FilterNone { setFilter(PhotoFilter.NONE) } }) else null,
                circles = choosing, onHold = choice::hold, onHoldOver = choice::holdOver,
            ) { choice.viewing = it.key }
        }
        Box(Modifier.align(Alignment.BottomEnd).fillMaxWidth()) {
            choice.Foot(listed, note = if (album == null) stringResource(Res.string.picker_add_note) else null) {
                if (album == null && !sideways && canAdd) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.gutter), contentAlignment = Alignment.CenterEnd) {
                    addButton("picker_add") {}
                }
            }
        }
    }
    // Beside the count, which it changes; the bar's circles keep to their two.
    val filterButton: @Composable () -> Unit = { FilterButton(filtered.active) { filtering = true } }
    CompositionLocalProvider(LocalPickerVideos provides videos) {
        if (album != null) TabPage(listing.title(), Album, subtitle = subtitle, actions = actions, titleMenu = menu, subtitleAction = filterButton, content = content)
        else SubPage(listing.title(), onClose, subtitle = subtitle, close = true, titleMenu = menu, actions = actions, subtitleAction = filterButton, content = content)
    }

    if (filtering) FilterSheet(
        filter, facets,
        days = listing.days(filtered),
        exif = if (Facet.EXIF in facets) listing.exifOptions(filtered) else null,
        onChange = setFilter,
        onDismiss = { filtering = false },
    )

    choice.ViewerOver(container, shown, videos, overTabs = album != null, onMoment = album?.onMoment, onEdit = album?.onEdit) {
        if (canAdd) addButton("picker_viewer_add") { choice.viewing = null }
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

    sources.forEach { it.Dialogs() }
}
