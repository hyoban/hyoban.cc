package cc.hyoban.moments.ui.theme

import cc.hyoban.moments.ui.design.Shapes
import cc.hyoban.moments.ui.design.MotionScheme
import cc.hyoban.moments.ui.design.AppTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CornerBasedShape

// The app's look, as its design system has it: the site's restraint on a phone. One column of
// text, a page and one surface with a hairline between them, greys for text and one blue for
// state, a short type scale set apart by weight more than size, and continuous
// corners, all from the design tokens (Tokens.kt). The components are the app's own (`ui/design`), drawn with these.

/** The fully rounded shape: buttons, chips, labels and what floats. A capsule with continuous ends. */
object Corners {
    val full: Shape = continuousCapsule()
}

/**
 * Whether the system's font is set large (130% or more): what stands side by side at the usual
 * size then stacks, and a row's words take two lines, rather than any figure being cut.
 */
@Composable @ReadOnlyComposable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.3f

// The corner sizes as continuous curves rather than arcs, so a corner eases into its sides.
private val AppShapes = Shapes(
    medium = continuousRoundedRectangle(Radius.medium),
    large = continuousRoundedRectangle(Radius.large),
)

/** A capsule with continuous ends, as each platform draws one. */
expect fun continuousCapsule(): Shape

/** A rectangle whose corners of [radius] ease into its sides, as each platform draws one. */
expect fun continuousRoundedRectangle(radius: Dp): CornerBasedShape

/** The springs everything moves on, which don't overshoot, for the components and for pages alike. */
val TransitionMotion: MotionScheme = MotionScheme.standard()

@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shown = appTokens(dark)

    CompositionLocalProvider(LocalDarkTheme provides dark, LocalTokens provides shown) {
        AppTheme(typography = TokenTypography, shapes = AppShapes, motionScheme = TransitionMotion, content = content)
    }
}
