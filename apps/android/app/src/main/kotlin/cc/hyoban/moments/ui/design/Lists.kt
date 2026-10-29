package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.ui.theme.tokens

// A row of a list: what leads it (an icon, a photo), its words (a line over them, the line
// itself, what it holds under them), and what follows them (a value, a switch, an arrow).

@Immutable
class ListItemColors(
    val containerColor: Color,
    val headlineColor: Color,
    val leadingColor: Color,
    val overlineColor: Color,
    val supportingColor: Color,
    val trailingColor: Color,
)

/** A row's shape in each state; the app gives every state the same. */
@Immutable
class ListItemShapes(
    val shape: Shape,
    val selectedShape: Shape = shape,
    val pressedShape: Shape = shape,
    val focusedShape: Shape = shape,
    val hoveredShape: Shape = shape,
    val draggedShape: Shape = shape,
)

object ListItemDefaults {
    @Composable
    fun colors(
        containerColor: Color = Color.Transparent,
        headlineColor: Color = tokens.tx,
        leadingIconColor: Color = tokens.tx2,
        overlineColor: Color = tokens.tx2,
        supportingColor: Color = tokens.tx2,
        trailingIconColor: Color = tokens.tx2,
    ) = ListItemColors(containerColor, headlineColor, leadingIconColor, overlineColor, supportingColor, trailingIconColor)

    @Composable
    fun segmentedColors(
        containerColor: Color = Color.Transparent,
        contentColor: Color = tokens.tx,
        leadingContentColor: Color = tokens.tx2,
        trailingContentColor: Color = tokens.tx2,
        supportingContentColor: Color = tokens.tx2,
        overlineContentColor: Color = tokens.tx2,
    ) = ListItemColors(containerColor, contentColor, leadingContentColor, overlineContentColor, supportingContentColor, trailingContentColor)

    /** A row on its own, square. */
    val Shapes = ListItemShapes(RectangleShape)
}

@Composable
private fun RowContent(
    modifier: Modifier,
    colors: ListItemColors,
    leadingContent: (@Composable () -> Unit)?,
    overlineContent: (@Composable () -> Unit)?,
    supportingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    headlineContent: @Composable () -> Unit,
) {
    val typography = AppTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (supportingContent != null || overlineContent != null) 64.dp else 56.dp)
            .background(colors.containerColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leadingContent != null) CompositionLocalProvider(LocalContentColor provides colors.leadingColor) { leadingContent() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (overlineContent != null) {
                CompositionLocalProvider(LocalContentColor provides colors.overlineColor) { ProvideTextStyle(typography.caption) { overlineContent() } }
            }
            CompositionLocalProvider(LocalContentColor provides colors.headlineColor) { ProvideTextStyle(typography.body) { headlineContent() } }
            if (supportingContent != null) {
                CompositionLocalProvider(LocalContentColor provides colors.supportingColor) {
                    ProvideTextStyle(typography.body.copy(fontSize = 13.sp, lineHeight = 20.sp)) { supportingContent() }
                }
            }
        }
        if (trailingContent != null) {
            CompositionLocalProvider(LocalContentColor provides colors.trailingColor) {
                ProvideTextStyle(typography.caption) { Box(contentAlignment = Alignment.CenterEnd) { trailingContent() } }
            }
        }
    }
}

/** A row of a list, not tapped itself; what is around it decides that. */
@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) = RowContent(modifier, colors, leadingContent, overlineContent, supportingContent, trailingContent, headlineContent)

/** A row of a group, tapped as a whole, in [shapes]; [selected] puts it on the accent's soft tint. */
@Composable
fun SegmentedListItem(
    onClick: () -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shown = if (enabled) colors else ListItemColors(colors.containerColor, tokens.tx3, tokens.tx3, tokens.tx3, tokens.tx3, tokens.tx3)
    RowContent(
        modifier
            .clip(shapes.shape)
            .then(if (selected) Modifier.background(tokens.accentSoft) else Modifier)
            .semantics { this.selected = selected }
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        shown,
        leadingContent,
        overlineContent,
        supportingContent,
        trailingContent,
        content,
    )
}
