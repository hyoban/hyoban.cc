package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.feed.TimeFilter
import cc.hyoban.moments.ui.feed.filtered
import cc.hyoban.moments.ui.feed.monthsOf
import cc.hyoban.moments.ui.feed.yearsOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** The timeline narrows to a year, then a month of it, from the years and months there are Moments in. */
class TimeFilterTest {
    private val moments = listOf(
        moment("A", "2026-10-06T23:45:00+08:00"),
        moment("B", "2026-05-01"),
        moment("C", "2025-10-18T13:57:00+08:00"),
        moment("D", "2024-09-03"),
    )

    @Test
    fun `the years and a year's months come newest first`() {
        assertEquals(listOf(2026, 2025, 2024), yearsOf(moments))
        assertEquals(listOf(10, 5), monthsOf(moments, 2026))
    }

    @Test
    fun `a year lets its Moments through, a month only that month's, and none all`() {
        assertEquals(listOf("A", "B", "C", "D"), moments.filtered(TimeFilter()).map { it.uid })
        assertEquals(listOf("A", "B"), moments.filtered(TimeFilter(2026)).map { it.uid })
        assertEquals(listOf("A"), moments.filtered(TimeFilter(2026, 10)).map { it.uid })
        assertEquals(emptyList(), moments.filtered(TimeFilter(2025, 5)).map { it.uid })
    }

    private fun moment(uid: String, occurredAt: String) =
        MomentRecord(uid, occurredAt, emptyList(), "", createdAt = occurredAt, updatedAt = occurredAt)
}
