package cc.hyoban.moments.ui.theme

import cc.hyoban.moments.ui.design.Typography
import cc.hyoban.moments.ui.design.Shapes
import cc.hyoban.moments.ui.design.MotionScheme
import cc.hyoban.moments.ui.design.AppTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ThemeMode
import cc.hyoban.moments.ui.components.isMediumWidth
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

// The app's look, as its design system has it: the site's restraint on a phone. One column of
// text, a page and one surface with a hairline between them, greys for text and one blue for
// state (Colors.kt), a short type scale set apart by weight more than size, and continuous
// corners. The components are the app's own (`ui/design`), drawn with these.

/** The 4dp grid every gap and padding is a step of. */
object Space {
    /** Half a step, only inside small things: a label's padding, a badge's gap. */
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** A screen's side margin: 20dp on a phone, 32dp on a wider window. */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = if (isMediumWidth()) 32.dp else 20.dp
}

/**
 * The corner sizes, for a shape that animates between them; anything else takes
 * [AppTheme.shapes]. Three in all: small (8dp) for thumbnails and small marks, medium
 * (12dp) for fields and grouped lists, large (18dp) for cards and sheets; the larger
 * names, kept for their callers, are large too.
 */
object Radius {
    val extraSmall = 8.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 18.dp
    val largeIncreased = 18.dp
    val extraLarge = 18.dp
    val extraLargeIncreased = 18.dp
    val extraExtraLarge = 18.dp
}

/** The fully rounded shape: buttons, chips, labels and what floats. A capsule with continuous ends. */
object Corners {
    val full: Shape = ContinuousCapsule
}

/**
 * The shadows, kept few and faint: none where a hairline sets a thing apart (level 1: a day's
 * label, a menu, a message, a sheet), a soft one only for what floats over a photo, a map or
 * the scrolling page (level 2: the tab bar, the floating buttons), deeper only while something
 * is held or dragged (3 and 4).
 */
object Elevation {
    val level1 = 0.dp
    val level2 = 2.dp
    val level3 = 4.dp
    val level4 = 8.dp
}

// The corner sizes as continuous curves rather than arcs, so a corner eases into its sides.
private val AppShapes = Shapes(
    extraSmall = ContinuousRoundedRectangle(Radius.extraSmall),
    small = ContinuousRoundedRectangle(Radius.small),
    medium = ContinuousRoundedRectangle(Radius.medium),
    large = ContinuousRoundedRectangle(Radius.large),
    extraLarge = ContinuousRoundedRectangle(Radius.extraLarge),
)

private fun style(size: Int, line: Int, weight: FontWeight, numbers: Boolean = false) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.em,
    fontFeatureSettings = if (numbers) "tnum" else null,
)

// The design system's type scale on its slots: display for amounts, headline for a
// tab's large title, title for bars, cards and groups, body for a Moment's words (large) and
// lists, label for buttons, chips and the small print. No letter spacing, which spreads
// Chinese out; digits in columns wherever figures stand. The emphasized styles are the same
// a weight heavier.
private val AppTypography = run {
    val displayLarge = style(40, 44, FontWeight.Medium, numbers = true)
    val displayMedium = style(36, 40, FontWeight.Medium, numbers = true)
    val displaySmall = style(32, 40, FontWeight.SemiBold, numbers = true)
    val headlineSmall = style(22, 30, FontWeight.SemiBold)
    val titleLarge = style(18, 26, FontWeight.SemiBold)
    val bodyLarge = style(17, 30, FontWeight.Normal)
    val labelLarge = style(14, 20, FontWeight.Medium, numbers = true)
    val labelMedium = style(12, 16, FontWeight.Medium, numbers = true)
    fun TextStyle.heavier() = copy(fontWeight = FontWeight((fontWeight ?: FontWeight.Normal).weight + 100))
    Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = style(32, 40, FontWeight.SemiBold),
        headlineMedium = style(28, 36, FontWeight.SemiBold),
        headlineSmall = headlineSmall,
        titleLarge = titleLarge,
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = bodyLarge,
        bodyMedium = style(15, 24, FontWeight.Normal),
        bodySmall = style(12, 18, FontWeight.Normal),
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = style(11, 16, FontWeight.Medium, numbers = true),
        displaySmallEmphasized = displaySmall.heavier(),
        headlineSmallEmphasized = headlineSmall.heavier(),
        titleLargeEmphasized = titleLarge.heavier(),
        bodyLargeEmphasized = bodyLarge.copy(fontWeight = FontWeight.Medium),
        labelLargeEmphasized = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMediumEmphasized = labelMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** The springs everything moves on, which don't overshoot, for the components and for pages alike. */
val TransitionMotion: MotionScheme = MotionScheme.standard()

@Composable
fun MomentsTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val shown = appTokens(dark)
    val colorScheme = remember(dark) { shown.scheme() }

    CompositionLocalProvider(LocalDarkTheme provides dark, LocalTokens provides shown) {
        AppTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, motionScheme = TransitionMotion, content = content)
    }
}
