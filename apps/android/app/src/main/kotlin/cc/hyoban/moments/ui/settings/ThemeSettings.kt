package cc.hyoban.moments.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material.icons.rounded.Close
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
import cc.hyoban.moments.ui.theme.ColorStyle
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.seededColorScheme
import cc.hyoban.moments.ui.theme.systemAccent

// The look, as FlClash's theme page offers it: light, dark or the system's, each shown as
// a small screen in its colours; a seed from a row of swatches, the system's accent among
// them, with colours of one's own added by their hex; how much of the seed the scheme
// carries; and dark's page in pure black.

/** The appearance groups at the top of the settings page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThemeSettings(settings: Settings) {
    val seed = Color(settings.themeColor ?: systemAccent() ?: Settings.DEFAULT_THEME_COLOR)
    ThemeModeGroup(settings, seed)
    ThemeColorGroup(settings)
    SegmentedGroup(
        null,
        stringResource(R.string.settings_pure_black_note),
        listOf { shapes ->
            SwitchRow(settings.pureBlack, Icons.Rounded.DarkMode, stringResource(R.string.setting_pure_black), "pure_black", shapes) {
                settings.changePureBlack(!settings.pureBlack)
            }
        },
    )
}

/** A row that is a switch, read as one with its name and state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchRow(checked: Boolean, icon: ImageVector, title: String, tag: String, shapes: ListItemShapes, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        // Its handle ticked or crossed, as Material 3 lets a switch's icon say which it is as
        // well as its colour.
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = {
                    Icon(
                        if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        }.testTag(tag),
    ) { Text(title) }
}

@Composable
private fun ThemeModeGroup(settings: Settings, seed: Color) {
    val style = settings.colorStyle
    val light = remember(seed, style) { seededColorScheme(seed, false, style) }
    val dark = remember(seed, style, settings.pureBlack) { seededColorScheme(seed, true, style, settings.pureBlack) }

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
                    .clip(MaterialTheme.shapes.large)
                    .background(LocalSurfaceLevel.current.raised.fill())
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
                    .clip(MaterialTheme.shapes.medium)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
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
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).background(card).padding(5.dp),
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeColorGroup(settings: Settings) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val accent = systemAccent()

    val swatches = boxed {
        // As many to a row as fit with at least a little between them, spread across the
        // card's width so each row's ends line up with the rows' text.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            val columns = ((maxWidth + MIN_GAP) / (SWATCH + MIN_GAP)).toInt().coerceAtLeast(1)
            // Whole pixels, rounded down, so rounding doesn't push a row's last swatch onto the next.
            val gap = with(LocalDensity.current) {
                if (columns > 1) ((constraints.maxWidth - SWATCH.roundToPx() * columns) / (columns - 1)).toDp() else 0.dp
            }
            FlowRow(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap.coerceAtMost(Space.s)),
            ) {
                if (accent != null) {
                    Swatch(Color(accent), settings.themeColor == null, system = true, onClick = { settings.changeThemeColor(null) })
                }
                Settings.THEME_COLORS.forEach { color ->
                    Swatch(Color(color), settings.themeColor == color, onClick = { settings.changeThemeColor(color) })
                }
                settings.customColors.forEach { color ->
                    Swatch(Color(color), settings.themeColor == color, onClick = { settings.changeThemeColor(color) }, onDelete = { settings.removeCustomColor(color) })
                }
                AddSwatch { adding = true }
            }
        }
    }
    val colorStyle = boxed {
        ListItem(
            headlineContent = { Text(stringResource(R.string.setting_color_style)) },
            leadingContent = { Icon(Icons.Rounded.Palette, contentDescription = null) },
            colors = rowColors(),
        )
        // Every style in sight, one chosen, as Material 3's connected button group.
        Row(
            Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, bottom = Space.l),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            val styles = ColorStyle.entries
            styles.forEachIndexed { index, style ->
                ToggleButton(
                    checked = style == settings.colorStyle,
                    onCheckedChange = { settings.changeColorStyle(style) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        styles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    // Those not chosen outlined, so each stands apart from the card at the 3:1 a group needs.
                    border = if (style == settings.colorStyle) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(
                        stringResource(styleLabel(style)),
                        maxLines = 1,
                        style = if (style == settings.colorStyle) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
    // The colours, and the style they are worked out in, each a segment of the group. A
    // colour of one's own, chosen, can be deleted from a button by the caption as well as
    // from its long press, as Material 3 keeps no action behind a long press alone.
    val custom = settings.themeColor?.takeIf { it in settings.customColors }
    SegmentedGroup(
        stringResource(R.string.section_theme_color),
        null,
        listOf(swatches, colorStyle),
        action = if (settings.themeIsDefault) null else {
            {
                Row {
                    if (custom != null) {
                        TextButton(onClick = { settings.removeCustomColor(custom) }, modifier = Modifier.testTag("delete_color")) {
                            Text(stringResource(R.string.action_delete_color))
                        }
                    }
                    TextButton(onClick = settings::resetTheme) { Text(stringResource(R.string.action_restore_default)) }
                }
            }
        },
    )

    if (adding) {
        HexColorDialog(onDismiss = { adding = false }) {
            settings.addCustomColor(it)
            adding = false
        }
    }
}

private fun styleLabel(style: ColorStyle) = when (style) {
    ColorStyle.VIVID -> R.string.color_style_vivid
    ColorStyle.TINTED -> R.string.color_style_tinted
    ColorStyle.SOFT -> R.string.color_style_soft
    ColorStyle.MONOCHROME -> R.string.color_style_monochrome
}

private val SWATCH = 44.dp
private val DOT = 32.dp
private val MIN_GAP = 6.dp

/**
 * A colour to pick: a dot, ringed in its own colour and checked when it's the one. The
 * system's accent has an eyedropper at its corner; a colour of one's own is removed from
 * a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(color: Color, selected: Boolean, system: Boolean = false, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val check by animateFloatAsState(if (selected) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "check")
    // Named by its hex, the system's accent by the eyedropper's own label; one of a group, chosen or not.
    val hex = "#%06X".format(color.toArgb() and 0xFFFFFF)
    Box(
        Modifier
            .size(SWATCH)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete?.let { { menu = true } },
                onLongClickLabel = onDelete?.let { stringResource(R.string.action_delete) },
                role = Role.RadioButton,
            )
            .semantics {
                this.selected = selected
                if (!system) contentDescription = hex
            }
            .testTag(if (system) "swatch_system" else "swatch_${hex.drop(1)}")
            // The ring comes and goes with the check, rather than in one frame.
            .border(2.5.dp, color.copy(alpha = color.alpha * check.coerceIn(0f, 1f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(DOT).clip(CircleShape).background(color))
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            modifier = Modifier.size(18.dp).scale(check),
        )
        if (system) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(LocalSurfaceLevel.current.raised.fill()).padding(2.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Colorize, contentDescription = stringResource(R.string.theme_color_system), modifier = Modifier.size(12.dp))
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    onDelete?.invoke()
                },
            )
        }
    }
}

@Composable
private fun AddSwatch(onClick: () -> Unit) {
    Box(Modifier.size(SWATCH).clip(CircleShape).clickable(onClick = onClick).testTag("swatch_add"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(DOT).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_add_color), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** `RRGGBB`, with or without its `#`, as ARGB; null when it isn't one. */
private fun parseHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6) return null
    return hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}

/** A colour of one's own, by its hex, shown beside the field as it is typed. */
@Composable
private fun HexColorDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val color = parseHex(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Contrast, contentDescription = null) },
        title = { Text(stringResource(R.string.action_add_color)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(7) },
                    placeholder = { Text("#1F7A5C") },
                    singleLine = true,
                    isError = text.isNotBlank() && color == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(DOT).clip(CircleShape)
                        .background(color?.let(::Color) ?: Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { color?.let(onAdd) }, enabled = color != null) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

/** One segment of a group, drawn in the [ListItemShapes] its place in the group gives it. */
typealias Segment = @Composable (ListItemShapes) -> Unit

/** What isn't a row, the swatches, a field, as a segment: on the group's fill, in its shape. */
fun boxed(content: @Composable ColumnScope.() -> Unit): Segment = { shapes ->
    Column(Modifier.fillMaxWidth().clip(shapes.shape).background(LocalSurfaceLevel.current.raised.fill()), content = content)
}

/** What is grouped in one card under a caption, with a note under them, as FlClash groups its settings. */
@Composable
fun Group(title: String?, note: String?, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    SegmentedGroup(title, note, listOf(boxed(content)), action = action)

/**
 * Rows grouped under a caption as Material 3's segmented list, a contained list being set
 * apart by gaps rather than lines: each of [segments] filled on its own a level up, a small
 * gap between, the group's outer corners round and its inner ones tight; a note under them.
 * A row is a [SegmentedListItem] in [segmentColors], its corners rounding under the finger.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedGroup(
    title: String?,
    note: String?,
    segments: List<Segment>,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    if (title != null) Caption(title, action) else Spacer(Modifier.height(Space.xl))
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            segments.forEachIndexed { index, segment -> segment(ListItemDefaults.segmentedShapes(index, segments.size)) }
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s),
            )
        }
    }
}

/** A row of a group: filled a level above the page, its icons and secondary text in the secondary colour. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun segmentColors() = ListItemDefaults.segmentedColors(
    containerColor = LocalSurfaceLevel.current.raised.fill(),
    leadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** A row on a group's card: no fill of its own, its icon and secondary text in the secondary colour. */
@Composable
fun rowColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
