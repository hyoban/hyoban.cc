package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
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

    @Test
    fun `a day read from its morning reads the bills next to each other from the morning too`() {
        val at = { time: String -> java.time.OffsetDateTime.parse("2026-01-02T$time+08:00").toInstant().toEpochMilli() }
        val bill = { id: String, time: String -> Txn(id, "book", TxnKind.EXPENSE, at(time), 480, 100, "CNY") }
        val checkin = Checkin("checkin", "2026-01-02T19:43:00+08:00", "古茗")
        val items = dayItems(
            emptyList(),
            listOf(bill("red-packet", "16:52:00"), bill("battery", "20:09:00"), bill("tea", "19:46:00")),
            checkins = listOf(checkin),
            oldestFirst = true,
        )
        val order = items.flatMap { item ->
            when (item) {
                is DayItem.Bills -> item.txns.map { it.id }
                is DayItem.CheckinItem -> listOf(item.checkin.uid)
                else -> emptyList()
            }
        }
        assertEquals(listOf("red-packet", "checkin", "tea", "battery"), order)
    }

    private fun moment(uid: String, time: String): MomentRecord {
        val at = "2026-01-02T$time+08:00"
        return MomentRecord(uid, at, emptyList(), "", createdAt = at, updatedAt = at)
    }
}
