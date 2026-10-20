package cc.hyoban.moments

import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatDayOfMonth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.format.formatMonthOnly
import cc.hyoban.moments.ui.format.formatRange
import cc.hyoban.moments.ui.format.weekdayLetters
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dates read as a Chinese or an English reader says them, the year only when it isn't this one. */
class DatesTest {
    private val today = LocalDate.parse("2026-10-20")
    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.US

    @Test
    fun `in Chinese`() {
        assertEquals("10月6日", formatDate("2026-10-06", today, zh))
        assertEquals("2025年10月6日", formatDate("2025-10-06T09:00:00+08:00", today, zh))
        assertEquals("10月6日 周二", formatDay("2026-10-06", today, zh))
        assertEquals("9月30日 – 10月6日", formatRange("2026-09-30", "2026-10-06", today, zh))
        assertEquals("2025年12月30日 – 2026年1月2日", formatRange("2025-12-30", "2026-01-02", LocalDate.parse("2027-01-01"), zh))
        assertEquals("6日 周二", formatDayOfMonth("2026-10-06", zh))
        assertEquals("10月" to "2026", formatMonth("2026-10-06", zh))
        assertEquals("10 月", formatMonthOnly(10, zh))
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), weekdayLetters(zh))
    }

    @Test
    fun `in English`() {
        assertEquals("Oct 6", formatDate("2026-10-06", today, en))
        assertEquals("Oct 6, 2025", formatDate("2025-10-06", today, en))
        assertEquals("Tue, Oct 6", formatDay("2026-10-06", today, en))
        assertEquals("Sep 30 – Oct 6", formatRange("2026-09-30", "2026-10-06", today, en))
        assertEquals("Sep 30 – Oct 6, 2025", formatRange("2025-09-30", "2025-10-06", today, en))
        assertEquals("Dec 30, 2025 – Jan 2", formatRange("2025-12-30", "2026-01-02", today, en))
        assertEquals("Tue 6", formatDayOfMonth("2026-10-06", en))
        assertEquals("October" to "2026", formatMonth("2026-10-06", en))
        assertEquals("Oct", formatMonthOnly(10, en))
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), weekdayLetters(en))
    }
}
