package cc.hyoban.moments.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// The app's look, Material 3 with the Expressive shapes. One seed colour, a sea blue,
// makes every role through Material's own colour algorithm, the same on every phone
// rather than whatever the wallpaper gives. Type keeps the system's fonts but drops
// Material's letter spacing, which spreads Chinese out, and gives Chinese text room
// between lines.

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

/** Corners: large on what is held (cards, sheets), smaller inside them, full on pills. */
object Corners {
    val card = RoundedCornerShape(24.dp)
    /** A photo inside a card, its edges kept just inside the card's. */
    val inset = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun style(size: Int, line: Int, weight: FontWeight, numbers: Boolean = false) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.em,
    // Times and counts line up, digit under digit.
    fontFeatureSettings = if (numbers) "tnum" else null,
)

private val AppTypography = Typography(
    displaySmall = style(36, 44, FontWeight.SemiBold, numbers = true),
    headlineLarge = style(32, 40, FontWeight.SemiBold),
    headlineMedium = style(28, 36, FontWeight.SemiBold),
    headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 26, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium, numbers = true),
    labelMedium = style(12, 16, FontWeight.Medium, numbers = true),
    labelSmall = style(11, 14, FontWeight.Medium, numbers = true),
)

@Composable
fun MomentsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = rememberDynamicColorScheme(seedColor = Seed, isDark = dark, style = PaletteStyle.TonalSpot)

    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
}
