package cc.hyoban.moments.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

// The app's look, Material 3 adapted the way FlClash adapts it: Material's type scale
// and shape scale, with continuous corners, and every colour role made from one seed, a
// sea blue, the same on every phone (Colors.kt says how the scheme leaves Material's).

private val Seed = Color(0xFF2F6B8E)

/** The 4dp grid every gap and padding is a step of. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** A screen's side margin. */
    val gutter = 16.dp
}

/** The fully rounded shape: what floats, chips, labels. A capsule with continuous ends. */
object Corners {
    val full: Shape = ContinuousCapsule
}

// Material 3's shape scale, its corner sizes as they are, drawn as continuous curves
// rather than arcs, so a corner eases into its sides as Apple's and FlClash's do.
private val AppShapes = Shapes(
    extraSmall = ContinuousRoundedRectangle(4.dp),
    small = ContinuousRoundedRectangle(8.dp),
    medium = ContinuousRoundedRectangle(12.dp),
    large = ContinuousRoundedRectangle(16.dp),
    extraLarge = ContinuousRoundedRectangle(28.dp),
)

// Material 3's type scale, its sizes and weights as they are, with two changes for
// Chinese: no letter spacing, which spreads it out, and body text a few points more
// between lines. Labels set their digits in columns, so times and counts line up.
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
    )
}

@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = remember(dark) { seededColorScheme(Seed, dark) }

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
