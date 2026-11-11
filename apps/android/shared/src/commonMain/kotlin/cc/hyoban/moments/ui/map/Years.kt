package cc.hyoban.moments.ui.map

import androidx.compose.ui.graphics.Color
import cc.hyoban.moments.ui.theme.YearColors

// The site's colours for the years places were first visited in, in the same order and as they
// are, so a place is the same colour on the phone as on the site's map: the design tokens'
// year-1 … year-6, which match packages/trip-map/src/theme.ts.

private val YEAR_COLORS = YearColors

/** A colour for each year, in order from the earliest; years past the palette's end start it over. */
fun yearColors(years: Iterable<Int>): Map<Int, Color> =
    years.distinct().sorted().mapIndexed { index, year -> year to YEAR_COLORS[index % YEAR_COLORS.size] }.toMap()

/** The year of an ISO date or timestamp. */
fun yearOf(occurredAt: String): Int = occurredAt.take(4).toInt()
