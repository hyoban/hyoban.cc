package cc.hyoban.moments.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/** The page every screen is laid on: Material 3's surface. */
object AppColors {
    val page: Color
        @Composable get() = SurfaceLevel.Page.fill()

    /** A sheet over the page: a level up from it, in light and dark alike. */
    val sheetLevel: SurfaceLevel = SurfaceLevel.Card
}

/**
 * A card one level above what it sits on, set apart by its fill rather than a border or a
 * shadow; what is inside it is a level above it in turn. Pressed anywhere, its photos too,
 * it shows Material 3's pressed state layer, a ripple, without moving. With [onLongClick], a
 * long press, or a mouse's right click, opens what more it offers, named [onLongClickLabel];
 * [onLongClick] is told where, from the card's bottom start, as a menu over it is offset.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Offset) -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val level = LocalSurfaceLevel.current.raised
    // Where the finger or the pointer last went down, for what a long press opens.
    val down = remember { floatArrayOf(0f, 0f) }
    val currentLongClick by rememberUpdatedState(onLongClick)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = level.fill(), contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
    ) {
        // Tapped inside the card's shape, so the ripple is clipped to it and lies over its photos.
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) return@awaitEachGesture
                        event.changes.firstOrNull()?.position?.let { down[0] = it.x; down[1] = it.y - size.height }
                        val open = currentLongClick
                        if (open != null && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            open(Offset(down[0], down[1]))
                        }
                    }
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = onClick,
                    onLongClick = onLongClick?.let { open -> { open(Offset(down[0], down[1])) } },
                    onLongClickLabel = onLongClickLabel,
                ),
        ) {
            CompositionLocalProvider(LocalSurfaceLevel provides level) { content() }
        }
    }
}

/**
 * A label on what it sits on, one level above it: a time, a place, a trip, a count,
 * where the card around it is what's tapped. Neutral, as colour here is kept for state.
 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(Corners.full).background(LocalSurfaceLevel.current.raised.fill())
            .heightIn(min = 28.dp).padding(start = if (icon != null) Space.s else Space.m, end = Space.m, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A chip tapped on its own, an [AssistChip] filled a level above what it sits on and
 * without a border: a place to open. 48dp to tap, as every Material chip is.
 */
@Composable
fun NeutralChip(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = Corners.full,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = LocalSurfaceLevel.current.raised.fill(),
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    )
}

/** A card's section: a small icon and its name over what follows, both in the secondary text colour. */
@Composable
fun SectionHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** A month's start in a long list: the month large, its year beside it. */
@Composable
fun MonthHeader(month: String, year: String?) {
    Row(
        // Read as one heading, the month and its year together.
        Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(month, style = MaterialTheme.typography.headlineSmall)
        year?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.xxs))
        }
    }
}

/** A day over its Moments, floating over them as they scroll: the floating level, with a shadow. */
@Composable
fun DayPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).semantics(mergeDescendants = true) { heading() }) {
        Surface(
            shape = Corners.full,
            color = SurfaceLevel.Floating.fill(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = Elevation.level2,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                // At least its own height, taller with larger text rather than cutting it.
                modifier = Modifier.heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Space.l, vertical = Space.xs),
            )
        }
    }
}
