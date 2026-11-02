package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.feed.Charge
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.dayItems
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTest {
    @Test
    fun `a trip reads its Moments from morning to evening regardless of input order or other records`() {
        val morning = moment("morning", "08:00:00")
        val afternoon = moment("afternoon", "14:00:00")
        val evening = moment("evening", "20:00:00")
        val charge = Charge("charge", "2026-01-02T12:00:00+08:00", true, 20.0, 15)
        val expected = listOf("morning", "afternoon", "evening")

        for (moments in listOf(listOf(morning, afternoon, evening), listOf(evening, afternoon, morning), listOf(afternoon, morning, evening))) {
            for (charges in listOf(emptyList(), listOf(charge))) {
                val chronological = dayItems(moments, emptyList(), charges).reversed()
                assertEquals(expected, chronological.filterIsInstance<DayItem.Moment>().map { it.moment.uid })
                if (charges.isNotEmpty()) assertEquals(charge, (chronological[1] as DayItem.ChargeItem).charge)
            }
        }
    }

    private fun moment(uid: String, time: String): MomentRecord {
        val at = "2026-01-02T$time+08:00"
        return MomentRecord(uid, at, emptyList(), "", createdAt = at, updatedAt = at)
    }
}
