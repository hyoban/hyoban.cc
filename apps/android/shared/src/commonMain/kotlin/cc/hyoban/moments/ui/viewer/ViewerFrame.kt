package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.res.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.components.LocalInPictureInPicture
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.ViewerSystemBars
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.rememberPredictiveBack
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.LocalDesktop
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.theme.continuousRoundedRectangle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The one viewer: whatever the app shows full size on black, a Moment's media, 选照片's photos
 * or those picked for a Moment being written, swiped through the same way. Swipe sideways
 * between them, up for the shown one's info and down to put it away or close; a tap hides
 * everything but the photo. Over it a bar with close, which of how many and [top]'s own; under
 * it a video's [controls], the others small in a row (the filmstrip) while upright, a [caption]
 * with the info and the viewer's [actions] beside it, and a [footer]. With a keyboard the arrows
 * go from one to the next, Space is [onSpace]'s and Escape closes.
 *
 * Each viewer gives its own [page] for an item, drawing it zoomable and calling back when it is
 * zoomed or tapped, and its [thumb] for the filmstrip, [marked] with a dot where it is chosen.
 */
@Composable
fun ViewerFrame(
    pager: PagerState,
    key: (Int) -> Any,
    onClose: () -> Unit,
    thumb: @Composable (index: Int) -> Unit,
    thumbLabel: @Composable (index: Int) -> String?,
    caption: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    marked: (Int) -> Boolean = { false },
    info: (@Composable (Modifier) -> Unit)? = null,
    onSpace: (() -> Unit)? = null,
    top: @Composable BoxScope.(Alignment) -> Unit = {},
    controls: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    page: @Composable (index: Int, onZoom: (Boolean) -> Unit, onTap: () -> Unit) -> Unit,
) {
    val count = pager.pageCount
    var zoomed by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    // The shown one's info beside or under it (PhotoInfo), swiped up or opened from the bar.
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // How far the photo has been dragged up or down, read only where it is drawn, so a drag
    // moves pixels rather than rebuilding the screen every frame.
    var drag by remember { mutableFloatStateOf(0f) }
    val dragging by remember { derivedStateOf { drag != 0f } }
    val density = LocalDensity.current
    val closeAt = with(density) { 120.dp.toPx() }
    val haptics = rememberHaptics()
    // In a window of its own only the video shows: no bars, no controls.
    val inPip = LocalInPictureInPicture.current
    ViewerSystemBars(hidden = !chrome || inPip)
    val sideways = isPhoneLandscape()
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { keys.requestFocus() }

    BoxWithConstraints(
        modifier.fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (zoomed) return@onKeyEvent false
                        val next = (event.key == Key.DirectionRight) != rtl
                        val to = (pager.currentPage + if (next) 1 else -1).coerceIn(0, (count - 1).coerceAtLeast(0))
                        scope.launch { pager.animateScrollToPage(to) }
                        true
                    }
                    Key.Spacebar -> onSpace?.let { it(); true } ?: false
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable()
            .testTag("viewer"),
    ) {
        val fadeOver = constraints.maxHeight / 3f
        // How far a drag has gone toward closing: the photo shrinks a little and the black
        // thins out to the page under the viewer.
        fun progress() = (abs(drag) / fadeOver).coerceIn(0f, 1f)
        // The system's back gesture previews closing as a drag down does, the photo following
        // the finger; let go, the photo closes from there, and called off it settles.
        val back = rememberPredictiveBack(enabled = !showInfo) { onClose() }
        LaunchedEffect(back) {
            snapshotFlow { back.progress }.collect { drag = it * fadeOver }
        }
        BackHandler(enabled = showInfo) { showInfo = false }
        val opened by animateFloatAsState(if (showInfo) 1f else 0f, AppTheme.motionScheme.defaultSpatialSpec(), label = "info")
        // Upright the info comes up under the photo; on its side it opens beside it.
        val panelHeight = maxHeight * 0.55f
        val panelWidth = 360.dp
        val backdrop = Color.Black
        Box(Modifier.fillMaxSize().drawBehind { drawRect(backdrop.copy(alpha = 1f - progress())) })
        HorizontalPager(
            pager,
            Modifier.fillMaxSize()
                .padding(
                    bottom = if (sideways || info == null) 0.dp else panelHeight * opened,
                    end = if (sideways && info != null) panelWidth * opened else 0.dp,
                )
                // The drag moves and shrinks the photo in layout, not just in drawing, so
                // where it is when let go is where its flight back into its tile starts.
                .layout { measurable, constraints ->
                    val scale = 1f - 0.2f * progress()
                    val width = (constraints.maxWidth * scale).roundToInt()
                    val height = (constraints.maxHeight * scale).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2 + drag.roundToInt())
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !zoomed,
                    state = rememberDraggableState { delta ->
                        haptics.across(abs(drag), abs(drag + delta), closeAt)
                        drag += delta
                    },
                    onDragStopped = { velocity ->
                        // Up opens the info, down puts it away; down again, or with no info, closes.
                        val up = info != null && drag < 0f && (-drag > closeAt / 2 || velocity < -1500f)
                        when {
                            up || (showInfo && drag > 0f) -> {
                                showInfo = up
                                animate(drag, 0f, initialVelocity = velocity) { value, _ -> drag = value }
                            }
                            drag > 0f && (drag > closeAt || velocity > 2000f) -> onClose()
                            else -> animate(drag, 0f, initialVelocity = velocity) { value, _ -> drag = value }
                        }
                    },
                ),
            userScrollEnabled = !zoomed,
            key = key,
        ) { index ->
            page(index, { if (pager.currentPage == index) zoomed = it }) { chrome = !chrome }
        }

        val motion = AppTheme.motionScheme
        val bars = chrome && !dragging && !inPip
        AnimatedVisibility(bars, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
            // Close at the start, which of how many in the middle, and the viewer's own at the end.
            Box(Modifier.fillMaxWidth().background(MediaOverlay.bar).statusBarsPadding().sideInsetsPadding().padding(Space.xs)) {
                // On a desktop, clear of the window's own buttons at its top left (D03): close at the end.
                val desktop = LocalDesktop.current
                Tooltip(stringResource(Res.string.action_close), below = true, modifier = Modifier.align(if (desktop) Alignment.CenterEnd else Alignment.CenterStart)) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("back")) {
                        Icon(AppIcons.Close, contentDescription = stringResource(Res.string.action_close), tint = MediaOverlay.content)
                    }
                }
                if (count > 1) {
                    Text(
                        "${pager.currentPage + 1} / $count",
                        color = MediaOverlay.content,
                        style = AppTheme.typography.body,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                top(if (desktop) Alignment.CenterStart else Alignment.CenterEnd)
            }
        }
        AnimatedVisibility(
            bars && !showInfo,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().background(MediaOverlay.bar).navigationBarsPadding().sideInsetsPadding()) {
                controls()
                if (!sideways && count > 1) Filmstrip(pager, key, thumb, thumbLabel, marked, Modifier.padding(top = Space.s))
                Row(Modifier.padding(start = Space.gutter - Space.s, end = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    caption()
                    if (info != null) Tooltip(stringResource(Res.string.photo_info)) {
                        IconButton(onClick = { showInfo = true }, modifier = Modifier.testTag("photo_info_open")) {
                            Icon(AppIcons.Info, contentDescription = stringResource(Res.string.photo_info), tint = MediaOverlay.content)
                        }
                    }
                    actions()
                }
                footer()
            }
        }
        if (info != null && opened > 0f) {
            if (sideways) {
                info(Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight().graphicsLayer { translationX = size.width * (1f - opened) })
            } else {
                info(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight).graphicsLayer { translationY = size.height * (1f - opened) })
            }
        }
        overlay()
    }
}

/**
 * The items before and after the one shown, small in a row with it in the middle: those
 * [marked] have a dot, a tap turns the viewer to one, and dragging the row turns it along.
 */
@Composable
private fun Filmstrip(
    pager: PagerState,
    key: (Int) -> Any,
    thumb: @Composable (Int) -> Unit,
    thumbLabel: @Composable (Int) -> String?,
    marked: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
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
            Modifier.fillMaxWidth().testTag("viewer_strip"),
            state = strip,
            // Room at each end for the first and the last to reach the middle.
            contentPadding = PaddingValues(horizontal = ((maxWidth - THUMB) / 2).coerceAtLeast(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            items(pager.pageCount, key = key) { index ->
                val selected = index == pager.currentPage
                val label = thumbLabel(index)
                val corner by animateDpAsState(if (selected) Radius.large else Radius.medium, AppTheme.motionScheme.fastSpatialSpec(), label = "thumb shape")
                val dim by animateFloatAsState(if (selected) 0f else MediaOverlay.CONTROL_ALPHA, AppTheme.motionScheme.fastEffectsSpec(), label = "thumb dim")
                Box(
                    Modifier.size(THUMB).clip(continuousRoundedRectangle(corner))
                        .clickable {
                            // One nearby is turned to; one far off is gone to at once.
                            scope.launch { if (abs(index - pager.currentPage) <= 3) pager.animateScrollToPage(index) else pager.scrollToPage(index) }
                        }
                        .semantics {
                            if (label != null) contentDescription = label
                            this.selected = selected
                        },
                ) {
                    thumb(index)
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black))
                    if (marked(index)) Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp)
                            .background(tokens.accent, CircleShape).border(1.5.dp, MediaOverlay.content, CircleShape),
                    )
                }
            }
        }
    }
}

/** How big each of the filmstrip's items is. */
private val THUMB = 44.dp
