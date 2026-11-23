package cc.hyoban.moments

import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.plugins.BillRecord
import cc.hyoban.moments.plugins.CheckinAnchor
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ui.feed.ChatItem
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.at
import cc.hyoban.moments.ui.feed.chatItems
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatItemsTest {
    private fun bill(id: String, minute: Long) = Txn(id, "book", TxnKind.EXPENSE, 1_700_000_000_000L + minute * 60_000L, 480, 100, "CNY")

    private fun bills(vararg txns: Txn) = txns.map { DayItem.Record(BillRecord(it, Ledger())) }

    @Test
    fun `a check-in comes from the right and sets the records around it apart, each run whole`() {
        val stop = Checkin("s", "2023-11-14T23:00:00Z", "Cafe")
        val items = chatItems(
            bills(bill("a", 0), bill("b", 300)) + DayItem.Anchor(CheckinAnchor(stop)) + bills(bill("c", 400)),
        )
        assertEquals(listOf("records-bill-a", "checkin-s", "records-bill-c"), items.map { it.key })
        assertEquals(2, (items[0] as ChatItem.Records).lines.size)
    }

    @Test
    fun `a run of records is parted where a trip begins, and each bubble says when it was`() {
        val begins = 1_700_000_000_000L + 100 * 60_000L
        val items = chatItems(bills(bill("a", 0), bill("b", 100), bill("c", 200)), breakAt = begins)
        assertEquals(listOf("records-bill-a", "records-bill-b"), items.map { it.key })
        assertEquals(listOf(1_700_000_000_000L, begins), items.map { it.at() })
        assertEquals(1, chatItems(bills(bill("a", 0), bill("b", 100))).size, "without a trip, one bubble")
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

    @Test
    fun `a bill paid during a stay kept as a check-in belongs to it, the shortest stay when several cover it`() {
        val mall = Checkin("mall", "2023-11-14T22:00:00Z", "Mall", end = "2023-11-14T23:00:00Z")
        val shop = Checkin("shop", "2023-11-14T22:10:00Z", "Shop", end = "2023-11-14T22:20:00Z")
        val links = cc.hyoban.moments.ledger.AutoLinks(emptyList(), listOf(mall, shop))
        assertEquals("shop", links.of(bill("a", 0)))
        assertEquals("mall", links.of(bill("b", 30)))
        assertEquals(null, links.of(bill("c", 70)), "after the stay")
        val plain = cc.hyoban.moments.ledger.AutoLinks(emptyList(), listOf(Checkin("p", "2023-11-14T22:10:00Z", "Cafe")))
        assertEquals(null, plain.of(bill("d", 0)), "a check-in with no end covers only its very time")
    }
}
