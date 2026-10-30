// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.

package cc.hyoban.moments.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.design.Typography

/** The design system's colours in one theme; [LightTokens] and [DarkTokens] hold them. */
@Immutable
class Tokens(
    val bg: Color,
    val surface: Color,
    val fill: Color,
    val border: Color,
    val borderStrong: Color,
    val tx: Color,
    val tx2: Color,
    val tx3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val income: Color,
    val scrim: Color,
    val onMedia: Color,
    val year1: Color,
    val year2: Color,
    val year3: Color,
    val year4: Color,
    val year5: Color,
    val year6: Color,
    val series1: Color,
    val series2: Color,
    val series3: Color,
    val series4: Color,
    val series5: Color,
    val series6: Color,
)

val LightTokens = Tokens(
    bg = Color(0xFFFDFDFC),
    surface = Color(0xFFF9F9F8),
    fill = Color(0xFFF3F2F0),
    border = Color(0xFFE9E9E7),
    borderStrong = Color(0xFF8F8E88),
    tx = Color(0xFF21201C),
    tx2 = Color(0xFF4C4C47),
    tx3 = Color(0xFF73736C),
    accent = Color(0xFF3F63A6),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDF1F8),
    danger = Color(0xFFB3361F),
    income = Color(0xFF2F6F8F),
    scrim = Color(0x73000000),
    onMedia = Color(0xFFFFFFFF),
    year1 = Color(0xFF3B82F6),
    year2 = Color(0xFF8B5CF6),
    year3 = Color(0xFFF59E0B),
    year4 = Color(0xFF10B981),
    year5 = Color(0xFFEF4444),
    year6 = Color(0xFF06B6D4),
    series1 = Color(0xFF2A78D6),
    series2 = Color(0xFFEB6834),
    series3 = Color(0xFF1BAF7A),
    series4 = Color(0xFFEDA100),
    series5 = Color(0xFFE87BA4),
    series6 = Color(0xFF008300),
)

val DarkTokens = Tokens(
    bg = Color(0xFF111110),
    surface = Color(0xFF191918),
    fill = Color(0xFF21211F),
    border = Color(0xFF2A2A28),
    borderStrong = Color(0xFF6E6D67),
    tx = Color(0xFFEEEEEC),
    tx2 = Color(0xFFB5B3AD),
    tx3 = Color(0xFF85837C),
    accent = Color(0xFF9DB6E6),
    onAccent = Color(0xFF10213F),
    accentSoft = Color(0xFF1B2232),
    danger = Color(0xFFF0927C),
    income = Color(0xFF86C3DF),
    scrim = Color(0x73000000),
    onMedia = Color(0xFFFFFFFF),
    year1 = Color(0xFF3B82F6),
    year2 = Color(0xFF8B5CF6),
    year3 = Color(0xFFF59E0B),
    year4 = Color(0xFF10B981),
    year5 = Color(0xFFEF4444),
    year6 = Color(0xFF06B6D4),
    series1 = Color(0xFF3987E5),
    series2 = Color(0xFFD95926),
    series3 = Color(0xFF199E70),
    series4 = Color(0xFFC98500),
    series5 = Color(0xFFD55181),
    series6 = Color(0xFF008300),
)

/** The map's year colours in order, the same in both themes and on the site. */
val YearColors: List<Color> = listOf(LightTokens.year1, LightTokens.year2, LightTokens.year3, LightTokens.year4, LightTokens.year5, LightTokens.year6)

/** The 4dp grid every gap and padding is a step of, and the gaps that mean something. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = if (isMediumWidth()) 32.dp else 20.dp
}

/** The corner sizes, each a continuous curve where it is drawn. */
object Radius {
    val medium = 12.dp
    val large = 18.dp
}

/** The one shadow, as the level softShadow draws: a blur of 6× it, 1.5× it down, 10% black. */
val ShadowElevation = 2.dp

/** How strong a wash or a fade is. */
object Opacity {
    const val disabled = 0.38f
    const val press = 0.1f
    const val hover = 0.06f
}

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.em,
    fontFeatureSettings = "tnum",
)

/** The six text styles. */
val TokenTypography = Typography(
    titlePage = style(26, 34, FontWeight.SemiBold),
    title = style(18, 26, FontWeight.SemiBold),
    bodyReading = style(17, 30, FontWeight.Normal),
    body = style(15, 24, FontWeight.Normal),
    label = style(14, 20, FontWeight.Medium),
    caption = style(12, 18, FontWeight.Normal),
)

/** A spring that doesn't overshoot. */
class SpringToken(val dampingRatio: Float, val stiffness: Float)

/** The springs everything moves on. */
object Springs {
    val spatialFast = SpringToken(0.9f, 1400.0f)
    val spatialDefault = SpringToken(0.9f, 700.0f)
    val effectsFast = SpringToken(1.0f, 3800.0f)
    val effectsDefault = SpringToken(1.0f, 1600.0f)
}
