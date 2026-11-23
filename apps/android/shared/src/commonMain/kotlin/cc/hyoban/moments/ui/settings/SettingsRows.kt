package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Space
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text

/**
 * A row of a settings group, one of the design's kinds (the SettingsGroup card): what a plugin
 * declares of its settings, which the group draws by its kind. What these can't draw opens the
 * plugin's own dialog or panel from a [Value] or [Action] row.
 */
sealed interface SettingsRow {
    val icon: ImageVector
    val title: String
    val tag: String?

    /** A switch at the row's end; a tap on the row turns it. */
    class Switch(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String,
        val checked: Boolean,
        val supporting: String? = null,
        val enabled: Boolean = true,
        val onToggle: () -> Unit,
    ) : SettingsRow

    /** What it is set to under its name; a tap opens a dialog to change it. */
    class Value(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val value: String?,
        val maxLines: Int = 1,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** Done on a tap (back up now, check for updates), what it is doing or did under its name, or at its end. */
    class Action(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val supporting: String? = null,
        val trailing: String? = null,
        val maxLines: Int = Int.MAX_VALUE,
        val enabled: Boolean = true,
        /** Under way: a spinner at its end in place of [trailing]. */
        val busy: Boolean = false,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** Which of [options] (by key, with its words) is chosen, under its name; a tap opens a menu of them. */
    class Choice(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val options: List<Pair<String, String>>,
        val chosen: String,
        val onChoose: (String) -> Unit,
    ) : SettingsRow

    /** Opens a page of its own. */
    class Page(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val supporting: String? = null,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** Opens the system's settings or another app. */
    class External(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val supporting: String? = null,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** Only says how something stands (the camera connected, a permission given): no tap. */
    class Status(
        override val icon: ImageVector,
        override val title: String,
        override val tag: String?,
        val supporting: String? = null,
        val trailing: String? = null,
        /** Under way: a spinner at its end in place of [trailing]. */
        val busy: Boolean = false,
    ) : SettingsRow
}

/** A settings group as declared: its caption, its rows and its note. */
class SettingsGroupSpec(val title: String?, val note: String?, val rows: List<SettingsRow>)

/** Draws [group], each row by its kind. */
@Composable
fun SettingsGroupView(group: SettingsGroupSpec) {
    if (group.rows.isEmpty()) return
    SegmentedGroup(group.title, group.note, group.rows.map { row -> { shapes -> SettingsRowView(row, shapes) } })
}

@Composable
private fun SettingsRowView(row: SettingsRow, shapes: cc.hyoban.moments.ui.design.ListItemShapes) {
    val tagged = row.tag?.let { Modifier.testTag(it) } ?: Modifier
    when (row) {
        is SettingsRow.Switch -> SwitchRow(row.checked, row.icon, row.title, row.tag, shapes, row.supporting, row.enabled, row.onToggle)
        is SettingsRow.Value -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            enabled = row.enabled,
            colors = segmentColors(),
            leadingContent = { Icon(row.icon, contentDescription = null) },
            supportingContent = row.value?.let { { Text(it, maxLines = row.maxLines, overflow = TextOverflow.Ellipsis) } },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.Action -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            enabled = row.enabled,
            colors = segmentColors(),
            leadingContent = { Icon(row.icon, contentDescription = null) },
            supportingContent = row.supporting?.let { { Text(it, maxLines = row.maxLines, overflow = TextOverflow.Ellipsis) } },
            trailingContent = when {
                row.busy -> { { cc.hyoban.moments.ui.design.Spinner(Modifier.size(24.dp)) } }
                else -> row.trailing?.let { { Text(it, style = AppTheme.typography.caption) } }
            },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.Choice -> {
            var open by remember { mutableStateOf(false) }
            Box {
                SegmentedListItem(
                    onClick = { open = true },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(row.icon, contentDescription = null) },
                    supportingContent = row.options.firstOrNull { it.first == row.chosen }?.let { (_, words) -> { Text(words) } },
                    trailingContent = { Icon(AppIcons.CaretDown, contentDescription = null) },
                    modifier = tagged,
                ) { Text(row.title) }
                cc.hyoban.moments.ui.design.DropdownMenu(open, onDismissRequest = { open = false }, offset = DpOffset(Space.l, 0.dp)) {
                    row.options.forEach { (key, words) ->
                        cc.hyoban.moments.ui.design.DropdownMenuItem(
                            text = { Text(words) },
                            onClick = { row.onChoose(key); open = false },
                            trailingIcon = if (row.chosen == key) {
                                { Icon(AppIcons.Check, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        is SettingsRow.Page -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(row.icon, contentDescription = null) },
            supportingContent = row.supporting?.let { { Text(it) } },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.External -> ActionRow(row.icon, row.title, shapes, row.supporting, row.tag, row.enabled, external = true, onClick = row.onClick)
        is SettingsRow.Status -> SegmentedListItem(
            onClick = {},
            shapes = shapes,
            enabled = true,
            colors = segmentColors(),
            leadingContent = { Icon(row.icon, contentDescription = null) },
            supportingContent = row.supporting?.let { { Text(it) } },
            trailingContent = when {
                row.busy -> { { cc.hyoban.moments.ui.design.Spinner(Modifier.size(24.dp)) } }
                else -> row.trailing?.let { { Text(it, style = AppTheme.typography.caption) } }
            },
            modifier = tagged,
        ) { Text(row.title) }
    }
}
