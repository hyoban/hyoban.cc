package cc.hyoban.moments.widget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// The heatmap widget's days, apart from how they are drawn: which days stand where in each of
// its shapes, and how deep each is.

/** What the heatmap counts each day, chosen when the widget is added and changed by long press. */
enum class HeatSource(val key: String, val link: String) {
    /** The Moments the public sees. */
    MOMENTS("moments", "moments"),
    /** What the ledger's bills spent, drawn only as deep or light. */
    SPENDING("spending", "ledger"),
    /** How far the footprints went, in metres. */
    FOOTPRINTS("footprints", "footprints");

    companion object {
        fun of(key: String?): HeatSource = entries.firstOrNull { it.key == key } ?: MOMENTS
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
