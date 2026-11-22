package cc.hyoban.moments.ui.design

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import cc.hyoban.moments.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.LayoutDirection
import cc.hyoban.moments.ui.theme.Radius
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Sheets from the foot of the screen: the surface, its top in the large corners, a short handle
// at its top, dragged by it or by what it holds once that is at its top.

/**
 * [color] in [shape], round at its top alone, as a sheet is, and what it holds clipped to its
 * rectangle, for a sheet whose content keeps clear of its corners. The graphics pipeline draws a
 * shape it can't work out as a mask in software, again every frame, as large as the shape and
 * across every word and photo clipped to it: the corners are drawn as shapes the size of the
 * corners alone, and the rest, and the clip, as rectangles, which it draws and clips as it goes.
 */
fun Modifier.sheetSurface(color: Color, shape: CornerBasedShape): Modifier =
    drawWithCache {
        val corner = minOf((Radius.large * 3).toPx(), size.width / 2, size.height).roundToInt().toFloat()
        val piece = Size(corner, corner)
        val start = shape.copy(topEnd = ZeroCornerSize).createOutline(piece, layoutDirection, this)
        val end = shape.copy(topStart = ZeroCornerSize).createOutline(piece, layoutDirection, this)
        val (left, right) = if (layoutDirection == LayoutDirection.Ltr) start to end else end to start
        onDrawBehind {
            drawOutline(left, color)
            translate(left = size.width - corner) { drawOutline(right, color) }
            drawRect(color, Offset(corner, 0f), Size(size.width - corner * 2, corner))
            drawRect(color, Offset(0f, corner), Size(size.width, size.height - corner))
        }
    }.clipToBounds()

/** A sheet's handle: a short capsule in the control border. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 12.dp).size(32.dp, 4.dp).background(tokens.borderStrong, Corners.full))
}

/**
 * A sheet over the page in a window of its own: it rises from the foot, the page dimmed behind;
 * dragged down past a third of itself, tapped behind or backed out of, it goes.
 */
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = tokens.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    // How far it travels out: its height from the foot, or on its side its width out to the end.
    var travel by remember { mutableFloatStateOf(0f) }
    val drag = rememberSheetDrag(onSettled = { to -> if (to > 0f) onDismissRequest() })
    val leave: () -> Unit = {
        scope.launch {
            drag.animateTo(travel)
            onDismissRequest()
        }
    }
    // On a window 840 wide or more the panel is a dialog in the middle, rounded all round (L01);
    // on a phone on its side it slides in from the end, the whole height and 360 wide (H00).
    // On a desktop it is the design's inspector (D10, D20, D29): 360 wide down the window's end.
    val desktop = LocalDesktop.current
    val wide = !desktop && cc.hyoban.moments.ui.components.isExpandedWidth()
    val side = desktop || (!wide && cc.hyoban.moments.ui.components.hidesSystemBars())
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val noSystemBars = cc.hyoban.moments.ui.components.hidesSystemBars()
    Dialog(onDismissRequest = leave, properties = cc.hyoban.moments.ui.components.fullWindowDialogProperties()) {
        cc.hyoban.moments.ui.components.DialogSystemBars(noSystemBars)
        val alignment = when {
            wide -> Alignment.Center
            side -> Alignment.CenterEnd
            else -> Alignment.BottomCenter
        }
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = leave), contentAlignment = alignment) {
            val shape = when {
                wide -> AppTheme.shapes.large
                side -> AppTheme.shapes.large.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
                else -> AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
            }
            Column(
                modifier
                    .then(
                        if (side) {
                            Modifier.fillMaxHeight().width(SIDE_SHEET_WIDTH)
                        } else {
                            Modifier.statusBarsPadding().widthIn(max = if (wide) 560.dp else 640.dp).fillMaxWidth()
                        },
                    )
                    .onSizeChanged {
                        travel = (if (side) it.width else it.height).toFloat()
                        val first = drag.position.isNaN()
                        drag.setAnchors(listOf(0f, travel), start = travel)
                        if (first) scope.launch { drag.animateTo(0f) }
                    }
                    .offset {
                        val shift = (drag.position.takeUnless { it.isNaN() } ?: travel).coerceIn(0f, travel.coerceAtLeast(0f)).roundToInt()
                        if (side) IntOffset(if (ltr) shift else -shift, 0) else IntOffset(0, shift)
                    }
                    .clip(shape)
                    .background(containerColor)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    // On its side it goes sideways, across the way its list scrolls.
                    .sheetDrag(drag, if (side) Orientation.Horizontal else Orientation.Vertical, reverse = side && !ltr)
                    .then(if (side) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)).padding(top = Space.l) else Modifier.navigationBarsPadding())
                    .imePadding(),
            ) {
                // Dragged sideways on its side, it has no handle to show which way.
                if (!side) SheetHandle(Modifier.align(Alignment.CenterHorizontally))
                CompositionLocalProvider(LocalContentColor provides tokens.tx) { content() }
            }
        }
    }
}

/** How wide a sheet is on a phone on its side, sliding in from the end. */
private val SIDE_SHEET_WIDTH = 360.dp

/** Where a standard sheet rests: away, peeking, or as tall as it can be. */
enum class SheetValue { Hidden, PartiallyExpanded, Expanded }

/** Where a standard sheet is and is going; moved by [partialExpand], [expand] and [hide], or by dragging. */
@Stable
class SheetState internal constructor(initialValue: SheetValue, val skipHiddenState: Boolean, internal val drag: SheetDrag) {
    var currentValue by mutableStateOf(initialValue)
        private set
    internal var anchors by mutableStateOf<Map<SheetValue, Float>>(emptyMap())
        private set

    internal fun place(values: Map<SheetValue, Float>) {
        anchors = values
        drag.setAnchors(values.values, start = values[currentValue] ?: values.values.max())
    }

    /** The value resting at [position], as it is let go. */
    internal fun settleAt(position: Float) {
        anchors.entries.firstOrNull { it.value == position }?.let { currentValue = it.key }
    }

    /** Where its top edge is, in pixels from the top of its scaffold; NaN before it's laid out. */
    val top: Float get() = drag.position

    /** How far it is drawn up from resting toward its full height: 0 resting or below, 1 at full height. */
    val raised: Float
        get() {
            val rest = anchors[SheetValue.PartiallyExpanded] ?: return 0f
            val top = anchors[SheetValue.Expanded] ?: return 0f
            val now = drag.position
            if (now.isNaN() || rest <= top) return 0f
            return ((rest - now) / (rest - top)).coerceIn(0f, 1f)
        }

    suspend fun partialExpand() = animateTo(SheetValue.PartiallyExpanded)
    suspend fun expand() = animateTo(SheetValue.Expanded)
    suspend fun hide() = animateTo(if (skipHiddenState) SheetValue.PartiallyExpanded else SheetValue.Hidden)

    internal suspend fun animateTo(value: SheetValue) {
        currentValue = value
        val target = anchors[value] ?: return
        drag.animateTo(target)
    }
}

@Composable
fun rememberStandardBottomSheetState(initialValue: SheetValue = SheetValue.PartiallyExpanded, skipHiddenState: Boolean = true): SheetState {
    lateinit var state: SheetState
    val drag = rememberSheetDrag(onSettle = { state.settleAt(it) })
    state = remember { SheetState(initialValue, skipHiddenState, drag) }
    return state
}

class BottomSheetScaffoldState(val bottomSheetState: SheetState)

@Composable
fun rememberBottomSheetScaffoldState(bottomSheetState: SheetState = rememberStandardBottomSheetState()): BottomSheetScaffoldState =
    remember(bottomSheetState) { BottomSheetScaffoldState(bottomSheetState) }

/**
 * [content] with a sheet over its foot: [sheetPeekHeight] of it showing at rest, dragged up to
 * all of it (the list in it scrolling only once it is all up) and, unless its state skips that,
 * down and away.
 */
@Composable
fun BottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    scaffoldState: BottomSheetScaffoldState = rememberBottomSheetScaffoldState(),
    sheetPeekHeight: Dp = 56.dp,
    sheetSwipeEnabled: Boolean = true,
    sheetDragHandle: (@Composable () -> Unit)? = { SheetHandle() },
    sheetContainerColor: Color = tokens.surface,
    sheetShadowElevation: Dp = 0.dp,
    containerColor: Color = tokens.bg,
    content: @Composable () -> Unit,
) {
    val state = scaffoldState.bottomSheetState
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(containerColor)) {
        val room = constraints.maxHeight.toFloat()
        var sheetHeight by remember { mutableFloatStateOf(0f) }
        val peek = with(density) { sheetPeekHeight.toPx() }
        LaunchedEffect(room, sheetHeight, peek, state.skipHiddenState) {
            if (sheetHeight == 0f) return@LaunchedEffect
            state.place(buildMap {
                put(SheetValue.PartiallyExpanded, (room - peek).coerceAtLeast(room - sheetHeight))
                put(SheetValue.Expanded, (room - sheetHeight).coerceAtLeast(0f))
                if (!state.skipHiddenState) put(SheetValue.Hidden, room)
            })
            state.animateTo(state.currentValue)
        }
        content()
        val shape = AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = with(density) { room.toDp() })
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .offset { IntOffset(0, (state.drag.position.takeUnless { it.isNaN() } ?: room).roundToInt()) }
                .then(if (sheetShadowElevation > 0.dp) Modifier.softShadow(sheetShadowElevation, shape) else Modifier)
                // Below a handle, taller than the corners are round, nothing in the sheet reaches them.
                .then(if (sheetDragHandle != null) Modifier.sheetSurface(sheetContainerColor, shape) else Modifier.clip(shape).background(sheetContainerColor))
                .sheetDrag(state.drag, enabled = sheetSwipeEnabled),
        ) {
            if (sheetDragHandle != null) Box(Modifier.align(Alignment.CenterHorizontally)) { sheetDragHandle() }
            CompositionLocalProvider(LocalContentColor provides tokens.tx) { sheetContent() }
        }
    }
}
