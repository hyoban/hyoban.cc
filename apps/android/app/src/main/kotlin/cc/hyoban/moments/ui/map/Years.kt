package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

// The site's colours for the years places were first visited in, in the same order, so
// a place is the same colour on the phone as on the site's map.

private val YEAR_COLORS = listOf(
    Color(0xFF3B82F6),
    Color(0xFF8B5CF6),
    Color(0xFFF59E0B),
    Color(0xFF10B981),
    Color(0xFFEF4444),
    Color(0xFF06B6D4),
)

/** A colour for each year, in order from the earliest; years past the palette's end start it over. */
fun yearColors(years: Iterable<Int>): Map<Int, Color> =
    years.distinct().sorted().mapIndexed { index, year -> year to YEAR_COLORS[index % YEAR_COLORS.size] }.toMap()

/** This colour's hue and chroma at [tone], as Material sets a custom colour: 40 in light, 80 in dark. */
fun Color.atTone(tone: Double): Color {
    val hct = Hct.fromInt(toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, tone).toInt())
}

/** The year of an ISO date or timestamp. */
fun yearOf(occurredAt: String): Int = occurredAt.take(4).toInt()
