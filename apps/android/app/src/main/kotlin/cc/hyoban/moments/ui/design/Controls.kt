package cc.hyoban.moments.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens

// The design system's controls: capsules, the pressed grey for what can be chosen, the
// accent's soft tint for what is, the ink for the page's main action, text in the text colour,
// hairlines rather than outlines and tonal fills.

// ---------------------------------------------------------------- buttons

@Immutable
class ButtonColors(val containerColor: Color, val contentColor: Color)

object ButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    val ButtonWithIconContentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
    val TextButtonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    val IconSize = 18.dp
    val IconSpacing = 8.dp

    @Composable
    fun buttonColors(containerColor: Color = tokens.ink, contentColor: Color = tokens.onInk) = ButtonColors(containerColor, contentColor)

    @Composable
    fun textButtonColors(contentColor: Color = tokens.tx) = ButtonColors(Color.Transparent, contentColor)

    @Composable
    fun filledTonalButtonColors(containerColor: Color = tokens.fill, contentColor: Color = tokens.tx) = ButtonColors(containerColor, contentColor)
}

/** What every button is: [colors] in [shape], at least 40dp tall and 48dp to tap, its words in the label style. */
@Composable
private fun ButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    colors: ButtonColors,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val fill = if (enabled || colors.containerColor == Color.Transparent) colors.containerColor else tokens.fill
    val ink = if (enabled) colors.contentColor else tokens.tx3
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) { ProvideTextStyle(AppTheme.typography.label) { content() } }
    }
}

/** The page's main action: an ink capsule. At most one on a page. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = ButtonBase(onClick, modifier, enabled, Corners.full, colors, null, contentPadding, content)

/** A light action: the text colour alone, a capsule showing only when pressed; a dialog's buttons. */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) = ButtonBase(onClick, modifier, enabled, Corners.full, colors, null, ButtonDefaults.TextButtonContentPadding, content)

/** A secondary action set on the pressed grey: the way there, 新建, 校准, a key. */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Corners.full,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = ButtonBase(onClick, modifier, enabled, shape, colors, null, contentPadding, content)

// ---------------------------------------------------------------- floating buttons

/** A round floating button, 56dp, [containerColor] with [elevation]'s soft shadow. */
@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Corners.full,
    containerColor: Color = tokens.ink,
    contentColor: Color = tokens.onInk,
    elevation: Dp = Elevation.level2,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(56.dp)
            .softShadow(elevation, shape)
            .clip(shape)
            .background(containerColor)
            .clickable(interactionSource = interactionSource, indication = PressIndication, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides contentColor) { content() } }
}

/** A floating capsule with its icon and, while [expanded], its words. */
@Composable
fun ExtendedFloatingActionButton(
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    shape: Shape = Corners.full,
    containerColor: Color = tokens.ink,
    contentColor: Color = tokens.onInk,
    elevation: Dp = Elevation.level2,
) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 56.dp)
            .softShadow(elevation, shape)
            .clip(shape)
            .background(containerColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            icon()
            AnimatedVisibility(expanded, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row {
                    Spacer(Modifier.width(12.dp))
                    ProvideTextStyle(AppTheme.typography.label) { text() }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- chips

/** The chips' sizes. */
object ChipDefaults {
    val IconSize = 18.dp
    val Height = 36.dp

    /** How far in from a chip's edge its words start, without an icon: what a line over or under a row of chips lines up with. */
    val LabelInset = 14.dp
}

typealias FilterChipDefaults = ChipDefaults
typealias InputChipDefaults = ChipDefaults
typealias AssistChipDefaults = ChipDefaults

/**
 * A chip: a capsule [container], 36dp tall, its icons and label inside. It is laid out at the
 * height it is drawn, so rows of chips stand as far apart as the design has them; its 48dp target
 * still takes a tap round it, as Compose widens a target smaller than that.
 */
@Composable
private fun Chip(
    modifier: Modifier,
    container: Color,
    content: Color,
    iconColor: Color,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = ChipDefaults.Height)
            .clip(Corners.full)
            .background(container)
            .padding(start = if (leadingIcon != null) 10.dp else ChipDefaults.LabelInset, end = if (trailingIcon != null) 10.dp else ChipDefaults.LabelInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides iconColor) { leadingIcon() }
        CompositionLocalProvider(LocalContentColor provides content) { ProvideTextStyle(AppTheme.typography.label) { label() } }
        if (trailingIcon != null) CompositionLocalProvider(LocalContentColor provides tokens.tx2) { trailingIcon() }
    }
}

/** A chip to choose with: the pressed grey, the accent's soft tint once chosen. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = Chip(
    modifier.selectable(selected, enabled = enabled, role = Role.Checkbox, onClick = onClick),
    if (selected) tokens.accentSoft else tokens.fill,
    if (selected) tokens.tx else tokens.tx2,
    if (selected) tokens.accent else tokens.tx2,
    leadingIcon,
    trailingIcon,
    label,
)

/** A chip tapped for what it does: the pressed grey. */
@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = Chip(
    modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    tokens.fill,
    tokens.tx,
    tokens.tx2,
    leadingIcon,
    trailingIcon,
    label,
)

// ---------------------------------------------------------------- segmented control

/**
 * One of a few, side by side: a capsule track in the pressed grey, the chosen segment raised on
 * the surface with its hairline, its words in the text colour, the rest in the secondary.
 */
@Composable
fun SingleChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    SegmentTrack(modifier.selectableGroup(), content)

/** As [SingleChoiceSegmentedButtonRow], where more than one can be on. */
@Composable
fun MultiChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    SegmentTrack(modifier, content)

@Composable
private fun SegmentTrack(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        // A fixed height, so its segments fill it rather than whatever room the page has.
        modifier.height(44.dp).clip(Corners.full).background(tokens.fill).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A segment chosen as one of its row's. */
@Composable
fun RowScope.SegmentedButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) =
    Segment(selected, modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), label)

/** A segment turned on and off on its own. */
@Composable
fun RowScope.SegmentedButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable () -> Unit) =
    Segment(checked, modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange), label)

@Composable
private fun RowScope.Segment(on: Boolean, modifier: Modifier, label: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(Corners.full)
            .then(if (on) Modifier.background(tokens.surface).border(cardBorder(), Corners.full) else Modifier)
            .then(modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (on) tokens.tx else tokens.tx2) {
            ProvideTextStyle(AppTheme.typography.label) { label() }
        }
    }
}

// ---------------------------------------------------------------- progress

/** Something being waited for: a thin ring in [color], turning. */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = tokens.tx2) {
    val turning = rememberInfiniteTransition(label = "spinner")
    val angle by turning.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spinner angle")
    Canvas(modifier.padding(4.dp).size(24.dp)) {
        val stroke = 2.5.dp.toPx()
        drawArc(color, angle, 270f, useCenter = false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** [Spinner] on a small round surface of its own, for over a map or a photo. */
@Composable
fun ContainedSpinner(modifier: Modifier = Modifier) {
    Surface(modifier, shape = Corners.full, color = tokens.surface, contentColor = tokens.tx2, border = cardBorder()) {
        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { Spinner() }
    }
}

// ---------------------------------------------------------------- dialogs

/** A dialog on the surface, in the large corners: its title, its text, and its buttons at its end. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest, properties) {
        Surface(
            modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            shape = AppTheme.shapes.large,
            color = tokens.surface,
            contentColor = tokens.tx,
            border = cardBorder(),
        ) {
            Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                if (icon != null) {
                    CompositionLocalProvider(LocalContentColor provides tokens.tx2) { Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) { icon() } }
                }
                if (title != null) {
                    ProvideTextStyle(AppTheme.typography.title) { Box(Modifier.padding(bottom = 16.dp)) { title() } }
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides tokens.tx2) {
                        ProvideTextStyle(AppTheme.typography.body) { Box(Modifier.weight(1f, fill = false).padding(bottom = 16.dp)) { text() } }
                    }
                }
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- menus

/** What a menu shows: the surface with its hairline, in the medium corners, its items as wide as the widest. */
@Composable
fun MenuSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = AppTheme.shapes.medium, color = tokens.surface, contentColor = tokens.tx, border = cardBorder(), shadowElevation = Elevation.level2) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
    }
}

/** A menu in a popup [positionProvider] places, fading and growing in. */
@Composable
fun MenuPopup(expanded: Boolean, onDismissRequest: () -> Unit, positionProvider: PopupPositionProvider, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = expanded
    if (shown.currentState || shown.targetState) {
        Popup(popupPositionProvider = positionProvider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(shown, enter = fadeIn(tween(90)) + scaleIn(tween(120), initialScale = 0.92f), exit = fadeOut(tween(75))) {
                MenuSurface(content)
            }
        }
    }
}

/** A menu under what it is called in, at its start, [offset] from there; above it where there is no room below. */
@Composable
fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, offset: DpOffset = DpOffset(0.dp, 0.dp), content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val provider = remember(offset, density) {
        val x = with(density) { offset.x.roundToPx() }
        val y = with(density) { offset.y.roundToPx() }
        anchoredMenu({ anchor, _ -> listOf(anchor.left + x) }, { anchor, menu -> listOf(anchor.bottom + y, anchor.top - y - menu.height) })
    }
    MenuPopup(expanded, onDismissRequest, provider) { Column(modifier, content = content) }
}

/**
 * Where a menu goes: the first of [xs] and of [ys], given the anchor's bounds and the menu's
 * size, at which it fits the window, else the first moved in as far as it must.
 */
fun anchoredMenu(xs: (IntRect, IntSize) -> List<Int>, ys: (IntRect, IntSize) -> List<Int>): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 16
        fun pick(candidates: List<Int>, extent: Int, room: Int) =
            candidates.firstOrNull { it >= margin && it + extent <= room - margin } ?: candidates.first().coerceIn(margin, (room - extent - margin).coerceAtLeast(margin))
        return IntOffset(
            pick(xs(anchorBounds, popupContentSize), popupContentSize.width, windowSize.width),
            pick(ys(anchorBounds, popupContentSize), popupContentSize.height, windowSize.height),
        )
    }
}

/** A choice in a menu: its icon, its words, what follows them; 48dp tall. */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides tokens.tx2) { leadingIcon() }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) tokens.tx else tokens.tx3) { ProvideTextStyle(AppTheme.typography.body) { text() } }
        }
        if (trailingIcon != null) CompositionLocalProvider(LocalContentColor provides tokens.tx2) { trailingIcon() }
    }
}
