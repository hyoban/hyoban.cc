package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.launch

// The design system's theme: the colour roles, the type scale, the corners and the springs the
// app is drawn with, read through [AppTheme] as a page reads them, and the colour and text
// style what is drawn inside something takes from it. The app's own, with no Material under it.

/**
 * The design system's 13 text styles, as it names them: hierarchy by weight and colour more
 * than size. Figures (amounts, times, numeric dates) take the `num` styles, in the system's
 * monospace; [emphasized] is any style a weight heavier, never a size larger.
 */
@Immutable
class Typography(
    /** A page's large title, a tab's and a sub-page's alike. */
    val titlePage: TextStyle,
    /** A collapsed bar's title, a sheet's and a dialog's. */
    val title: TextStyle,
    /** A group's or a section's heading. */
    val titleSmall: TextStyle,
    /** A Moment's words, and the default. */
    val bodyReading: TextStyle,
    /** The interface's words: rows, fields, menus, dialogs. */
    val body: TextStyle,
    /** Supporting lines and notes. */
    val bodySmall: TextStyle,
    /** Buttons, chips, the segmented control. */
    val label: TextStyle,
    /** What tells about something: a place, a weekday, a field's name. */
    val caption: TextStyle,
    /** Badges and legends. */
    val micro: TextStyle,
    /** Amounts and big figures. */
    val numDisplay: TextStyle,
    /** A day in its header row, a figure in a stat. */
    val numTitle: TextStyle,
    /** An amount at a row's end. */
    val numBody: TextStyle,
    /** Times, counts and numeric dates. */
    val numCaption: TextStyle,
)

/** The same style a weight heavier, for emphasis. */
val TextStyle.emphasized: TextStyle
    get() = copy(fontWeight = FontWeight((fontWeight ?: FontWeight.Normal).weight + 100))

/** The corner sizes: small for marks, medium for fields and groups, large for photos, cards and sheets. */
@Immutable
class Shapes(
    val small: CornerBasedShape,
    val medium: CornerBasedShape,
    val large: CornerBasedShape,
)

/**
 * The springs everything moves on, none of them overshooting: spatial ones for what moves or
 * changes size, effects ones for colour and fades, each fast, default or slow.
 */
@Immutable
class MotionScheme {
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 1400f)
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 700f)
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(0.9f, 300f)
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 3800f)
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 1600f)
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 800f)

    companion object {
        fun standard(): MotionScheme = MotionScheme()
    }
}

private val LocalTypography = staticCompositionLocalOf<Typography> { error("No AppTheme") }
private val LocalShapes = staticCompositionLocalOf<Shapes> { error("No AppTheme") }
private val LocalMotionScheme = staticCompositionLocalOf { MotionScheme() }

/** The colour what is drawn here takes when it names none: text, icons. */
val LocalContentColor = compositionLocalOf { Color.Black }

/** The text style text here takes when it names none. */
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

/** Text in [content] in [value], over what it would take otherwise. */
@Composable
fun ProvideTextStyle(value: TextStyle, content: @Composable () -> Unit) {
    val merged = LocalTextStyle.current.merge(value)
    CompositionLocalProvider(LocalTextStyle provides merged, content = content)
}

/** The theme as a page reads it. */
object AppTheme {
    val typography: Typography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current
    val motionScheme: MotionScheme
        @Composable @ReadOnlyComposable get() = LocalMotionScheme.current
}

/** [content] drawn in the theme: its roles, type, corners and springs, its text in the body style, and a press drawn as a faint wash. */
@Composable
fun AppTheme(typography: Typography, shapes: Shapes, motionScheme: MotionScheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTypography provides typography,
        LocalShapes provides shapes,
        LocalMotionScheme provides motionScheme,
        LocalContentColor provides tokens.tx,
        LocalTextStyle provides typography.bodyReading,
        LocalIndication provides PressIndication,
        content = content,
    )
}

/**
 * What a press, a hover or focus looks like on anything tapped: a wash of the content's own
 * colour over it, faint, coming and going quickly; no ripple.
 */
object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 1
}

private class PressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val shown = mutableSetOf<Interaction>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press, is HoverInteraction.Enter, is FocusInteraction.Focus -> shown += interaction
                    is PressInteraction.Release -> shown -= interaction.press
                    is PressInteraction.Cancel -> shown -= interaction.press
                    is HoverInteraction.Exit -> shown -= interaction.enter
                    is FocusInteraction.Unfocus -> shown -= interaction.focus
                }
                val target = when {
                    shown.any { it is PressInteraction.Press } -> PRESSED
                    shown.isNotEmpty() -> HOVERED
                    else -> 0f
                }
                launch {
                    alpha.animateTo(target, if (target > alpha.value) tween(60) else spring(stiffness = Spring.StiffnessMediumLow))
                    invalidateDraw()
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) drawRect(currentValueOf(LocalContentColor).copy(alpha = a))
    }
}

private const val PRESSED = 0.1f
private const val HOVERED = 0.06f
