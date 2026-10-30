package cc.hyoban.moments.ui.components

import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.AssistChipDefaults
import cc.hyoban.moments.ui.design.Card
import cc.hyoban.moments.ui.design.CardDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.PressIndication
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/** The page every screen is laid on. */
object AppColors {
    val page: Color
        @Composable get() = SurfaceLevel.Page.fill()

    /** What is in a sheet over the page sits as on a card: a level up from it, in light and dark alike. */
    val sheetLevel: SurfaceLevel = SurfaceLevel.Card

    /** A sheet's own fill, bottom or side: the surface. */
    val sheet: Color
        @Composable get() = tokens.surface
}

/**
 * A card one level above what it sits on, in the large corners, set apart by its hairline and
 * its fill, never a shadow; what is inside it is a level above it in turn. Pressed anywhere,
 * its photos too, it shows a ripple, without moving. With [onLongClick], a
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
    /** On the page itself, no fill or hairline of its own: an item of a list rather than a card. */
    flat: Boolean = false,
    /** Its corners, what it clips its content and its press to. */
    shape: androidx.compose.ui.graphics.Shape = AppTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val level = if (flat) LocalSurfaceLevel.current else LocalSurfaceLevel.current.raised
    // Where the finger or the pointer last went down, for what a long press opens.
    val down = remember { floatArrayOf(0f, 0f) }
    val currentLongClick by rememberUpdatedState(onLongClick)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (flat) Color.Transparent else level.fill(), contentColor = tokens.tx),
        border = if (flat) null else cardBorder(),
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
                    indication = PressIndication,
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
        icon?.let { Icon(it, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(16.dp)) }
        Text(text, style = AppTheme.typography.label, color = tokens.tx2, maxLines = 1)
    }
}

/**
 * A chip tapped on its own, an [AssistChip] filled a level above what it sits on and
 * without a border: a place to open. A capsule, 48dp to tap.
 */
@Composable
fun NeutralChip(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = modifier,
    )
}

/** A card's section: a small icon and its name over what follows, both in the secondary text colour. */
@Composable
fun SectionHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(18.dp))
        Text(title, style = AppTheme.typography.titleSmall, color = tokens.tx2, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** A month's start in a long list: the month as a title, its year small beside it. */
@Composable
fun MonthHeader(month: String, year: String?) {
    Row(
        // Read as one heading, the month and its year together.
        Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(month, style = AppTheme.typography.title)
        year?.let {
            Text(it, style = AppTheme.typography.bodySmall, color = tokens.tx2, modifier = Modifier.padding(bottom = Space.xxs))
        }
    }
}

/**
 * A day over its Moments, floating over them as they scroll: the surface with its hairline and a
 * shadow.
 * With [onClick], tapped it opens the day, growing into its page from [date]'s card.
 */
@Composable
fun DayPill(text: String, date: String? = null, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).semantics(mergeDescendants = true) { heading() }) {
        val open = if (onClick != null && date != null) {
            Modifier.sharedCard(dayCardKey(date), cardCorner = PILL_CORNER, relayout = true).clip(PillShape)
                .clickable(onClickLabel = stringResource(R.string.label_whole_day), onClick = onClick).testTag("day_pill_$date")
        } else {
            Modifier
        }
        Surface(
            shape = PillShape,
            color = SurfaceLevel.Floating.fill(),
            contentColor = tokens.tx,
            border = cardBorder(),
            shadowElevation = Elevation.level1,
            modifier = open,
        ) {
            Text(
                text,
                style = AppTheme.typography.label,
                // One line, so springing past its width as it shrinks back from the day's page
                // doesn't wrap its last word away for a moment.
                softWrap = false,
                // At least its own height, taller with larger text rather than cutting it.
                modifier = Modifier.heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Space.l, vertical = Space.xs),
            )
        }
    }
}

/** How round a day's pill is at most: fully at its own height, a rounded page as it grows into one. */
private val PILL_CORNER = 32.dp

/** A pill's shape, round at its ends, but no rounder than [PILL_CORNER], so grown it is no oval. */
private val PillShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        RoundedCornerShape(minOf(size.height / 2, with(density) { PILL_CORNER.toPx() })).createOutline(size, layoutDirection, density)
}
