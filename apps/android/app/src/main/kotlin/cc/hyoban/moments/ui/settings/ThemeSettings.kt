package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.ui.design.AppSwitch
import cc.hyoban.moments.ui.theme.scheme
import cc.hyoban.moments.ui.theme.appTokens
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.graphics.RectangleShape
import cc.hyoban.moments.ui.design.HorizontalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.ColorScheme
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.ListItemShapes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ThemeMode
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill

// The look: light, dark or the system's, each shown as a small screen in its colours. The
// colours themselves are the design system's, the same for everyone.

/** The appearance group of the appearance page. */
@Composable
fun ThemeSettings(settings: Settings) {
    ThemeModeGroup(settings)
}

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
        trailingContent = { AppSwitch(checked, enabled) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        }.testTag(tag),
    ) { Text(title) }
}

@Composable
private fun ThemeModeGroup(settings: Settings) {
    val light = remember { appTokens(false).scheme() }
    val dark = remember { appTokens(true).scheme() }

    Caption(stringResource(R.string.section_theme_mode))
    Row(Modifier.padding(horizontal = Space.gutter).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        listOf(
            ThemeMode.SYSTEM to R.string.theme_mode_system,
            ThemeMode.LIGHT to R.string.theme_mode_light,
            ThemeMode.DARK to R.string.theme_mode_dark,
        ).forEach { (mode, label) ->
            val selected = settings.themeMode == mode
            Column(
                Modifier
                    .weight(1f)
                    .clip(AppTheme.shapes.large)
                    .background(SurfaceLevel.Card.fill())
                    .border(cardBorder(), AppTheme.shapes.large)
                    .selectable(selected, role = Role.RadioButton) { settings.changeThemeMode(mode) }
                    .testTag("theme_mode_${mode.name.lowercase()}")
                    .padding(Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                // The screen clipped to the frame's own shape and the frame drawn over its edge,
                // so the two meet all the way round, corners and all.
                val frame = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(AppTheme.shapes.medium)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) AppTheme.colorScheme.primary else AppTheme.colorScheme.outlineVariant, AppTheme.shapes.medium)
                when (mode) {
                    ThemeMode.SYSTEM -> Row(frame) {
                        MiniScreen(light, Modifier.weight(1f))
                        MiniScreen(dark, Modifier.weight(1f))
                    }
                    ThemeMode.LIGHT -> MiniScreen(light, frame)
                    ThemeMode.DARK -> MiniScreen(dark, frame)
                }
                Text(
                    stringResource(label),
                    style = AppTheme.typography.label,
                    color = if (selected) AppTheme.colorScheme.primary else AppTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A page in [scheme]'s colours, small: a title, two cards with a line of text and a chip in primary, the tabs at the foot. */
@Composable
private fun MiniScreen(scheme: ColorScheme, modifier: Modifier) {
    val page = SurfaceLevel.Page.color(scheme)
    val card = SurfaceLevel.Card.color(scheme)
    val floating = SurfaceLevel.Floating.color(scheme)
    val line = scheme.onSurface.copy(alpha = 0.18f)
    Column(modifier.fillMaxSize().background(page).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.padding(top = 4.dp).width(28.dp).height(5.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.6f)))
        repeat(2) {
            Column(
                Modifier.fillMaxWidth().clip(AppTheme.shapes.extraSmall).background(card).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(line))
                Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(CircleShape).background(line))
                Box(Modifier.width(16.dp).height(5.dp).clip(CircleShape).background(scheme.primary))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(floating).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
            Box(Modifier.size(6.dp).clip(CircleShape).background(line))
            Box(Modifier.size(6.dp).clip(CircleShape).background(line))
        }
    }
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
            color = AppTheme.colorScheme.onSurfaceVariant,
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
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurfaceVariant,
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
    leadingContentColor = AppTheme.colorScheme.onSurfaceVariant,
    trailingContentColor = AppTheme.colorScheme.onSurfaceVariant,
    supportingContentColor = AppTheme.colorScheme.onSurfaceVariant,
)

/** A row on a group's card: no fill of its own, its icon and secondary text in the secondary colour. */
@Composable
fun rowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    leadingIconColor = AppTheme.colorScheme.onSurfaceVariant,
    supportingColor = AppTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = AppTheme.colorScheme.onSurfaceVariant,
)
