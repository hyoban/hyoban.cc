package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.tokens
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.DarkDialogStatusBar
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.viewer.PhotoInfoPanel
import cc.hyoban.moments.ui.viewer.ViewerFrame
import androidx.compose.ui.text.style.TextOverflow

/**
 * 选照片's photos in the viewer ([ViewerFrame]), each page as its source draws it: chosen, added
 * and deleted from here too, those chosen dotted in the filmstrip. Its own dialog's status bar
 * keeps light icons over the black while it is open.
 */
@Composable
fun Viewer(
    container: AppContainer,
    shown: List<Pick>,
    start: Int,
    order: Map<String, Int>,
    full: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Pick) -> Unit,
    addButton: @Composable () -> Unit,
    /** What the foot holds in place of choosing and [addButton]: 相册's, looked through, for the photo shown. */
    lead: (@Composable RowScope.(Pick) -> Unit)? = null,
    /** The icons for the one shown, beside its info as a Moment's viewer has them. */
    actions: (@Composable RowScope.(Pick) -> Unit)? = null,
    onDelete: (Pick) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { shown.size }
    // Light icons over the black while it is open, on the dialog's own window the picker is in.
    DarkDialogStatusBar()
    val current = shown.getOrNull(pager.currentPage)
    // Its own for each photo, so turning to one starts from what is known of it, never the last one's.
    val details = current?.let { pick -> androidx.compose.runtime.key(pick.key) { pick.rememberDetails() } }
    // The videos, each its page's, played by the device; the controls under the pages work the one shown.
    val videos = LocalPickerVideos.current
    // The live photos, each its page's motion as a Moment's has, and where each one's clip is read once known.
    val device = cc.hyoban.moments.ui.LocalDevice.current
    val motions = remember { mutableMapOf<String, cc.hyoban.moments.ui.viewer.ViewerMotion?>() }
    val clips = remember { androidx.compose.runtime.mutableStateMapOf<String, cc.hyoban.moments.ui.viewer.MotionSource>() }
    val liveOf: (Pick) -> PageLive? = { pick ->
        clips[pick.key]?.let { source -> motions.getOrPut(pick.key) { device.viewerMotion(cc.hyoban.moments.data.spec.MediaType.LIVE_PHOTO) }?.let { PageLive(it, source) } }
    }
    LaunchedEffect(current?.key, pager.isScrollInProgress) {
        // Once it rests there a moment, so the row of small ones dragged past it asks nothing of it.
        if (!pager.isScrollInProgress) {
            delay(cc.hyoban.moments.ui.viewer.AHEAD_AFTER)
            // Those either side, as a Moment's viewer does.
            listOfNotNull(shown.getOrNull(pager.currentPage + 1), shown.getOrNull(pager.currentPage - 1)).forEach { current?.settled(it) }
        }
    }
    ViewerFrame(
        pager = pager,
        key = { shown[it].key },
        onClose = onClose,
        modifier = Modifier.testTag("picker_viewer"),
        thumb = { index ->
            val thumb by rememberThumb(shown[index])
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        },
        thumbLabel = { shown[it].description() },
        marked = { shown[it].key in order },
        info = { modifier -> PhotoInfoPanel(container, details, modifier, note = current?.infoNote(details)?.let { stringResource(it) }) },
        onSpace = { current?.let(liveOf)?.motion?.toggle() },
        top = { at ->
            androidx.compose.foundation.layout.Row(Modifier.align(at), verticalAlignment = Alignment.CenterVertically) {
                if (current?.deletable != false) IconButton(onClick = { current?.let(onDelete) }, enabled = enabled && current != null, modifier = Modifier.testTag("picker_viewer_delete")) {
                    Icon(AppIcons.Trash, contentDescription = stringResource(current?.source?.deleteWords ?: Res.string.camera_delete), tint = MediaOverlay.content)
                }
                current?.let(liveOf)?.motion?.LiveButton(Modifier)
            }
        },
        controls = { if (current != null) videos.Controls(current.key, Modifier.padding(horizontal = Space.xs)) },
        actions = { if (current != null && actions != null) actions(current) },
        caption = {
            val at = details?.takenAt
            Text(
                listOfNotNull(at?.let { "${formatDay(it.toLocalDate().toString())} ${"%02d:%02d".format(it.hour, it.minute)}" }, current?.place()).joinToString(" · "),
                style = AppTheme.typography.caption, color = MediaOverlay.contentVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Space.s),
            )
        },
        footer = {
            if (current != null) Row(Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                val index = order[current.key] ?: -1
                if (lead != null) lead(current)
                else if (current.choosable) Row(
                    Modifier.weight(1f).height(48.dp)
                        .clickable(enabled = enabled && (index >= 0 || !full), role = Role.Checkbox) { onToggle(current) }
                        .semantics { selected = index >= 0 }
                        .testTag("picker_viewer_choose"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    ChoiceMark(index, Modifier.size(26.dp))
                    Text(stringResource(if (index >= 0) Res.string.camera_chosen else Res.string.camera_choose), style = AppTheme.typography.body, color = MediaOverlay.content)
                } else Text(stringResource(Res.string.camera_raw_kept), style = AppTheme.typography.body, color = MediaOverlay.content, modifier = Modifier.weight(1f))
                if (lead == null) addButton()
            }
        },
        overlay = {
            if (busy) Column(
                Modifier.fillMaxSize().background(MediaOverlay.bar),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
            ) {
                Spinner(color = MediaOverlay.content)
                Text(stringResource(Res.string.camera_deleting), style = AppTheme.typography.caption, color = MediaOverlay.content)
            }
        },
    ) { page, onZoom, onTap ->
        val pick = shown[page]
        val active = pager.currentPage == page
        val clip = pick.rememberMotion(active)
        LaunchedEffect(pick.key, clip) { if (clip != null) clips[pick.key] = clip }
        androidx.compose.runtime.CompositionLocalProvider(LocalPageLive provides liveOf(pick)) {
            pick.Page(active, onZoom, onTap)
        }
    }
}
