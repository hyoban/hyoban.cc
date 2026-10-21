package cc.hyoban.moments.ui.theme

import android.app.UiModeManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
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

// The app's look, Material 3 adapted the way FlClash adapts it: Material's type scale
// and shape scale, with continuous corners, and every colour role made from one seed, a
// the icon's green unless the settings pick another or the system's accent (Colors.kt says how
// the scheme leaves Material's).

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

    /** A screen's side margin, as Material 3 has it at the window's breakpoint: 16dp on a compact window, 24dp from medium on. */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = if (isMediumWidth()) 24.dp else 16.dp
}

/** Material 3's corner sizes, for a shape that animates between them; anything else takes [MaterialTheme.shapes]. */
object Radius {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 28.dp
}

/** The fully rounded shape: what floats, chips, labels. A capsule with continuous ends. */
object Corners {
    val full: Shape = ContinuousCapsule
}

/**
 * Material 3's elevation levels, for the shadows of what floats over content: 1 to 3 at rest,
 * 4 only while something is interacted with, as a photo held and dragged.
 */
object Elevation {
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
    val level4 = 8.dp
}

// Material 3's shape scale, its corner sizes as they are, drawn as continuous curves
// rather than arcs, so a corner eases into its sides as Apple's and FlClash's do.
private val AppShapes = Shapes(
    extraSmall = ContinuousRoundedRectangle(Radius.extraSmall),
    small = ContinuousRoundedRectangle(Radius.small),
    medium = ContinuousRoundedRectangle(Radius.medium),
    large = ContinuousRoundedRectangle(Radius.large),
    extraLarge = ContinuousRoundedRectangle(Radius.extraLarge),
)

// Material 3's type scale, its sizes and weights as they are, with two changes for
// Chinese: no letter spacing, which spreads it out, and body text a few points more
// between lines. Labels set their digits in columns, so times and counts line up. The
// emphasized styles, Material 3 Expressive's, the same with a heavier weight.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AppTypography = Typography().run {
    fun TextStyle.forChinese(lineHeight: Int? = null, numbers: Boolean = false) = copy(
        letterSpacing = 0.em,
        lineHeight = lineHeight?.sp ?: this.lineHeight,
        fontFeatureSettings = if (numbers) "tnum" else null,
    )
    Typography(
        displayLarge = displayLarge.forChinese(),
        displayMedium = displayMedium.forChinese(),
        displaySmall = displaySmall.forChinese(numbers = true),
        headlineLarge = headlineLarge.forChinese(),
        headlineMedium = headlineMedium.forChinese(),
        headlineSmall = headlineSmall.forChinese(),
        titleLarge = titleLarge.forChinese(),
        titleMedium = titleMedium.forChinese(),
        titleSmall = titleSmall.forChinese(),
        bodyLarge = bodyLarge.forChinese(lineHeight = 26),
        bodyMedium = bodyMedium.forChinese(lineHeight = 22),
        bodySmall = bodySmall.forChinese(lineHeight = 18),
        labelLarge = labelLarge.forChinese(numbers = true),
        labelMedium = labelMedium.forChinese(numbers = true),
        labelSmall = labelSmall.forChinese(numbers = true),
        displayLargeEmphasized = displayLargeEmphasized.forChinese(),
        displayMediumEmphasized = displayMediumEmphasized.forChinese(),
        displaySmallEmphasized = displaySmallEmphasized.forChinese(numbers = true),
        headlineLargeEmphasized = headlineLargeEmphasized.forChinese(),
        headlineMediumEmphasized = headlineMediumEmphasized.forChinese(),
        headlineSmallEmphasized = headlineSmallEmphasized.forChinese(),
        titleLargeEmphasized = titleLargeEmphasized.forChinese(),
        titleMediumEmphasized = titleMediumEmphasized.forChinese(),
        titleSmallEmphasized = titleSmallEmphasized.forChinese(),
        bodyLargeEmphasized = bodyLargeEmphasized.forChinese(lineHeight = 26),
        bodyMediumEmphasized = bodyMediumEmphasized.forChinese(lineHeight = 22),
        bodySmallEmphasized = bodySmallEmphasized.forChinese(lineHeight = 18),
        labelLargeEmphasized = labelLargeEmphasized.forChinese(numbers = true),
        labelMediumEmphasized = labelMediumEmphasized.forChinese(numbers = true),
        labelSmallEmphasized = labelSmallEmphasized.forChinese(numbers = true),
    )
}

/**
 * The springs a page changes on, Material 3's standard ones, which don't overshoot: a common
 * transition, as Material 3 has it, isn't bouncy, while the components keep the theme's
 * expressive ones.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val TransitionMotion: MotionScheme = MotionScheme.standard()

/**
 * The contrast the phone asks for, from Android 14's setting: 0 standard, up to 1 for high,
 * followed as it changes; standard before Android 14, which has no such setting.
 */
@Composable
private fun rememberSystemContrast(): Double {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0.0
    val context = LocalContext.current
    val modes = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(modes) { mutableFloatStateOf(modes.contrast) }
    DisposableEffect(modes) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        modes.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { modes.removeContrastChangeListener(listener) }
    }
    return contrast.toDouble()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MomentsTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val seed = Color(settings.themeColor ?: systemAccent() ?: Settings.DEFAULT_THEME_COLOR)
    val style = settings.colorStyle
    val pureBlack = settings.pureBlack
    val contrast = rememberSystemContrast()
    val colorScheme = remember(seed, dark, style, pureBlack, contrast) { seededColorScheme(seed, dark, style, pureBlack, contrast) }

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        // Material 3 Expressive's springs, which overshoot a little, for every component and the app's own motion.
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, motionScheme = MotionScheme.expressive(), content = content)
    }
}

/** The system's accent from the wallpaper, on Android 12 and later; null before. */
@Composable
fun systemAccent(): Int? {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(android.R.color.system_accent1_500) else null
}
