package cc.hyoban.moments.ui.theme

import cc.hyoban.moments.ui.design.ColorScheme
import cc.hyoban.moments.ui.design.AppTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.min

// The app's colours, the design system's tokens as they are (the site's warm greys, one blue
// for state): a page, one surface above it and a hairline between them, text in three greys,
// and colour kept for what is chosen, on or in progress. The components read the colour roles,
// each set to the token that plays its part.

/** The design system's tokens, in light or dark; [scheme] maps them onto the colour roles. */
@Immutable
class Tokens(
    /** The page. */
    val bg: Color,
    /** Cards, fields and sheets: the one surface above the page. */
    val surface: Color,
    /** What is pressed, and a chip not chosen. */
    val pressed: Color,
    /** A step under [pressed], for a mark inside one. */
    val sunken: Color,
    /** The 1px line between things. */
    val border: Color,
    /** A control's edge, at 3:1 on the page. */
    val borderStrong: Color,
    val tx: Color,
    val tx2: Color,
    /** The faintest text and icons: hints, what isn't chosen. Never on [pressed]. */
    val tx3: Color,
    /** The fill of the page's one main button. */
    val ink: Color,
    val onInk: Color,
    /** The one colour for state: chosen, on, in progress, a link. */
    val accent: Color,
    val onAccent: Color,
    /** Under what is chosen, its text staying [tx]. */
    val accentSoft: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerSoft: Color,
    /** Money coming in. */
    val income: Color,
)

val LightTokens = Tokens(
    bg = Color(0xFFFDFDFC),
    surface = Color(0xFFF9F9F8),
    pressed = Color(0xFFF3F2F0),
    sunken = Color(0xFFEBEAE7),
    border = Color(0xFFE9E9E7),
    borderStrong = Color(0xFF8F8E88),
    tx = Color(0xFF21201C),
    tx2 = Color(0xFF63635E),
    tx3 = Color(0xFF73736C),
    ink = Color(0xFF21201C),
    onInk = Color(0xFFFDFDFC),
    accent = Color(0xFF3F63A6),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDF1F8),
    danger = Color(0xFFB3361F),
    onDanger = Color(0xFFFFFFFF),
    dangerSoft = Color(0xFFFBECE8),
    income = Color(0xFF2F6F8F),
)

val DarkTokens = Tokens(
    bg = Color(0xFF111110),
    surface = Color(0xFF191918),
    pressed = Color(0xFF1D1D1B),
    sunken = Color(0xFF252523),
    border = Color(0xFF2A2A28),
    borderStrong = Color(0xFF6E6D67),
    tx = Color(0xFFEEEEEC),
    tx2 = Color(0xFFB5B3AD),
    tx3 = Color(0xFF85837C),
    ink = Color(0xFFEEEEEC),
    onInk = Color(0xFF111110),
    accent = Color(0xFF9DB6E6),
    onAccent = Color(0xFF10213F),
    accentSoft = Color(0xFF1B2232),
    danger = Color(0xFFF0927C),
    onDanger = Color(0xFF2A0D06),
    dangerSoft = Color(0xFF33201B),
    income = Color(0xFF86C3DF),
)

/** The tokens in light or [dark]. */
fun appTokens(dark: Boolean): Tokens = if (dark) DarkTokens else LightTokens

/** The colour roles, each the token that plays its part. */
fun Tokens.scheme(): ColorScheme = ColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentSoft,
    onPrimaryContainer = tx,
    secondaryContainer = accentSoft,
    onSecondaryContainer = tx,
    tertiary = income,
    background = bg,
    surface = bg,
    onSurface = tx,
    onSurfaceVariant = tx2,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = pressed,
    surfaceContainerHighest = sunken,
    outline = borderStrong,
    outlineVariant = border,
    scrim = Color.Black,
    error = danger,
    onError = onDanger,
    errorContainer = dangerSoft,
    onErrorContainer = tx,
)

/** The current tokens. */
val LocalTokens = staticCompositionLocalOf { LightTokens }

/** The design system's tokens, as the theme was built. */
val tokens: Tokens
    @Composable @ReadOnlyComposable get() = LocalTokens.current

/** A card's edge: the 1px border, which sets the surface apart from the page. */
@Composable
@ReadOnlyComposable
fun cardBorder(): BorderStroke = BorderStroke(1.dp, tokens.border)

/** Whether the app is drawn dark, as the theme was built. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * How deep a surface sits. Each surface takes the level one deeper than the one it sits
 * on: a card on the page, a block (a label, a tile) in a card, an inset (a small mark)
 * in a block: the page, the surface, pressed and sunken. A card carries a hairline too
 * ([cardBorder]), as the page and the surface are close.
 */
enum class SurfaceLevel {
    Page, Card, Block, Inset;

    /** The level a surface on this one takes. */
    val raised: SurfaceLevel get() = entries[min(ordinal + 1, Inset.ordinal)]

    fun color(scheme: ColorScheme): Color = when (this) {
        Page -> scheme.surface
        Card -> scheme.surfaceContainer
        Block -> scheme.surfaceContainerHigh
        Inset -> scheme.surfaceContainerHighest
    }

    companion object {
        /**
         * What floats over content, a date over its Moments, the map's bars and buttons: the
         * surface, set apart from what it passes over by its hairline and shadow.
         */
        val Floating: SurfaceLevel = Card
    }
}

/** The level of the surface a composable sits on. */
val LocalSurfaceLevel = staticCompositionLocalOf { SurfaceLevel.Page }

/** The fill of [level] in the current scheme. */
@Composable
@ReadOnlyComposable
fun SurfaceLevel.fill(): Color = color(AppTheme.colorScheme)

/** A divider: the 1px border. */
@Composable
@ReadOnlyComposable
fun dividerColor(): Color = AppTheme.colorScheme.outlineVariant

/** How faint what is disabled is drawn. */
const val DISABLED_ALPHA = 0.38f

/**
 * What is drawn over a photo or a video, which stays dark in either mode: the scrim, at
 * one strength under a bar and another under a control or a label, and white on it.
 */
object MediaOverlay {
    /** Under a bar across the media, and over media set back. */
    val bar: Color
        @Composable @ReadOnlyComposable get() = AppTheme.colorScheme.scrim.copy(alpha = 0.32f)

    /** Under a control or a label on the media, and over media dimmed behind one chosen. */
    val control: Color
        @Composable @ReadOnlyComposable get() = AppTheme.colorScheme.scrim.copy(alpha = CONTROL_ALPHA)

    const val CONTROL_ALPHA = 0.45f

    /** Text and icons on either. */
    val content = Color.White

    /** Secondary text on either. */
    val contentVariant = Color.White.copy(alpha = 0.8f)

    /** What is there but not yet reached or not on: a slider's track ahead of it. */
    val contentDisabled = Color.White.copy(alpha = DISABLED_ALPHA)
}
