package cc.hyoban.moments

import cc.hyoban.moments.data.store.categoryLabel
import cc.hyoban.moments.data.store.entry
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.data.store.pendingBills
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
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
    fun `a backup keeps who sees each book and bill, and what of each was published`() {
        val backup = cc.hyoban.moments.ledger.LedgerBackup(
            books = listOf(book.copy(visibility = "public")),
            accounts = emptyList(),
            categories = listOf(food, snack),
            txns = listOf(txn.copy(visibility = "private", published = "{}")),
        )
        val back = cc.hyoban.moments.ledger.LedgerBackup.decode(cc.hyoban.moments.ledger.LedgerBackup.encode(backup))
        assertEquals("public", back.books.single().visibility)
        assertEquals("private", back.txns.single().visibility)
        assertEquals("{}", back.txns.single().published)
    }
}
