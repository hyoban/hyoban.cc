package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
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

/**
 * 选照片 (39), over the Moment being written, in place of the system's picker: the phone's
 * gallery, the paired camera and the NAS in one page, one grid and one viewer, switched from the title.
 * What is chosen stays chosen across them, numbered in the order it goes into the Moment, at
 * most [limit]. The gallery asks for its permission first; refused, [onFallback] opens the
 * system's documents picker instead. [onAdded] has the photos, the camera's and the NAS's
 * saved to the gallery's Here album first. Leaving it leaves the camera's network.
 */
@Composable
fun PhotoPickerScreen(container: PhoneContainer, limit: Int, onClose: () -> Unit, onFallback: () -> Unit, onAdded: (List<Uri>) -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    val chosen = remember { mutableStateListOf<Pick>() }
    val order by remember { derivedStateOf { chosen.withIndex().associate { (i, p) -> p.key to i } } }
    val full = chosen.size >= limit
    val toggle: (Pick) -> Unit = { pick ->
        val at = order[pick.key]
        if (at != null) { haptics.toggle(false); chosen.removeAt(at) }
        else if (!full && pick.choosable) { haptics.toggle(true); chosen.add(pick) }
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

    val gallery = rememberGallery(deletion, onFallback)
    var listing by remember { mutableStateOf<Listing>(gallery.all) }
    val camera = rememberCamera(deletion, onRefused = { listing = gallery.all })
    val nas = rememberNas(container.nas, deletion)
    // In the title's menu in this order; fetched for the Moment in the order of [fetched].
    val sources = listOf(gallery, camera, nas)
    val fetched = listOf(camera, nas, gallery)
    // The map of what is listed, where its source knows where its photos were taken: then the
    // photos shown, in the grid under it and in the viewer, are the opened heap's.
    var mapped by remember { mutableStateOf(false) }
    val places = listing.places
    val onMap = mapped && places != null
    val shown = if (onMap) places!!.picks() else listing.picks()

    // Adding: the gallery's as they are, the camera's and the NAS's downloaded first, in the order chosen.
    var adding by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var within by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    // How many are fetched before they go in.
    val fetching = chosen.count { it.source.fetches }
    val add: () -> Unit = {
        failed = false
        adding = scope.launch {
            try {
                val byKey = mutableMapOf<String, Uri>()
                var before = 0
                fetched.forEach { source ->
                    val picks = chosen.filter { it.source === source }
                    val offset = before
                    byKey += picks.map { it.key }.zip(source.fetch(picks) { index, fraction -> done = offset + index; within = fraction })
                    if (source.fetches) before += picks.size
                }
                onAdded(chosen.mapNotNull { byKey[it.key] })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
            } finally { adding = null }
        }
    }

    val idle = adding == null && deletion.job == null
    val sideways = isPhoneLandscape()
    val count = if (onMap) places?.spots?.size else listing.count(shown)
    val subtitle = listOfNotNull(
        count?.let { stringResource(Res.string.camera_photo_count, it) },
        chosen.size.takeIf { it > 0 }?.let { stringResource(Res.string.picker_chosen_count, it) },
    ).joinToString(" · ").ifEmpty { null }
    val menu = TitleMenu(stringResource(Res.string.picker_switch)) { close ->
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
                    onClick = { close(); listing = choice.listing; choice.listing.opened() },
                    modifier = Modifier.testTag(choice.tag),
                )
            }
        }
    }
    val addButton = @Composable { tag: String, first: () -> Unit ->
        Button(onClick = { first(); add() }, modifier = Modifier.testTag(tag)) { Text(stringResource(Res.string.camera_add, chosen.size)) }
    }
    val canAdd = chosen.isNotEmpty() && idle
    // The bar's delete takes what is chosen from the source listed.
    val listed = listing.source
    val listedChosen = chosen.filter { it.source === listed }

    SubPage(
        listing.title(), onClose, subtitle = subtitle, close = true, small = onMap, titleMenu = menu,
        actions = {
            if (places != null) IconButton(onClick = { mapped = !mapped }, modifier = Modifier.testTag("picker_map_toggle")) {
                Icon(if (onMap) AppIcons.Photos else AppIcons.Map, contentDescription = stringResource(if (onMap) Res.string.picker_show_grid else Res.string.picker_show_map))
            }
            if (listedChosen.isNotEmpty() && idle) IconButton(onClick = { listed.delete(listedChosen, fromViewer = false) }, modifier = Modifier.testTag(listed.deleteTag)) {
                Icon(AppIcons.Trash, contentDescription = stringResource(Res.string.camera_delete_chosen, listedChosen.size))
            }
            if (sideways && canAdd) addButton("picker_add") {}
        },
    ) {
        if (onMap) PlacesMap(places!!, sideways) { onNearEnd ->
            Grid(
                shown, order, full, enabled = idle, sideways = false,
                density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                limited = null, empty = false, onToggle = toggle, onNearEnd = onNearEnd,
            ) { viewing = it.key }
        } else listing.Body(shown) { limited, onNearEnd ->
            Grid(
                shown, order, full, enabled = idle, sideways = sideways,
                density = container.settings.pickerDensity, onDensity = container.settings::changePickerDensity,
                limited = limited, onToggle = toggle, onNearEnd = onNearEnd,
            ) { viewing = it.key }
        }
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth()) {
            if (deletion.job != null) ProgressBar(
                stringResource(Res.string.camera_deleting_count, (deletion.done + 1).coerceAtMost(deletion.total), deletion.total),
                deletion.done.toFloat() / deletion.total.coerceAtLeast(1), null,
            ) { deletion.job?.cancel() }
            else if (adding != null && fetching > 0 || failed) {
                val n = fetching.coerceAtLeast(1)
                if (failed) ProgressBar(stringResource(Res.string.camera_download_failed), null, null, retry = add) {}
                else ProgressBar(stringResource(Res.string.picker_adding, (done + 1).coerceAtMost(n), n), (done + within) / n, stringResource(Res.string.picker_add_note)) { adding?.cancel() }
            } else if (!sideways && canAdd) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.gutter), contentAlignment = Alignment.CenterEnd) {
                addButton("picker_add") {}
            }
        }
    }

    viewing?.let { key -> shown.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }?.let { start ->
        Viewer(
            container, shown, start, order, full, enabled = idle && !deletion.busy, busy = deletion.busy, onToggle = toggle,
            addButton = { if (canAdd) addButton("picker_viewer_add") { viewing = null } },
            onDelete = { pick ->
                val at = shown.indexOfFirst { it.key == pick.key }
                afterDelete = (shown.getOrNull(at + 1) ?: shown.getOrNull(at - 1))?.key
                pick.source.delete(listOf(pick), fromViewer = true)
            },
            onClose = { viewing = null },
        )
    }

    fetched.forEach { it.Dialogs() }
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
