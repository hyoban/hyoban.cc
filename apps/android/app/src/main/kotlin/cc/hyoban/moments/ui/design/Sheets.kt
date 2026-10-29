package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Sheets from the foot of the screen: the surface, its top in the large corners, a short handle
// at its top, dragged by it or by what it holds once that is at its top.

/** A sheet's handle: a short capsule in the control border. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = 12.dp).size(32.dp, 4.dp).clip(Corners.full).background(tokens.borderStrong))
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
    val offset = remember { Animatable(Float.MAX_VALUE) }
    var height by remember { mutableFloatStateOf(0f) }
    val leave: () -> Unit = {
        scope.launch {
            offset.animateTo(height, spring(stiffness = 1500f))
            onDismissRequest()
        }
    }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = leave), contentAlignment = Alignment.BottomCenter) {
            val shape = AppTheme.shapes.large.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
            Column(
                modifier
                    .statusBarsPadding()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .onSizeChanged {
                        val first = height == 0f
                        height = it.height.toFloat()
                        if (first) scope.launch { offset.snapTo(height); offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f)) }
                    }
                    .offset { IntOffset(0, offset.value.coerceIn(0f, height.coerceAtLeast(0f)).roundToInt()) }
                    .clip(shape)
                    .background(containerColor)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .draggable(
                        rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, height)) } },
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (offset.value > height / 3 || velocity > 2000f) leave() else offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
                        },
                    )
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                SheetHandle(Modifier.align(Alignment.CenterHorizontally))
                CompositionLocalProvider(LocalContentColor provides tokens.tx) { content() }
            }
        }
    }
}

/** Where a standard sheet rests: away, peeking, or as tall as it can be. */
enum class SheetValue { Hidden, PartiallyExpanded, Expanded }

/** Where a standard sheet is and is going; moved by [partialExpand], [expand] and [hide], or by dragging. */
@Stable
class SheetState(initialValue: SheetValue, val skipHiddenState: Boolean) {
    var currentValue by mutableStateOf(initialValue)
        private set
    internal val offset = Animatable(Float.NaN)
    internal var anchors by mutableStateOf<Map<SheetValue, Float>>(emptyMap())

    suspend fun partialExpand() = animateTo(SheetValue.PartiallyExpanded)
    suspend fun expand() = animateTo(SheetValue.Expanded)
    suspend fun hide() = animateTo(if (skipHiddenState) SheetValue.PartiallyExpanded else SheetValue.Hidden)

    internal suspend fun animateTo(value: SheetValue, velocity: Float = 0f) {
        currentValue = value
        val target = anchors[value] ?: return
        if (offset.value.isNaN()) offset.snapTo(target) else offset.animateTo(target, spring(dampingRatio = 1f, stiffness = 500f), velocity)
    }

    /** To the anchor nearest where it was let go, or the next one the way it was flung. */
    internal suspend fun settle(velocity: Float) {
        val now = offset.value
        if (now.isNaN() || anchors.isEmpty()) return
        val sorted = anchors.entries.sortedBy { it.value }
        val target = when {
            velocity > 1000f -> sorted.firstOrNull { it.value > now + 1 } ?: sorted.last()
            velocity < -1000f -> sorted.lastOrNull { it.value < now - 1 } ?: sorted.first()
            else -> sorted.minBy { abs(it.value - now) }
        }
        animateTo(target.key, velocity)
    }

    internal fun dragBy(delta: Float): Float {
        val now = offset.value
        if (now.isNaN() || anchors.isEmpty()) return 0f
        val next = (now + delta).coerceIn(anchors.values.min(), anchors.values.max())
        return next - now
    }
}

@Composable
fun rememberStandardBottomSheetState(initialValue: SheetValue = SheetValue.PartiallyExpanded, skipHiddenState: Boolean = true): SheetState =
    remember { SheetState(initialValue, skipHiddenState) }

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
            state.anchors = buildMap {
                put(SheetValue.PartiallyExpanded, (room - peek).coerceAtLeast(room - sheetHeight))
                put(SheetValue.Expanded, (room - sheetHeight).coerceAtLeast(0f))
                if (!state.skipHiddenState) put(SheetValue.Hidden, room)
            }
            state.animateTo(state.currentValue)
        }
        val drag = remember(state) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    val moved = state.dragBy(available.y)
                    if (moved != 0f) scope.launch { state.offset.snapTo(state.offset.value + moved) }
                    return Offset(0f, moved)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    val moved = state.dragBy(available.y)
                    if (moved != 0f) scope.launch { state.offset.snapTo(state.offset.value + moved) }
                    return Offset(0f, moved)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val at = state.offset.value
                    val resting = state.anchors.values.any { abs(it - at) < 1f }
                    if (resting) return Velocity.Zero
                    state.settle(available.y)
                    return available
                }
            }
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
                .offset { IntOffset(0, (state.offset.value.takeUnless { it.isNaN() } ?: room).roundToInt()) }
                .then(if (sheetShadowElevation > 0.dp) Modifier.softShadow(sheetShadowElevation, shape) else Modifier)
                .clip(shape)
                .background(sheetContainerColor)
                .draggable(
                    rememberDraggableState { delta ->
                        val moved = state.dragBy(delta)
                        if (moved != 0f) scope.launch { state.offset.snapTo(state.offset.value + moved) }
                    },
                    Orientation.Vertical,
                    enabled = sheetSwipeEnabled,
                    onDragStopped = { velocity -> state.settle(velocity) },
                )
                .then(if (sheetSwipeEnabled) Modifier.nestedScroll(drag) else Modifier),
        ) {
            if (sheetDragHandle != null) Box(Modifier.align(Alignment.CenterHorizontally)) { sheetDragHandle() }
            CompositionLocalProvider(LocalContentColor provides tokens.tx) { sheetContent() }
        }
    }
}
