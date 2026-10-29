package cc.hyoban.moments

import cc.hyoban.moments.data.store.categoryLabel
import cc.hyoban.moments.data.store.entry
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.data.store.pendingBills
import cc.hyoban.moments.data.store.publicLine
import cc.hyoban.moments.data.store.simplify
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackShare
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What of the ledger and the footprints goes public: a bill by its book's say or its own, a span simplified. */
class OutsideTest {
    private val book = Book("B", "日常")
    private val food = Category("F", "B", CategoryType.EXPENSE, "餐饮")
    private val snack = Category("S", "B", CategoryType.EXPENSE, "零食", parent = "F")
    private val time = 1_790_000_000_000
    private val txn = Txn(
        id = "01K7TYA270CFFQRXN6M58XARSB", book = "B", kind = TxnKind.EXPENSE, time = time, offsetMinutes = 480,
        amount = 1250, currency = "CNY", category = "S", account = "wallet", note = "奶茶",
    )

    private fun ledger(vararg txns: Txn, public: Boolean = false) =
        Ledger(books = listOf(book.copy(visibility = if (public) "public" else "private")), categories = listOf(food, snack), txns = txns.toList())

    @Test
    fun `a bill is private unless its book or itself says public`() {
        assertFalse(ledger(txn).isPublic(txn))
        assertTrue(ledger(txn, public = true).isPublic(txn))
        assertFalse(ledger(txn, public = true).isPublic(txn.copy(visibility = "private")))
        assertTrue(ledger(txn).isPublic(txn.copy(visibility = "public")))
        assertTrue(ledger(txn).pendingBills().isEmpty())
    }

    @Test
    fun `a public bill publishes its amount, category and note, never its account`() {
        val pending = ledger(txn, public = true).pendingBills().single()
        val part = pending.part!!
        assertEquals("12.5", part["amount"]!!.jsonPrimitive.content)
        assertEquals("餐饮 › 零食", part["category"]!!.jsonPrimitive.content)
        assertEquals("奶茶", part["note"]!!.jsonPrimitive.content)
        assertNull(part["account"])
        assertEquals("餐饮 › 零食", ledger(txn).categoryLabel(txn))
        val entry = pending.entry()
        assertEquals(txn.id, entry["uid"]!!.jsonPrimitive.content)
        assertTrue(entry["at"]!!.jsonPrimitive.content.endsWith("+08:00"))
    }

    @Test
    fun `a bill published as it is waits for nothing, one made private again is taken off`() {
        val part = ledger(txn, public = true).pendingBills().single().part.toString()
        val published = txn.copy(published = part)
        assertTrue(ledger(published, public = true).pendingBills().isEmpty())
        val off = ledger(published).pendingBills().single()
        assertNull(off.part)
    }

    @Test
    fun `a span's line keeps its breaks and drops what lies on a straight line`() {
        val straight = (0..20).map { TrackPoint(time + it * 10_000L, 31.0 + it * 0.0001, 118.0, 5.0) }
        assertEquals(2, simplify(straight, 30.0).size)
        val share = TrackShare("01K7TYA270CFFQRXN6M58XARSC", time, time + 3_600_000)
        val line = publicLine(share, straight)!!
        val segment = line["segments"]!!.jsonArray.single().jsonObject["coordinates"]!!.jsonArray
        assertEquals(2, segment.size)
        assertEquals((time / 1000).toString(), segment.first().jsonArray[2].jsonPrimitive.content)
        assertNull(publicLine(share, emptyList()))
    }
}
