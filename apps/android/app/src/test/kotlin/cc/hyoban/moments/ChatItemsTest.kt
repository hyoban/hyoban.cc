package cc.hyoban.moments

import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.feed.ChatItem
import cc.hyoban.moments.ui.feed.DayItem
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
            emptyMap(),
        )
        assertEquals(listOf("records-bill-a", "checkin-s", "records-bill-c"), items.map { it.key })
        assertEquals(2, (items[0] as ChatItem.Records).lines.size)
    }
}
