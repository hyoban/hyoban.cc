package cc.hyoban.moments.ui.design

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens

// The smallest pieces everything else is made of: text, icons, surfaces, buttons that are an
// icon alone, lines, marks and progress, each taking its colour and style from where it sits.

// ---------------------------------------------------------------- text and icons

/** Text in the style around it, its colour that of what it sits on unless [color] says. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    autoSize: TextAutoSize? = null,
) = BasicText(
    text,
    modifier,
    resolve(style, color, fontSize, fontWeight, textAlign, textDecoration),
    onTextLayout,
    overflow,
    softWrap,
    maxLines,
    minLines,
    autoSize = autoSize,
)

/** [Text] for styled text, with what is set inline in it. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = BasicText(
    text,
    modifier,
    resolve(style, color, fontSize, fontWeight, textAlign, textDecoration),
    onTextLayout,
    overflow,
    softWrap,
    maxLines,
    minLines,
    inlineContent,
)

@Composable
private fun resolve(style: TextStyle, color: Color, fontSize: TextUnit, fontWeight: FontWeight?, textAlign: TextAlign?, textDecoration: TextDecoration?): TextStyle {
    val shown = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    return style.merge(TextStyle(color = shown, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign ?: TextAlign.Unspecified, textDecoration = textDecoration))
}

/** An icon, 24dp unless sized, in the colour of what it sits on unless [tint] says; named for a screen reader by [contentDescription]. */
@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(rememberVectorPainter(imageVector), contentDescription, modifier, tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val filter = remember(tint) { if (tint.isSpecified) ColorFilter.tint(tint) else null }
    val named = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Box(modifier.size(24.dp).paint(painter, colorFilter = filter, contentScale = ContentScale.Fit).then(named))
}

// ---------------------------------------------------------------- surfaces

/**
 * What is tapped is at least 48dp each way, however small it is drawn: the 48dp is kept in the
 * layout, what follows is drawn at its own size in its middle, and a tap anywhere in it reaches
 * it, as Compose widens a smaller target to the touch-target size.
 */
fun Modifier.minimumInteractiveComponentSize(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val target = MinimumTarget.roundToPx()
    val width = maxOf(placeable.width, target)
    val height = maxOf(placeable.height, target)
    layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
}

private val MinimumTarget = 48.dp

/**
 * A surface: [color] in [shape], with an edge and a shadow if given, its content in
 * [contentColor]; it takes the taps on it rather than passing them through.
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = tokens.bg,
    contentColor: Color = tokens.tx,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .surface(shape, color, border, shadowElevation)
            .semantics(mergeDescendants = false) { isTraversalGroup = true }
            .pointerInput(Unit) {},
        propagateMinConstraints = true,
    ) { CompositionLocalProvider(LocalContentColor provides contentColor) { content() } }
}

/** A [Surface] tapped as a whole. */
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = tokens.bg,
    contentColor: Color = tokens.tx,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .surface(shape, color, border, shadowElevation)
                .clickable(interactionSource = interactionSource, indication = PressIndication, enabled = enabled, onClick = onClick),
            propagateMinConstraints = true,
        ) { content() }
    }
}

/** A [Surface] that is a switch, turned on and off as a whole. */
@Composable
fun Surface(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = tokens.bg,
    contentColor: Color = tokens.tx,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .surface(shape, color, border, shadowElevation)
                .toggleable(checked, interactionSource = null, indication = PressIndication, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
            propagateMinConstraints = true,
        ) { content() }
    }
}

/**
 * A soft shadow under what floats, as light from above throws it: blurred wide, a little below
 * it and faint, rather than the platform's even ring round its edge. None at 0.
 */
fun Modifier.softShadow(elevation: Dp, shape: Shape): Modifier =
    if (elevation <= 0.dp) this else dropShadow(shape, Shadow(radius = elevation * 6, color = Color.Black, offset = DpOffset(0.dp, elevation * 1.5f), alpha = 0.1f))

private fun Modifier.surface(shape: Shape, color: Color, border: BorderStroke?, shadow: Dp): Modifier =
    softShadow(shadow, shape)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .background(color, shape)
        .clip(shape)

/** A card: a [Surface] whose content is a column, tapped as a whole with [onClick]. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    colors: CardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    elevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inner: @Composable () -> Unit = { Column(content = content) }
    if (onClick != null) {
        Surface(onClick, modifier, shape = shape, color = colors.containerColor, contentColor = colors.contentColor, border = border, shadowElevation = elevation, content = inner)
    } else {
        Surface(modifier, shape, colors.containerColor, colors.contentColor, border, elevation, inner)
    }
}

@Immutable
class CardColors(val containerColor: Color, val contentColor: Color)

object CardDefaults {
    @Composable
    fun cardColors(containerColor: Color = tokens.surface, contentColor: Color = tokens.tx) = CardColors(containerColor, contentColor)

    fun cardElevation(defaultElevation: Dp = 0.dp): Dp = defaultElevation
}

// ---------------------------------------------------------------- icon buttons

/** A button that is an icon alone: 48dp round, the icon in the colour around it, faded when not [enabled]. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val color = LocalContentColor.current
    if (LocalFloatingIconButtons.current) {
        // Floating over the page, as a page's buttons do now it has no bar: a 40 circle on the
        // floating surface, its hairline and soft shadow, its touch target still 48.
        Box(modifier.minimumInteractiveComponentSize().size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp)
                    .surface(CircleShape, cc.hyoban.moments.ui.theme.SurfaceLevel.Floating.color(tokens), cc.hyoban.moments.ui.theme.cardBorder(), cc.hyoban.moments.ui.theme.Elevation.level2)
                    .clickable(interactionSource = interactionSource, indication = PressIndication, enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { CompositionLocalProvider(LocalContentColor provides if (enabled) tokens.tx else tokens.tx.copy(alpha = DISABLED)) { content() } }
        }
        return
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interactionSource, indication = PressIndication, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides if (enabled) color else color.copy(alpha = DISABLED)) { content() } }
}

/** Whether the icon buttons inside float as circles over the page, as the top bar's do. */
val LocalFloatingIconButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

@Immutable
class IconButtonColors(val containerColor: Color, val contentColor: Color)

object IconButtonDefaults {
    @Composable
    fun filledIconButtonColors(containerColor: Color = tokens.ink, contentColor: Color = tokens.onInk) = IconButtonColors(containerColor, contentColor)

    enum class IconButtonWidthOption { Narrow, Uniform, Wide }

    /** A small icon button's container, wider for the [widthOption] that stands out. */
    fun smallContainerSize(widthOption: IconButtonWidthOption = IconButtonWidthOption.Uniform): DpSize = when (widthOption) {
        IconButtonWidthOption.Narrow -> DpSize(28.dp, 40.dp)
        IconButtonWidthOption.Uniform -> DpSize(40.dp, 40.dp)
        IconButtonWidthOption.Wide -> DpSize(52.dp, 40.dp)
    }
}

/** An icon button on a fill of its own: the ink, for the one main action. */
@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) colors.containerColor else colors.containerColor.copy(alpha = 0.12f))
            .clickable(interactionSource = interactionSource, indication = PressIndication, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides colors.contentColor) { content() } }
}

// ---------------------------------------------------------------- lines and marks

/** A 1px line across. */
@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = tokens.border) =
    Canvas(modifier.fillMaxWidth().height(thickness)) { drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height) }

/** A 1px line down. */
@Composable
fun VerticalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = tokens.border) =
    Canvas(modifier.fillMaxHeight().width(thickness)) { drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), size.width) }

/** A dot in [containerColor], or a small capsule holding [content] when there is some. */
@Composable
fun Badge(modifier: Modifier = Modifier, containerColor: Color = tokens.accent, contentColor: Color = tokens.onAccent, content: (@Composable () -> Unit)? = null) {
    if (content == null) {
        Box(modifier.size(8.dp).clip(CircleShape).background(containerColor))
    } else {
        Box(modifier.sizeIn(minWidth = 16.dp, minHeight = 16.dp).clip(CircleShape).background(containerColor), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { ProvideTextStyle(AppTheme.typography.micro) { content() } }
        }
    }
}

/** [content] with [badge] at its top end. */
@Composable
fun BadgedBox(badge: @Composable BoxScope.() -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)) { badge() }
    }
}

/** A choice of one: a ring in the control border, filled with the accent once chosen. */
@Composable
fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ring by animateColorAsState(if (selected) tokens.accent else tokens.borderStrong, AppTheme.motionScheme.fastEffectsSpec(), label = "radio")
    val tap = if (onClick != null) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick) else Modifier
    Box(modifier.then(tap).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(ring, radius = size.minDimension / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (selected) drawCircle(ring, radius = 5.dp.toPx())
        }
    }
}

/** A choice that is on or off: a rounded square in the control border, filled with the accent and ticked once on. */
@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fill by animateColorAsState(if (checked) tokens.accent else Color.Transparent, AppTheme.motionScheme.fastEffectsSpec(), label = "checkbox")
    val edge = if (checked) tokens.accent else tokens.borderStrong
    val tick = tokens.onAccent
    val tap = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier
    Box(modifier.then(tap).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            drawRoundRect(fill, cornerRadius = corner)
            drawRoundRect(edge, cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (checked) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.72f)
                    lineTo(size.width * 0.78f, size.height * 0.32f)
                }
                drawPath(path, tick, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

// ---------------------------------------------------------------- progress

object ProgressIndicatorDefaults {
    val ProgressAnimationSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 50f, visibilityThreshold = 1 / 1000f)
}

/** How far something has got: a thin line in [color] over [trackColor], round at its ends. */
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = tokens.accent,
    trackColor: Color = tokens.fill,
    strokeCap: StrokeCap = StrokeCap.Round,
    gapSize: Dp = 0.dp,
    drawStopIndicator: () -> Unit = {},
) {
    Canvas(modifier.fillMaxWidth().height(4.dp).semantics { }) {
        val y = size.height / 2
        val line = size.height
        val inset = if (strokeCap == StrokeCap.Butt) 0f else line / 2
        val end = inset + (size.width - 2 * inset) * progress().coerceIn(0f, 1f)
        drawLine(trackColor, Offset(inset, y), Offset(size.width - inset, y), line, strokeCap)
        if (end > inset) drawLine(color, Offset(inset, y), Offset(end, y), line, strokeCap)
    }
}

private const val DISABLED = 0.38f
