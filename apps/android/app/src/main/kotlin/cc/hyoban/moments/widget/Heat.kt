package cc.hyoban.moments.widget

import cc.hyoban.moments.res.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// The heatmap widget's days, apart from how they are drawn: which days stand where in each of
// its shapes, and how deep each is.

/**
 * What the heatmap counts each day, chosen when the widget is added and changed by long press: the
 * Moments, or what a module brings (`PhonePoints.heat`), each kept by its [key].
 */
interface HeatSource {
    val key: String

    /** The page of the app the widget opens, a `moments://` host. */
    val link: String
    val icon: androidx.compose.ui.graphics.vector.ImageVector

    /** Its name on the widget. */
    fun name(context: android.content.Context): String

    /** Its name and what it counts, on the page that chooses it. */
    @androidx.compose.runtime.Composable fun title(): String
    @androidx.compose.runtime.Composable fun note(): String

    /** What each day from [from] through [to] had of it. */
    suspend fun days(context: android.content.Context, from: LocalDate, to: LocalDate): Map<LocalDate, Double>

    /** What the widget says the days [shown] came to: how many days had any. */
    fun amount(shown: Collection<Double>): String = shown.count { it > 0 }.let {
        pluralTextOf(Res.plurals.widget_heatmap_days, it, it)
    }

    companion object {
        /** Every source, the Moments first, then the modules', in their order. */
        val all: List<HeatSource> get() = listOf(MomentsHeat) + cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.PhonePoints.heat)

        fun of(key: String?): HeatSource = all.firstOrNull { it.key == key } ?: MomentsHeat
    }
}

/** A day's square: its column and row in the grid, or none drawn when [date] is still to come. */
data class HeatCell(val column: Int, val row: Int, val date: LocalDate)

/** The days a heatmap shows, in [columns] by [rows], from [start] through [end]. */
data class HeatGrid(val columns: Int, val rows: Int, val cells: List<HeatCell>, val start: LocalDate, val end: LocalDate)

/** [weeks] columns, one a week from Monday down, the last this week, up to [today]. */
fun weekGrid(today: LocalDate, weeks: Int): HeatGrid {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L)
    val cells = (0 until weeks * 7).mapNotNull { index ->
        val date = start.plusDays(index.toLong())
        if (date > today) null else HeatCell(index / 7, index % 7, date)
    }
    return HeatGrid(weeks, 7, cells, start, today)
}

/** The last [days] days in one row, ending [today]. */
fun dayRow(today: LocalDate, days: Int): HeatGrid {
    val start = today.minusDays(days - 1L)
    return HeatGrid(days, 1, (0 until days).map { HeatCell(it, 0, start.plusDays(it.toLong())) }, start, today)
}

/** The twelve months up to [today]'s, one a row and a day a column, as the ledger's year is drawn. */
fun monthGrid(today: LocalDate): HeatGrid {
    val first = YearMonth.from(today).minusMonths(11)
    val cells = (0 until 12).flatMap { row ->
        val month = first.plusMonths(row.toLong())
        (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { it <= today }.map { HeatCell(it.dayOfMonth - 1, row, it) }
    }
    return HeatGrid(31, 12, cells, first.atDay(1), today)
}

/** Which of four steps a day is, by its quartile among the days with any; 0 for none. */
fun heatLevels(values: Collection<Double>): (Double) -> Int {
    val sorted = values.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return { 0 }
    val cuts = listOf(0.25, 0.5, 0.75).map { sorted[((sorted.size - 1) * it).roundToInt()] }
    return { value -> if (value <= 0) 0 else 1 + cuts.count { value > it } }
}

/** How opaque the accent is at each step, as the ledger's heatmaps draw it. */
val HEAT_ALPHA = listOf(0f, 0.2f, 0.4f, 0.65f, 0.9f)
