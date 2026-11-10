package cc.hyoban.moments.ui.picker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.design.softShadow
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The handle at the right of a long grid laid out by the day (DateScrubber): it comes while the
 * grid scrolls and goes a moment after, sits as far down as the grid is through what it holds,
 * and dragged takes the grid there, a bubble beside it saying which month by [label] of the item
 * reached. It reaches only what the grid holds, so a source read a page at a time reads on at the end.
 */
@Composable
internal fun BoxScope.DateScrubber(state: LazyGridState, bottom: Dp, label: (Int) -> String?) {
    // Not worth a handle under three screens of photos.
    val long by remember(state) {
        derivedStateOf { state.layoutInfo.let { it.visibleItemsInfo.isNotEmpty() && it.totalItemsCount > it.visibleItemsInfo.size * 3 } }
    }
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) shown = true
        else {
            delay(1500)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown && long) 1f else 0f, AppTheme.motionScheme.fastEffectsSpec(), label = "scrubber")
    if (alpha == 0f) return

    val haptics = rememberHaptics()
    val words by rememberUpdatedState(label)
    val density = LocalDensity.current
    val handle = with(density) { 48.dp.toPx() }
    var track by remember { mutableIntStateOf(0) }
    val room = (track - handle).coerceAtLeast(1f)
    // How far through the grid is, by its first item among those that can come first.
    val scrolled by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
        }
    }
    var dragged by remember { mutableFloatStateOf(0f) }
    var reached by remember { mutableIntStateOf(0) }
    val fraction = if (dragging) dragged else scrolled

    Box(
        Modifier.fillMaxSize().padding(top = LocalTopClearance.current, bottom = bottom).onSizeChanged { track = it.height }
            .graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction * room).roundToInt()) }.size(48.dp)
                .pointerInput(state) {
                    var month: String? = null
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragged = scrolled
                            reached = state.firstVisibleItemIndex
                            month = words(reached)
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        dragged = (dragged + amount / room).coerceIn(0f, 1f)
                        val index = (dragged * (state.layoutInfo.totalItemsCount - 1)).roundToInt().coerceAtLeast(0)
                        if (index != reached) {
                            reached = index
                            state.requestScrollToItem(index)
                            val now = words(index)
                            if (now != month) {
                                month = now
                                haptics.tick()
                            }
                        }
                    }
                }
                .clearAndSetSemantics { }
                .testTag("picker_scrubber"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val ink = tokens.tx2
            Canvas(
                Modifier.padding(end = Space.xs).size(28.dp, 44.dp).softShadow(ShadowElevation, CircleShape)
                    .background(tokens.surface, CircleShape).border(cardBorder(), CircleShape),
            ) {
                // Two small points, up and down.
                val stroke = 2.dp.toPx()
                val half = 4.dp.toPx()
                val middle = size.width / 2
                listOf(15.dp.toPx() to -1f, size.height - 15.dp.toPx() to 1f).forEach { (tip, way) ->
                    drawLine(ink, Offset(middle - half, tip - way * half), Offset(middle, tip), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(middle + half, tip - way * half), Offset(middle, tip), stroke, StrokeCap.Round)
                }
            }
        }
        val month = if (dragging) label(reached) else null
        if (month != null) Text(
            month,
            style = AppTheme.typography.label.strong,
            color = tokens.bg,
            modifier = Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-with(density) { 44.dp.roundToPx() }, (fraction * room + handle / 2 - with(density) { 16.dp.toPx() }).roundToInt()) }
                .background(tokens.tx, CircleShape).padding(horizontal = Space.m, vertical = 6.dp),
        )
    }
}
