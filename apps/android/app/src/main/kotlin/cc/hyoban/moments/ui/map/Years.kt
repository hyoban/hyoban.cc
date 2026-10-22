package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color

// The site's colours for the years places were first visited in, in the same order and as they
// are, so a place is the same colour on the phone as on the site's map: an exception to
// Material 3's custom colours, harmonized and toned, kept on purpose.

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

/** The year of an ISO date or timestamp. */
fun yearOf(occurredAt: String): Int = occurredAt.take(4).toInt()
