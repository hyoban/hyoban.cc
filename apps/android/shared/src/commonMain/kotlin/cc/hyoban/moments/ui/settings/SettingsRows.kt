package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
    /** Every row in a group has one; a panel's list of options may go without. */
    val icon: ImageVector?
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
        override val icon: ImageVector?,
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
        /** Destructive (deleting a book): its icon and name in the danger colour, asked about in a dialog first. */
        val danger: Boolean = false,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** One of a panel's options that exclude each other, a radio at its end; no icon. */
    class Radio(
        override val title: String,
        override val tag: String?,
        val chosen: Boolean,
        val onClick: () -> Unit,
    ) : SettingsRow {
        override val icon: ImageVector? get() = null
    }

    /** A few lines only read (what the side key did last): [headline] in body, [lines] under it in caption. */
    class Lines(
        val headline: String,
        val lines: List<String>,
    ) : SettingsRow {
        override val icon: ImageVector? get() = null
        override val title: String get() = headline
        override val tag: String? get() = null
    }

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
        /** One the list can lose (a home): a close button at its end, read as [removeLabel]. */
        val onRemove: (() -> Unit)? = null,
        val removeLabel: String? = null,
        val removeTag: String? = null,
    ) : SettingsRow
}

/** Whether rows are drawn on the page itself (我) rather than in a group's card: a page row's chevron is then in the third grey. */
private val LocalOnPage = androidx.compose.runtime.staticCompositionLocalOf { false }

/** [rows] on the page itself, as 我 lists them: space between them rather than a card and lines. */
@Composable
fun SettingsRowsOnPage(rows: List<SettingsRow>, modifier: Modifier = Modifier) {
    androidx.compose.runtime.CompositionLocalProvider(LocalOnPage provides true) {
        PageRows(modifier) { shapes -> rows.forEach { SettingsRowView(it, shapes) } }
    }
}

/** A settings group as declared: its caption, its rows and its note. */
class SettingsGroupSpec(val title: String?, val note: String?, val rows: List<SettingsRow>)

/** Draws [group], each row by its kind. */
@Composable
fun SettingsGroupView(group: SettingsGroupSpec) {
    if (group.rows.isEmpty()) {
        // No rows yet (no albums): its caption and note, no empty card.
        if (group.title == null && group.note == null) return
        if (group.title != null) Caption(group.title) else androidx.compose.foundation.layout.Spacer(Modifier.size(Space.xl))
        group.note?.let {
            Text(
                it,
                style = AppTheme.typography.caption,
                color = cc.hyoban.moments.ui.theme.tokens.tx2,
                modifier = Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter + Space.l, top = Space.s),
            )
        }
        return
    }
    SegmentedGroup(group.title, group.note, group.rows.map { row -> { shapes -> SettingsRowView(row, shapes) } })
}

@Composable
internal fun SettingsRowView(row: SettingsRow, shapes: cc.hyoban.moments.ui.design.ListItemShapes) {
    val tagged = row.tag?.let { Modifier.testTag(it) } ?: Modifier
    when (row) {
        is SettingsRow.Switch -> SwitchRow(row.checked, row.icon, row.title, row.tag, shapes, row.supporting, row.enabled, row.onToggle)
        is SettingsRow.Value -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            enabled = row.enabled,
            colors = segmentColors(),
            leadingContent = row.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
            supportingContent = row.value?.let { { Text(it, maxLines = row.maxLines, overflow = TextOverflow.Ellipsis) } },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.Action -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            enabled = row.enabled,
            colors = segmentColors(),
            leadingContent = if (row.danger) {
                { Icon(row.icon, contentDescription = null, tint = cc.hyoban.moments.ui.theme.tokens.danger) }
            } else {
                { Icon(row.icon, contentDescription = null) }
            },
            supportingContent = row.supporting?.let { { Text(it, maxLines = row.maxLines, overflow = TextOverflow.Ellipsis) } },
            trailingContent = when {
                row.busy -> { { cc.hyoban.moments.ui.design.Spinner(Modifier.size(24.dp)) } }
                else -> row.trailing?.let { { Text(it, style = AppTheme.typography.caption) } }
            },
            modifier = tagged,
        ) { if (row.danger) Text(row.title, color = cc.hyoban.moments.ui.theme.tokens.danger) else Text(row.title) }
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
            trailingContent = {
                if (LocalOnPage.current) {
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = cc.hyoban.moments.ui.theme.tokens.tx3)
                } else {
                    Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            },
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
                row.onRemove != null -> { {
                    cc.hyoban.moments.ui.design.IconButton(onClick = row.onRemove, modifier = row.removeTag?.let { Modifier.testTag(it) } ?: Modifier) {
                        Icon(AppIcons.Close, contentDescription = row.removeLabel, tint = cc.hyoban.moments.ui.theme.tokens.tx2, modifier = Modifier.size(20.dp))
                    }
                } }
                else -> row.trailing?.let { { Text(it, style = AppTheme.typography.caption) } }
            },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.Radio -> SegmentedListItem(
            onClick = row.onClick,
            shapes = shapes,
            colors = segmentColors(),
            trailingContent = { cc.hyoban.moments.ui.design.RadioButton(row.chosen, onClick = null) },
            modifier = tagged,
        ) { Text(row.title) }
        is SettingsRow.Lines -> androidx.compose.foundation.layout.Column(
            Modifier.padding(Space.l),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s),
        ) {
            Text(row.headline, style = AppTheme.typography.body)
            row.lines.forEach { Text(it, style = AppTheme.typography.caption, color = cc.hyoban.moments.ui.theme.tokens.tx2) }
        }
    }
}
