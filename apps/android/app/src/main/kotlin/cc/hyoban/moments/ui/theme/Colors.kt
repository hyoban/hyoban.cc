package cc.hyoban.moments.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.SchemeContent
import kotlin.math.min

// The app's colours, after FlClash's surfaces (github.com/chen08209/FlClash,
// .agents/surfaces.md): structure is drawn with fills a step apart in lightness, never
// with borders, and colour is kept for state: what is chosen, what is on, what fails.
//
// The scheme is Material's content scheme for the seed with three changes. Surfaces stay
// near grey, their chroma capped, so the seed doesn't wash every page blue. The seed
// itself is primary, as an accent colour is, its tone held where white text on it still
// reads. And primaryContainer is primary's own hue at tone 90 and 30, so it reads as the
// primary's light version rather than whatever tone the seed happens to have.

private const val SURFACE_CHROMA = 3.0
private const val PAPER_CHROMA = 1.5
private const val OUTLINE_VARIANT_CHROMA = 4.0
private const val PRIMARY_CONTAINER_CHROMA = 48.0

/** The scheme for [seed] in light or [dark]. */
fun seededColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val source = Hct.fromInt(seed.toArgb())
    val scheme = SchemeContent(source, dark, 0.0)
    val roles = MaterialDynamicColors()
    val primaryPalette = scheme.primaryPalette
    val container = TonalPalette.fromHueAndChroma(primaryPalette.hue, min(primaryPalette.chroma, PRIMARY_CONTAINER_CHROMA))

    fun role(color: DynamicColor) = Color(color.getArgb(scheme))
    fun neutral(color: DynamicColor, maxChroma: Double): Color {
        val palette = color.palette(scheme)
        return Color(Hct.from(palette.hue, min(palette.chroma, maxChroma), color.getTone(scheme)).toInt())
    }

    // The seed as primary: in light at its own tone between 35 and 60, so white text on it
    // keeps about 3:1; in dark lifted three tones, between 50 and 75.
    val tone = if (dark) (source.tone + 3).coerceIn(50.0, 75.0) else source.tone.coerceIn(35.0, 60.0)
    val primary = if (tone == source.tone) seed else Color(Hct.from(source.hue, source.chroma, tone).toInt())
    val onPrimary = if (tone <= 60) Color.White else Color(TonalPalette.fromHueAndChroma(source.hue, source.chroma).tone(10))
    val paper = if (dark) SURFACE_CHROMA else PAPER_CHROMA

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        surfaceTint = primary,
        primaryContainer = Color(container.tone(if (dark) 30 else 90)),
        onPrimaryContainer = Color(container.tone(if (dark) 90 else 30)),
        inversePrimary = role(roles.inversePrimary()),
        secondary = role(roles.secondary()),
        onSecondary = role(roles.onSecondary()),
        secondaryContainer = role(roles.secondaryContainer()),
        onSecondaryContainer = role(roles.onSecondaryContainer()),
        tertiary = role(roles.tertiary()),
        onTertiary = role(roles.onTertiary()),
        tertiaryContainer = role(roles.tertiaryContainer()),
        onTertiaryContainer = role(roles.onTertiaryContainer()),
        error = role(roles.error()),
        onError = role(roles.onError()),
        errorContainer = role(roles.errorContainer()),
        onErrorContainer = role(roles.onErrorContainer()),
        background = neutral(roles.surface(), paper),
        onBackground = role(roles.onSurface()),
        surface = neutral(roles.surface(), paper),
        onSurface = role(roles.onSurface()),
        surfaceVariant = neutral(roles.surfaceVariant(), SURFACE_CHROMA),
        onSurfaceVariant = role(roles.onSurfaceVariant()),
        inverseSurface = role(roles.inverseSurface()),
        inverseOnSurface = role(roles.inverseOnSurface()),
        outline = role(roles.outline()),
        outlineVariant = neutral(roles.outlineVariant(), OUTLINE_VARIANT_CHROMA),
        scrim = role(roles.scrim()),
        surfaceBright = neutral(roles.surfaceBright(), paper),
        surfaceDim = neutral(roles.surfaceDim(), SURFACE_CHROMA),
        surfaceContainerLowest = neutral(roles.surfaceContainerLowest(), SURFACE_CHROMA),
        surfaceContainerLow = neutral(roles.surfaceContainerLow(), SURFACE_CHROMA),
        surfaceContainer = neutral(roles.surfaceContainer(), SURFACE_CHROMA),
        surfaceContainerHigh = neutral(roles.surfaceContainerHigh(), SURFACE_CHROMA),
        surfaceContainerHighest = neutral(roles.surfaceContainerHighest(), SURFACE_CHROMA),
    )
}

/** Whether the app is drawn dark, as the theme was built. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * How deep a surface sits. Each surface takes the level one deeper than the one it sits
 * on: a card on the page, a block (a label, a tile) in a card, an inset (a small mark)
 * in a block. Light alternates grey and near-white; dark only lightens, as a darker step
 * inside a lighter surface reads as a hole.
 */
enum class SurfaceLevel {
    Page, Card, Block, Inset;

    /** The level a surface on this one takes. */
    val raised: SurfaceLevel get() = entries[min(ordinal + 1, Inset.ordinal)]

    fun color(scheme: ColorScheme, dark: Boolean): Color = when (this) {
        Page -> if (dark) scheme.surface else scheme.surfaceContainer
        Card -> if (dark) scheme.surfaceContainer else scheme.surfaceBright
        Block -> if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainer
        Inset -> scheme.surfaceContainerHighest
    }

    companion object {
        /** What floats over content, the tabs, a date over its Moments, the map's bars: a card in light, a block in dark, where a shadow doesn't show. */
        fun floating(dark: Boolean): SurfaceLevel = if (dark) Block else Card
    }
}

/** The level of the surface a composable sits on. */
val LocalSurfaceLevel = staticCompositionLocalOf { SurfaceLevel.Page }

/** The fill of [level] in the current scheme. */
@Composable
@ReadOnlyComposable
fun SurfaceLevel.fill(): Color = color(androidx.compose.material3.MaterialTheme.colorScheme, LocalDarkTheme.current)

/** A divider: onSurface at 14%, the same contrast on any level in either mode. */
@Composable
@ReadOnlyComposable
fun dividerColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
