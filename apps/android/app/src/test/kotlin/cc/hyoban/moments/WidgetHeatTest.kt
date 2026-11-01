package cc.hyoban.moments

import cc.hyoban.moments.widget.HeatSource
import cc.hyoban.moments.widget.dayRow
import cc.hyoban.moments.widget.heatLevels
import cc.hyoban.moments.widget.monthGrid
import cc.hyoban.moments.widget.weekGrid
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The heatmap widget lays its days out by weeks, by months or in a row, and shades them by quartile. */
class WidgetHeatTest {
    // A Monday.
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun weeksRunFromMondayDownAndStopToday() {
        val grid = weekGrid(today, weeks = 3)
        assertEquals(LocalDate.parse("2026-10-19"), grid.start)
        // Two whole weeks and this week's Monday; the rest of this week is still to come.
        assertEquals(15, grid.cells.size)
        val last = grid.cells.last()
        assertEquals(today, last.date)
        assertEquals(2 to 0, last.column to last.row)
        val sunday = grid.cells.first { it.date == LocalDate.parse("2026-10-25") }
        assertEquals(0 to 6, sunday.column to sunday.row)
    }

    @Test
    fun aRowEndsToday() {
        val grid = dayRow(today, days = 14)
        assertEquals(14, grid.cells.size)
        assertEquals(LocalDate.parse("2026-10-20"), grid.start)
        assertEquals(today, grid.cells.last().date)
        assertEquals(13, grid.cells.last().column)
    }

    @Test
    fun aYearIsTheTwelveMonthsUpToThisOne() {
        val grid = monthGrid(today)
        assertEquals(LocalDate.parse("2025-12-01"), grid.start)
        // December's 31, February's 28 and this month's first two days.
        assertEquals(31, grid.cells.count { it.row == 0 })
        assertEquals(28, grid.cells.count { it.row == 2 })
        assertEquals(listOf(0, 1), grid.cells.filter { it.row == 11 }.map { it.column })
    }

    @Test
    fun levelsAreQuartilesOfTheDaysWithAny() {
        val level = heatLevels(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(0, level(0.0))
        assertEquals(1, level(1.0))
        assertEquals(4, level(5.0))
        assertEquals(0, heatLevels(listOf(0.0, 0.0))(3.0))
    }

    @Test
    fun anUnknownSourceIsMoments() {
        assertEquals(HeatSource.MOMENTS, HeatSource.of(null))
        assertEquals(HeatSource.MOMENTS, HeatSource.of("gone"))
        assertEquals(HeatSource.FOOTPRINTS, HeatSource.of("footprints"))
    }
}
