package cc.hyoban.moments.ui.theme

import cc.hyoban.moments.ui.design.Typography
import cc.hyoban.moments.ui.design.Shapes
import cc.hyoban.moments.ui.design.MotionScheme
import cc.hyoban.moments.ui.design.AppTheme
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.platform.LocalDensity
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

    // What a gap means, rather than how big it is: a page is set apart by these alone.
    /** Between things within a day or a section, and below the heading that leads them. */
    val item = l
    /** Between groups on a page: settings' groups, a detail page's runs of rows. */
    val section = xl
    /** Between days on a list that holds Moments, the widest gap on a list. */
    val day = xxl

    /** A screen's side margin: 20dp on a phone, 32dp on a wider window. */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = if (isMediumWidth()) 32.dp else 20.dp
}

/**
 * The corner sizes, for a shape that animates between them; anything else takes
 * [AppTheme.shapes]. Three in all: small (8dp) for thumbnails and small marks, medium
 * (12dp) for fields and grouped lists, large (18dp) for photos, cards and sheets.
 */
object Radius {
    val small = 8.dp
    val medium = 12.dp
    val large = 18.dp
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

/**
 * The date rail down the left of a timeline, a ledger and a trip's days: the day sits in it, the
 * rest to its right. 56dp, widening with the system's font up to a quarter more, so a day's two
 * figures still fit at the largest size.
 */
val Rail: Dp
    @Composable @ReadOnlyComposable get() = 56.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.25f)

/**
 * Whether the system's font is set large (130% or more): what stands side by side at the usual
 * size then stacks, and a row's words take two lines, rather than any figure being cut.
 */
@Composable @ReadOnlyComposable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.3f

// The corner sizes as continuous curves rather than arcs, so a corner eases into its sides.
private val AppShapes = Shapes(
    small = ContinuousRoundedRectangle(Radius.small),
    medium = ContinuousRoundedRectangle(Radius.medium),
    large = ContinuousRoundedRectangle(Radius.large),
)

private fun style(size: Int, line: Int, weight: FontWeight, mono: Boolean = false) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.em,
    fontFamily = if (mono) FontFamily.Monospace else null,
)

// The design system's type scale: the system's sans for words, its monospace for figures. No
// letter spacing, which spreads Chinese out.
private val AppTypography = Typography(
    titlePage = style(26, 34, FontWeight.SemiBold),
    title = style(18, 26, FontWeight.SemiBold),
    titleSmall = style(15, 22, FontWeight.SemiBold),
    bodyReading = style(17, 30, FontWeight.Normal),
    body = style(15, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    label = style(14, 20, FontWeight.Medium),
    caption = style(12, 18, FontWeight.Normal),
    micro = style(11, 14, FontWeight.Medium),
    numDisplay = style(34, 40, FontWeight.Medium, mono = true),
    numTitle = style(22, 28, FontWeight.Medium, mono = true),
    numBody = style(15, 24, FontWeight.Normal, mono = true),
    numCaption = style(12, 16, FontWeight.Normal, mono = true),
)

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

    CompositionLocalProvider(LocalDarkTheme provides dark, LocalTokens provides shown) {
        AppTheme(typography = AppTypography, shapes = AppShapes, motionScheme = TransitionMotion, content = content)
    }
}
