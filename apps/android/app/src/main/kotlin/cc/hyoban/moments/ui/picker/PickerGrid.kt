package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
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
    onOpen: (Pick) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val byMonth = density == 2
    val groups: List<Pair<Any?, List<Pick>>> = remember(shown, byMonth) {
        if (byMonth) shown.groupBy { pick -> pick.day?.let(YearMonth::from) }.toList() else shown.groupBy { it.day }.toList()
    }
    // Where each group starts among the grid's items, and its month, for the handle's bubble.
    val lead = (if (limited != null) 1 else 0) + (if (shown.isEmpty()) 1 else 0)
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
    val step by rememberUpdatedState { by: Int ->
        val next = (density + by).coerceIn(0, 2)
        if (next != density) {
            haptics.tick()
            onDensity(next)
        }
    }
    Box(Modifier.fillMaxSize().pinchSteps { step(it) }) {
        LazyVerticalGrid(
            GridCells.Fixed((if (sideways) Sideways else Upright)[density]),
            Modifier.fillMaxSize().testTag("picker_grid"),
            state = state,
            contentPadding = PaddingValues(top = LocalTopClearance.current, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (limited != null) item(key = "limited", span = { GridItemSpan(maxLineSpan) }) { limited() }
            if (shown.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.picker_empty), style = AppTheme.typography.body, color = tokens.tx3, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.xl * 3))
            }
            groups.forEach { (group, picks) ->
                item(key = "group-$group", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        when (group) {
                            null -> stringResource(R.string.camera_no_date)
                            is YearMonth -> formatYearMonth(group)
                            today -> stringResource(R.string.camera_today)
                            else -> formatDay(group.toString(), today)
                        },
                        style = AppTheme.typography.caption, color = tokens.tx2,
                        modifier = Modifier.animateItem().padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
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
                        Modifier.animateItem().aspectRatio(1f).background(tokens.fill)
                            .then(if (index >= 0) Modifier.border(3.dp, tokens.accent) else Modifier)
                            .semantics { contentDescription = label; selected = index >= 0 }
                            .clickable(onClickLabel = stringResource(R.string.camera_view)) { onOpen(pick) },
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
                                .clickable(enabled = enabled && !setBack, role = Role.Checkbox, onClickLabel = stringResource(R.string.camera_choose)) { onToggle(pick) }
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

/**
 * Two fingers pinched or spread over what scrolls under them: [onStep] with 1 each time they
 * close by a fifth, -1 each time they open by a quarter, the scroll left alone meanwhile.
 */
private fun Modifier.pinchSteps(onStep: (Int) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                if (zoom > 1.25f) {
                    onStep(-1)
                    zoom = 1f
                } else if (zoom < 0.8f) {
                    onStep(1)
                    zoom = 1f
                }
            }
        } while (event.changes.any { it.pressed })
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
