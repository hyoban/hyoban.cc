package cc.hyoban.moments.ui.picker

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
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

/**
 * The photos full size, black as the photo viewer (03): swiped through, chosen and added from
 * here too, each page as its source draws it. Swiped up, the photo makes room for its info
 * (PhotoInfo); swiped down, the info goes, else the viewer.
 */
@Composable
internal fun Viewer(
    container: AppContainer,
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
    val details = current?.rememberDetails()

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("picker_viewer")) {
        // Zoomed in, a swipe moves the photo rather than turning the page.
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(current?.key, pager.isScrollInProgress) {
            if (!pager.isScrollInProgress) current?.settled()
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
                val settled = pager.currentPage == page
                shown[page].Page { if (settled) zoomed = it }
            }
        }) { modifier -> PhotoInfoPanel(container, details, modifier, note = current?.infoNote(details)?.let { stringResource(it) }) }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("picker_viewer_close")) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), tint = MediaOverlay.content)
            }
            Text("${pager.currentPage + 1} / ${shown.size}", style = AppTheme.typography.body, color = MediaOverlay.content,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { current?.let(onDelete) }, enabled = enabled && current != null, modifier = Modifier.testTag("picker_viewer_delete")) {
                Icon(AppIcons.Trash, contentDescription = stringResource(current?.source?.deleteWords ?: R.string.camera_delete), tint = MediaOverlay.content)
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
                    listOfNotNull(at?.let { "${formatDay(it.toLocalDate().toString())} ${"%02d:%02d".format(it.hour, it.minute)}" }, current.place()).joinToString(" · "),
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
