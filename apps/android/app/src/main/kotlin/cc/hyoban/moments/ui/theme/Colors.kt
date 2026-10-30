package cc.hyoban.moments.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.min

// The app's colours, the design system's tokens as they are (Tokens.kt, generated from
// packages/design-tokens): a page, one surface above it and a hairline between them, text in
// three greys, and colour kept for what is chosen, on or in progress.

/** The tokens in light or [dark]. */
fun appTokens(dark: Boolean): Tokens = if (dark) DarkTokens else LightTokens

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
 * in a block: the page, the surface, fill and fill-strong. A card carries a hairline too
 * ([cardBorder]), as the page and the surface are close.
 */
enum class SurfaceLevel {
    Page, Card, Block, Inset;

    /** The level a surface on this one takes. */
    val raised: SurfaceLevel get() = entries[min(ordinal + 1, Inset.ordinal)]

    fun color(tokens: Tokens): Color = when (this) {
        Page -> tokens.bg
        Card -> tokens.surface
        Block -> tokens.fill
        Inset -> tokens.fillStrong
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

/** The fill of [level] in the current tokens. */
@Composable
@ReadOnlyComposable
fun SurfaceLevel.fill(): Color = color(tokens)

/** A divider: the 1px border. */
@Composable
@ReadOnlyComposable
fun dividerColor(): Color = tokens.border

/** How faint what is disabled is drawn. */
const val DISABLED_ALPHA = Opacity.disabled

/**
 * What is drawn over a photo or a video, which stays dark in either mode: the scrim, at
 * one strength under a bar and another under a control or a label, and white on it.
 */
object MediaOverlay {
    /** Under a bar across the media, and over media set back. */
    val bar: Color
        @Composable @ReadOnlyComposable get() = tokens.scrimBar

    /** Under a control or a label on the media, and over media dimmed behind one chosen. */
    val control: Color
        @Composable @ReadOnlyComposable get() = tokens.scrim

    val CONTROL_ALPHA = LightTokens.scrim.alpha

    /** Text and icons on either. */
    val content = LightTokens.onMedia

    /** Secondary text on either. */
    val contentVariant = LightTokens.onMediaVariant

    /** What is there but not yet reached or not on: a slider's track ahead of it. */
    val contentDisabled = LightTokens.onMedia.copy(alpha = DISABLED_ALPHA)
}
