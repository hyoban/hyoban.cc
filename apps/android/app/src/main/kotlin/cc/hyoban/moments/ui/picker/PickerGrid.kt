package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatYearMonth
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many photos across at each step of the grid's density, upright and on its side. */
private val Upright = listOf(3, 4, 6)
private val Sideways = listOf(6, 8, 12)

/**
 * The photos of what is listed by the day each was taken, newest first, each chosen by its circle
 * and opened by a tap. Two fingers pinched or spread change how many go across, [density] of
 * three steps; at the closest they are by the month and only looked through, without their
 * circles. A handle at the right (DateScrubber) goes through them by the month.
 */
@Composable
internal fun Grid(
    shown: List<Pick>,
    order: Map<String, Int>,
    full: Boolean,
    enabled: Boolean,
    sideways: Boolean,
    density: Int,
    onDensity: (Int) -> Unit,
    limited: (@Composable () -> Unit)?,
    onToggle: (Pick) -> Unit,
    onNearEnd: (() -> Unit)? = null,
    /** Whether it says so when there is nothing; not while what it shows is still being read. */
    empty: Boolean = true,
    onOpen: (Pick) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val byMonth = density == 2
    val groups: List<Pair<Any?, List<Pick>>> = remember(shown, byMonth) {
        grouped(shown, byMonth)
    }
    // Where each group starts among the grid's items, and its month, for the handle's bubble.
    val lead = (if (limited != null) 1 else 0) + (if (shown.isEmpty() && empty) 1 else 0)
    val months = remember(groups, lead) {
        var at = lead
        groups.map { (_, picks) -> (at to picks.firstOrNull()?.day?.let(YearMonth::from)).also { at += 1 + picks.size } }
    }
    val state = rememberLazyGridState()
    // More of a source read a page at a time, as the grid nears the end of what is read.
    val nearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) to it.totalItemsCount } }
            .collect { (last, total) -> if (total > 0 && last >= total - 40) nearEnd?.invoke() }
    }
    val haptics = rememberHaptics()
    val across = if (sideways) Sideways else Upright
    val scope = rememberCoroutineScope()
    val pxDensity = LocalDensity.current
    val topClearance = with(pxDensity) { LocalTopClearance.current.toPx() }
    val settleSpec = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    val pinch = remember { Pinch() }
    // Taking a new count, the photos are put straight in their places rather than moved there.
    val placement = if (pinch.relayout) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    // As other gallery apps do, the grid is only scaled while the fingers are down, and takes
    // the next count (one step at most) once they lift: in the same frame it is laid out anew,
    // scrolled so the photo under the fingers stays there (at its top it stays at its top), and
    // scaled to keep its photos as large as the fingers left them, then settles to its own size.
    LaunchedEffect(density) {
        if (density != pinch.next) return@LaunchedEffect
        pinch.settle = scope.launch {
            animate(pinch.nextScale, 1f, animationSpec = settleSpec) { v, _ -> pinch.nextScale = v }
            pinch.scale = 1f
            pinch.next = -1
            pinch.relayout = false
        }
    }
    val onPinchStart by rememberUpdatedState { origin: Offset ->
        // Caught while it settles, it goes on from where it is.
        pinch.settle?.cancel()
        if (pinch.next == density) pinch.scale = pinch.nextScale
        pinch.next = -1
        pinch.relayout = false
        pinch.raw = pinch.scale
        // At its top the grid grows from the top of its photos, so none go up under the page's
        // large title while it is open; scrolled, from between the fingers.
        pinch.origin = if (state.canScrollBackward) origin else Offset(origin.x, topClearance)
        pinch.zone = 0
    }
    val onPinch by rememberUpdatedState { by: Float ->
        pinch.raw *= by
        val raw = pinch.raw
        // Past the first and last counts it gives only a little, as a rubber band.
        pinch.scale = when {
            density == 0 && raw > 1f -> 1f + (raw - 1f) * 0.2f
            density == 2 && raw < 1f -> 1f - (1f - raw) * 0.2f
            else -> raw
        }
        val zone = target(density, raw) - density
        if (zone != pinch.zone) {
            pinch.zone = zone
            if (zone != 0) haptics.tick()
        }
    }
    val onPinchEnd by rememberUpdatedState {
        val next = target(density, pinch.raw)
        if (next == density) {
            pinch.settle = scope.launch { animate(pinch.scale, 1f, animationSpec = settleSpec) { v, _ -> pinch.scale = v } }
        } else {
            val gap = with(pxDensity) { 3.dp.toPx() }
            val width = state.layoutInfo.viewportSize.width
            val side = (width - gap * (across[next] - 1)) / across[next]
            // Only a grid already scrolled is held at the photo under the fingers: scrolled here,
            // not by a drag, it would leave the page's large title open over what went under it.
            if (state.canScrollBackward) anchor(state, pinch.origin)?.let { hold ->
                indexOf(hold.key, grouped(shown, next == 2), lead)?.let { at ->
                    state.requestScrollToItem(at, (state.layoutInfo.beforeContentPadding + hold.fraction * side - hold.y).roundToInt())
                }
            }
            pinch.nextScale = pinch.scale * across[next] / across[density]
            pinch.next = next
            pinch.relayout = true
            onDensity(next)
        }
    }
    // Kept across recompositions: a new gesture handler would cut short the pinch under way.
    val pinchModifier = remember { Modifier.pinchGesture({ onPinchStart(it) }, { onPinch(it) }, { onPinchEnd() }) }
    Box(Modifier.fillMaxSize().then(pinchModifier)) {
        LazyVerticalGrid(
            GridCells.Fixed(across[density]),
            Modifier.fillMaxSize().graphicsLayer {
                val scale = if (density == pinch.next) pinch.nextScale else pinch.scale
                scaleX = scale
                scaleY = scale
                val origin = pinch.origin
                if (origin.isSpecified && size.width > 0f) transformOrigin = TransformOrigin(origin.x / size.width, origin.y / size.height)
            }.testTag("picker_grid"),
            state = state,
            contentPadding = PaddingValues(top = LocalTopClearance.current, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (limited != null) item(key = "limited", span = { GridItemSpan(maxLineSpan) }) { limited() }
            if (shown.isEmpty() && empty) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(Res.string.picker_empty), style = AppTheme.typography.body, color = tokens.tx3, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.xl * 3))
            }
            groups.forEach { (group, picks) ->
                item(key = "group-$group", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        when (group) {
                            null -> stringResource(Res.string.camera_no_date)
                            is YearMonth -> formatYearMonth(group)
                            today -> stringResource(Res.string.camera_today)
                            else -> formatDay(group.toString(), today)
                        },
                        style = AppTheme.typography.caption, color = tokens.tx2,
                        modifier = Modifier.animateItem(placementSpec = placement).padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
                    )
                }
                items(picks, key = { it.key }) { pick ->
                    val index = order[pick.key] ?: -1
                    val thumb by rememberThumb(pick)
                    val corner = pick.badge()
                    val label = pick.description()
                    // At the limit, what can't be chosen any more is set back.
                    val setBack = full && index < 0
                    Box(
                        Modifier.animateItem(placementSpec = placement).aspectRatio(1f).background(tokens.fill)
                            .then(if (index >= 0) Modifier.border(3.dp, tokens.accent) else Modifier)
                            .semantics { contentDescription = label; selected = index >= 0 }
                            .clickable(onClickLabel = stringResource(Res.string.camera_view)) { onOpen(pick) },
                    ) {
                        thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (setBack) 0.38f else 1f)) }
                        if (!byMonth) corner?.let {
                            Text(it, style = AppTheme.typography.caption.strong, color = MediaOverlay.content, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                        // The circle chooses it, its target the corner's 48dp; by the month there is
                        // no room for one, and what is chosen only keeps its number.
                        if (byMonth) {
                            if (index >= 0) ChoiceMark(index, Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
                        } else if (pick.choosable) Box(
                            Modifier.align(Alignment.TopEnd).size(48.dp)
                                .clickable(enabled = enabled && !setBack, role = Role.Checkbox, onClickLabel = stringResource(Res.string.camera_choose)) { onToggle(pick) }
                                .testTag("picker_choose_${pick.key}"),
                            contentAlignment = Alignment.TopEnd,
                        ) { ChoiceMark(index, Modifier.padding(6.dp).size(22.dp).alpha(if (setBack) 0.38f else 1f)) }
                    }
                }
            }
        }
        DateScrubber(state, bottom = 96.dp) { index ->
            val at = months.binarySearch { it.first.compareTo(index) }.let { if (it >= 0) it else -it - 2 }
            months.getOrNull(at)?.second?.let { formatYearMonth(it) }
        }
    }
}

/** A pinch of the grid as it goes: how far the fingers have taken it and where it is held. */
private class Pinch {
    var scale by mutableFloatStateOf(1f)
    var origin by mutableStateOf(Offset.Unspecified)
    /** The count it has been let go at, and the scale it settles from there. */
    var next by mutableIntStateOf(-1)
    var nextScale by mutableFloatStateOf(1f)
    /** While the grid takes a new count and settles. */
    var relayout by mutableStateOf(false)
    var raw = 1f
    var zone = 0
    var settle: Job? = null
}

/** The step a pinch to [raw] lets go at from [density]: a fifth closer or wider, one step at most. */
private fun target(density: Int, raw: Float) = when {
    raw > 1.2f && density > 0 -> density - 1
    raw < 0.8f && density < 2 -> density + 1
    else -> density
}

/** The photos by the day they were taken, or by the month. */
private fun grouped(shown: List<Pick>, byMonth: Boolean): List<Pair<Any?, List<Pick>>> =
    if (byMonth) shown.groupBy { pick -> pick.day?.let(YearMonth::from) }.toList() else shown.groupBy { it.day }.toList()

/** A photo to keep under the fingers: its key, how far down it they are, and where they are. */
private class Hold(val key: String, val fraction: Float, val y: Float)

/** The photo under [origin] (the nearest below it on a day's heading), as the grid is now laid out. */
private fun anchor(state: LazyGridState, origin: Offset): Hold? {
    if (!origin.isSpecified) return null
    val info = state.layoutInfo
    val y = origin.y - info.beforeContentPadding
    val photos = info.visibleItemsInfo.filter { (it.key as? String)?.let { k -> k != "limited" && k != "empty" && !k.startsWith("group-") } == true }
    val under = photos.firstOrNull { y >= it.offset.y && y < it.offset.y + it.size.height && origin.x >= it.offset.x && origin.x < it.offset.x + it.size.width }
        ?: photos.firstOrNull { it.offset.y + it.size.height > y }
        ?: return null
    val key = under.key as String
    val fraction = ((y - under.offset.y) / under.size.height).coerceIn(0f, 1f)
    return Hold(key, fraction, origin.y)
}

/** Where the photo of [key] is among the grid's items. */
private fun indexOf(key: String, groups: List<Pair<Any?, List<Pick>>>, lead: Int): Int? {
    var at = lead
    for ((_, picks) in groups) {
        at += 1
        val i = picks.indexOfFirst { it.key == key }
        if (i >= 0) return at + i
        at += picks.size
    }
    return null
}

/**
 * Two fingers pinched or spread over what scrolls under them, the scroll left alone meanwhile:
 * [onStart] where they first are, [onZoom] with each change in how far apart, [onEnd] when lifted.
 */
private fun Modifier.pinchGesture(onStart: (Offset) -> Unit, onZoom: (Float) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (!pinching) {
                        pinching = true
                        onStart(event.calculateCentroid())
                    }
                    onZoom(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            // Also when the gesture is cut short, so the grid never stays scaled.
            if (pinching) onEnd()
        }
    }
}

/** Whether a photo is chosen: an empty ring, or the accent with its place among those chosen. */
@Composable
internal fun ChoiceMark(index: Int, modifier: Modifier) {
    Box(
        modifier.then(if (index >= 0) Modifier.background(tokens.accent, CircleShape) else Modifier.background(MediaOverlay.control, CircleShape).border(2.dp, MediaOverlay.content, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (index >= 0) Text("${index + 1}", style = AppTheme.typography.caption.strong, color = tokens.onAccent)
    }
}
