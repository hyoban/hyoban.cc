package cc.hyoban.moments.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeTonalSpot
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
private const val TINT_DIVISOR = 6.0
private const val MAX_TINT_CHROMA = 10.0

/** How much of the seed the scheme carries, as FlClash's colour styles. */
enum class ColorStyle {
    /** The seed itself as primary, the surfaces near grey: the scheme described above. */
    VIVID,

    /** As vivid, with the surfaces tinted towards the seed, a little more the more colourful it is. */
    TINTED,

    /** Material's tonal spot: a quieter primary from the seed's hue. */
    SOFT,

    /** Greys throughout. */
    MONOCHROME,
}

/** The scheme for [seed] in light or [dark], in [style]; [pureBlack] takes dark's page down to black. */
fun seededColorScheme(seed: Color, dark: Boolean, style: ColorStyle = ColorStyle.VIVID, pureBlack: Boolean = false): ColorScheme {
    val source = Hct.fromInt(seed.toArgb())
    val scheme: DynamicScheme = when (style) {
        ColorStyle.VIVID, ColorStyle.TINTED -> SchemeContent(source, dark, 0.0)
        ColorStyle.SOFT -> SchemeTonalSpot(source, dark, 0.0)
        ColorStyle.MONOCHROME -> SchemeMonochrome(source, dark, 0.0)
    }
    val accent = style == ColorStyle.VIVID || style == ColorStyle.TINTED
    val tint = if (style == ColorStyle.TINTED) min(source.chroma / TINT_DIVISOR, MAX_TINT_CHROMA) else null
    val roles = MaterialDynamicColors()
    val primaryPalette = scheme.primaryPalette
    val container = TonalPalette.fromHueAndChroma(primaryPalette.hue, min(primaryPalette.chroma, PRIMARY_CONTAINER_CHROMA))

    fun role(color: DynamicColor) = Color(color.getArgb(scheme))
    fun neutral(color: DynamicColor, maxChroma: Double): Color {
        val palette = color.palette(scheme)
        return Color(Hct.from(palette.hue, tint ?: min(palette.chroma, maxChroma), color.getTone(scheme)).toInt())
    }

    // The seed as primary: in light at its own tone between 35 and 60, so white text on it
    // keeps about 3:1; in dark lifted three tones, between 50 and 75.
    val tone = if (dark) (source.tone + 3).coerceIn(50.0, 75.0) else source.tone.coerceIn(35.0, 60.0)
    val primary = when {
        !accent -> role(roles.primary())
        tone == source.tone -> seed
        else -> Color(Hct.from(source.hue, source.chroma, tone).toInt())
    }
    val onPrimary = when {
        !accent -> role(roles.onPrimary())
        tone <= 60 -> Color.White
        else -> Color(TonalPalette.fromHueAndChroma(source.hue, source.chroma).tone(10))
    }
    val paper = if (dark) SURFACE_CHROMA else PAPER_CHROMA

    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
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
    return if (pureBlack && dark) colors.toPureBlack() else colors
}

/**
 * Dark with the page black, as FlClash's pure black: the deepest surfaces black and every
 * one above taken down by as much, so the steps between the levels stay as they were.
 */
private fun ColorScheme.toPureBlack(): ColorScheme {
    val shift = Hct.fromInt(surface.toArgb()).tone
    fun lower(color: Color): Color {
        val hct = Hct.fromInt(color.toArgb())
        return Color(Hct.from(hct.hue, hct.chroma, (hct.tone - shift).coerceAtLeast(0.0)).toInt())
    }
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = lower(surfaceContainerLow),
        surfaceContainer = lower(surfaceContainer),
        surfaceContainerHigh = lower(surfaceContainerHigh),
        surfaceContainerHighest = lower(surfaceContainerHighest),
        surfaceBright = lower(surfaceBright),
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
fun SurfaceLevel.fill(): Color = color(MaterialTheme.colorScheme, LocalDarkTheme.current)

/** A divider: onSurface at 14%, the same contrast on any level in either mode. */
@Composable
@ReadOnlyComposable
fun dividerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
