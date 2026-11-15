package cc.hyoban.moments

import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.feed.ChatItem
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.at
import cc.hyoban.moments.ui.feed.chatItems
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatItemsTest {
    private fun bill(id: String, minute: Long) = Txn(id, "book", TxnKind.EXPENSE, 1_700_000_000_000L + minute * 60_000L, 480, 100, "CNY")

    @Test
    fun `a check-in comes from the right and sets the records around it apart, each run whole`() {
        val stop = Checkin("s", "2023-11-14T23:00:00Z", "Cafe")
        val items = chatItems(
            listOf(DayItem.Bills(listOf(bill("a", 0), bill("b", 300))), DayItem.CheckinItem(stop), DayItem.Bills(listOf(bill("c", 400)))),
        )
        assertEquals(listOf("records-bill-a", "checkin-s", "records-bill-c"), items.map { it.key })
        assertEquals(2, (items[0] as ChatItem.Records).lines.size)
    }

    @Test
    fun `a run of records is parted where a trip begins, and each bubble says when it was`() {
        val begins = 1_700_000_000_000L + 100 * 60_000L
        val items = chatItems(listOf(DayItem.Bills(listOf(bill("a", 0), bill("b", 100), bill("c", 200)))), breakAt = begins)
        assertEquals(listOf("records-bill-a", "records-bill-b"), items.map { it.key })
        assertEquals(listOf(1_700_000_000_000L, begins), items.map { it.at() })
        assertEquals(1, chatItems(listOf(DayItem.Bills(listOf(bill("a", 0), bill("b", 100))))).size, "without a trip, one bubble")
    }

    @Test
    fun `a bill not linked by hand belongs to the Moment or check-in at its very time, a Moment first`() {
        val at = "2023-11-14T22:13:20Z" // 1_700_000_000_000
        val moment = cc.hyoban.moments.data.spec.MomentRecord(uid = "m", occurredAt = at, text = "noodles", createdAt = at, updatedAt = at)
        val links = cc.hyoban.moments.ledger.AutoLinks(listOf(moment), listOf(Checkin("s", at, "Cafe"), Checkin("t", "2023-11-14T22:14:20Z", "Shop")))
        assertEquals("m", links.of(bill("a", 0)))
        assertEquals("t", links.of(bill("b", 1)))
        assertEquals(null, links.of(bill("c", 2)), "a minute off isn't the same time")
        assertEquals("s", links.of(bill("d", 2).copy(moment = "s")), "one linked by hand stays so")
    }
}
