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
import kotlinx.coroutines.launch

// The design system's theme: the colour roles, the type scale, the corners and the springs the
// app is drawn with, read through [AppTheme] as a page reads them, and the colour and text
// style what is drawn inside something takes from it. The app's own, with no Material under it.

/** The colours by the part they play; the design's tokens fill them (`ui/theme/Colors.kt`). */
@Immutable
class ColorScheme(
    /** The accent: what is chosen, on, in progress. */
    val primary: Color,
    val onPrimary: Color,
    /** The accent's soft tint, under what is chosen. */
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    /** Money coming in. */
    val tertiary: Color,
    val background: Color,
    /** The page. */
    val surface: Color,
    val onSurface: Color,
    /** Secondary text and icons. */
    val onSurfaceVariant: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    /** A control's edge, at 3:1. */
    val outline: Color,
    /** The hairline. */
    val outlineVariant: Color,
    val scrim: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

/** The type scale's slots, and each a weight heavier for emphasis. */
@Immutable
class Typography(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
    val displaySmallEmphasized: TextStyle,
    val headlineSmallEmphasized: TextStyle,
    val titleLargeEmphasized: TextStyle,
    val bodyLargeEmphasized: TextStyle,
    val labelLargeEmphasized: TextStyle,
    val labelMediumEmphasized: TextStyle,
)

/** The corner sizes: small for marks, medium for fields and groups, large for photos, cards and sheets. */
@Immutable
class Shapes(
    val extraSmall: CornerBasedShape,
    val small: CornerBasedShape,
    val medium: CornerBasedShape,
    val large: CornerBasedShape,
    val extraLarge: CornerBasedShape,
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

private val LocalColorScheme = staticCompositionLocalOf<ColorScheme> { error("No AppTheme") }
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
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = LocalColorScheme.current
    val typography: Typography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current
    val motionScheme: MotionScheme
        @Composable @ReadOnlyComposable get() = LocalMotionScheme.current
}

/** [content] drawn in the theme: its roles, type, corners and springs, its text in the body style, and a press drawn as a faint wash. */
@Composable
fun AppTheme(colorScheme: ColorScheme, typography: Typography, shapes: Shapes, motionScheme: MotionScheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalColorScheme provides colorScheme,
        LocalTypography provides typography,
        LocalShapes provides shapes,
        LocalMotionScheme provides motionScheme,
        LocalContentColor provides colorScheme.onSurface,
        LocalTextStyle provides typography.bodyLarge,
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
