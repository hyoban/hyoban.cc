package cc.hyoban.moments.ui.picker

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.VideoControls
import cc.hyoban.moments.ui.viewer.VideoState
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.math.abs
import kotlinx.coroutines.launch
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
 * here too, each page as its source draws it. Under the photo, upright, the photos before and
 * after it small in a row (Filmstrip), the one shown in the middle, and a video's controls over
 * them. Swiped up, the photo makes room for its info (PhotoInfo); swiped down, the info goes, else
 * the viewer.
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
    // Each page's video, held here so the controls under the pages work the one shown.
    val videos = remember { mutableMapOf<String, VideoState>() }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("picker_viewer")) {
        // Zoomed in, a swipe moves the photo rather than turning the page.
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(current?.key, pager.isScrollInProgress) {
            // Once it rests there a moment, so the row of small ones dragged past it asks nothing of it.
            if (!pager.isScrollInProgress) {
                delay(300)
                current?.settled()
            }
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
                shown[page].Page(settled, videos.getOrPut(shown[page].key) { VideoState() }) { if (settled) zoomed = it }
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
            VideoControls(videos.getOrPut(current.key) { VideoState() })
            val gutter = Space.gutter
            if (!sideways && shown.size > 1) ViewerStrip(
                shown, pager, order,
                // From edge to edge, past the bar's own margins.
                Modifier.layout { measurable, constraints ->
                    val wider = gutter.roundToPx() * 2
                    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + wider, maxWidth = constraints.maxWidth + wider))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-wider / 2, 0) }
                },
            )
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

/**
 * The photos before and after the one shown, small in a row with it in the middle (Filmstrip):
 * those chosen have a dot, a tap turns the viewer to one, and dragging the row turns it along.
 */
@Composable
private fun ViewerStrip(shown: List<Pick>, pager: PagerState, order: Map<String, Int>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val strip = rememberLazyListState(pager.currentPage)
    // Whether the row is moved by hand, the viewer following it, or follows the viewer.
    var byHand by remember { mutableStateOf(false) }
    val held by strip.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(held) { if (held) byHand = true }
    LaunchedEffect(strip, pager) {
        launch {
            snapshotFlow { strip.isScrollInProgress }.collect { moving ->
                if (!moving && byHand) {
                    byHand = false
                    strip.animateScrollToItem(pager.currentPage)
                }
            }
        }
        launch {
            snapshotFlow {
                val info = strip.layoutInfo
                val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
            }.collect { if (byHand && it != null && it != pager.currentPage) pager.scrollToPage(it) }
        }
        snapshotFlow { pager.currentPage }.collect { if (!byHand) strip.animateScrollToItem(it) }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth().testTag("picker_viewer_strip"),
            state = strip,
            // Room at each end for the first and the last to reach the middle.
            contentPadding = PaddingValues(horizontal = ((maxWidth - 44.dp) / 2).coerceAtLeast(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            items(shown.size, key = { shown[it].key }) { index ->
                val pick = shown[index]
                val selected = index == pager.currentPage
                val thumb by rememberThumb(pick)
                val label = pick.description()
                val corner by animateDpAsState(if (selected) Radius.large else Radius.medium, AppTheme.motionScheme.fastSpatialSpec(), label = "thumb shape")
                val dim by animateFloatAsState(if (selected) 0f else MediaOverlay.CONTROL_ALPHA, AppTheme.motionScheme.fastEffectsSpec(), label = "thumb dim")
                Box(
                    Modifier.size(44.dp).clip(ContinuousRoundedRectangle(corner))
                        .clickable {
                            // One nearby is turned to; one far off is gone to at once.
                            scope.launch { if (abs(index - pager.currentPage) <= 3) pager.animateScrollToPage(index) else pager.scrollToPage(index) }
                        }
                        .semantics {
                            contentDescription = label
                            this.selected = selected
                        },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black))
                    if (pick.key in order) Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp)
                            .background(tokens.accent, CircleShape).border(1.5.dp, MediaOverlay.content, CircleShape),
                    )
                }
            }
        }
    }
}
