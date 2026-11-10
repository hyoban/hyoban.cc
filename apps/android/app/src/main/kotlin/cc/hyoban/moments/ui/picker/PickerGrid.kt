package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.LocalDate

/** The photos of what is listed by the day each was taken, newest first, each chosen by its circle and opened by a tap. */
@Composable
internal fun Grid(
    shown: List<Pick>,
    order: Map<String, Int>,
    full: Boolean,
    enabled: Boolean,
    columns: Int,
    limited: (@Composable () -> Unit)?,
    onToggle: (Pick) -> Unit,
    onNearEnd: (() -> Unit)? = null,
    onOpen: (Pick) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val days = remember(shown) { shown.groupBy { it.day }.toList() }
    val state = rememberLazyGridState()
    // More of a source read a page at a time, as the grid nears the end of what is read.
    val nearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) to it.totalItemsCount } }
            .collect { (last, total) -> if (total > 0 && last >= total - 40) nearEnd?.invoke() }
    }
    LazyVerticalGrid(
        GridCells.Fixed(columns),
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
        days.forEach { (day, picks) ->
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when (day) { null -> stringResource(R.string.camera_no_date); today -> stringResource(R.string.camera_today); else -> formatDay(day.toString(), today) },
                    style = AppTheme.typography.caption, color = tokens.tx2,
                    modifier = Modifier.padding(start = Space.gutter, top = Space.l, bottom = Space.xs),
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
                    Modifier.aspectRatio(1f).background(tokens.fill)
                        .then(if (index >= 0) Modifier.border(3.dp, tokens.accent) else Modifier)
                        .semantics { contentDescription = label; selected = index >= 0 }
                        .clickable(onClickLabel = stringResource(R.string.camera_view)) { onOpen(pick) },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (setBack) 0.38f else 1f)) }
                    corner?.let {
                        Text(it, style = AppTheme.typography.caption.strong, color = MediaOverlay.content, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                    // The circle chooses it, its target the corner's 48dp.
                    if (pick.choosable) Box(
                        Modifier.align(Alignment.TopEnd).size(48.dp)
                            .clickable(enabled = enabled && !setBack, role = Role.Checkbox, onClickLabel = stringResource(R.string.camera_choose)) { onToggle(pick) }
                            .testTag("picker_choose_${pick.key}"),
                        contentAlignment = Alignment.TopEnd,
                    ) { ChoiceMark(index, Modifier.padding(6.dp).size(22.dp).alpha(if (setBack) 0.38f else 1f)) }
                }
            }
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
