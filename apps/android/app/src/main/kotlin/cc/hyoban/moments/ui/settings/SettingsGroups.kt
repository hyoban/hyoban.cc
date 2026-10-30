package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.ui.design.AppSwitch
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.graphics.RectangleShape
import cc.hyoban.moments.ui.design.HorizontalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

/** A row that is a switch, read as one with its name and state, and what it is doing under its name. */
@Composable
fun SwitchRow(
    checked: Boolean,
    icon: ImageVector,
    title: String,
    tag: String,
    shapes: ListItemShapes,
    supporting: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { AppSwitch(checked, enabled = enabled) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        }.testTag(tag),
    ) { Text(title) }
}

/** A group's small caption, lined up with the rows' text. */
@Composable
fun Caption(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.xl, bottom = if (action == null) Space.s else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

/** One segment of a group, drawn in the [ListItemShapes] its place in the group gives it. */
typealias Segment = @Composable (ListItemShapes) -> Unit

/** What isn't a row, a field, a picker, as a segment: on the group's surface. */
fun boxed(content: @Composable ColumnScope.() -> Unit): Segment = { _ ->
    Column(Modifier.fillMaxWidth(), content = content)
}

/** What is grouped in one card under a caption, with a note under them. */
@Composable
fun Group(title: String?, note: String?, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    SegmentedGroup(title, note, listOf(boxed(content)), action = action)

/**
 * Rows grouped under a caption: one surface with a hairline round it and between its rows, as
 * the design system's grouped list, and a note under it. A row is a [SegmentedListItem] in
 * [segmentColors], given square shapes here, the group's own corners clipping the first and
 * the last.
 */
@Composable
fun SegmentedGroup(
    title: String?,
    note: String?,
    segments: List<Segment>,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    if (title != null) Caption(title, action) else Spacer(Modifier.height(Space.xl))
    // What chooses what the rows show, filter chips say, between the caption and the rows, edge to edge.
    header?.invoke()
    Column(Modifier.padding(horizontal = Space.gutter)) {
        val shape = AppTheme.shapes.medium
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(SurfaceLevel.Card.fill())
                .border(cardBorder(), shape),
        ) {
            CompositionLocalProvider(LocalSurfaceLevel provides SurfaceLevel.Card) {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = Space.l), color = dividerColor())
                    segment(SquareRow)
                }
            }
        }
        note?.let {
            Text(
                it,
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s),
            )
        }
    }
}

/** A row's shapes in a group, square in every state: the group's corners round its ends. */
private val SquareRow = ListItemShapes(RectangleShape, RectangleShape, RectangleShape, RectangleShape, RectangleShape, RectangleShape)

/** A row of a group: no fill of its own, on the group's surface; its icons and secondary text in the secondary colour. */
@Composable
fun segmentColors() = ListItemDefaults.segmentedColors(
    containerColor = Color.Transparent,
    leadingContentColor = tokens.tx2,
    trailingContentColor = tokens.tx2,
    supportingContentColor = tokens.tx2,
)

/** A row on a group's card: no fill of its own, its icon and secondary text in the secondary colour. */
@Composable
fun rowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    leadingIconColor = tokens.tx2,
    supportingColor = tokens.tx2,
    trailingIconColor = tokens.tx2,
)
