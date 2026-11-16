package cc.hyoban.moments

import cc.hyoban.moments.data.store.categoryLabel
import cc.hyoban.moments.data.store.entry
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.data.store.pendingBills
import cc.hyoban.moments.data.store.publicPart
import cc.hyoban.moments.data.store.readoptions
import cc.hyoban.moments.data.store.SiteBill
import cc.hyoban.moments.data.store.undo
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.ui.publish.after
import cc.hyoban.moments.ui.publish.before
import cc.hyoban.moments.ui.publish.group
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
    fun `a book public from a day makes only bills of that day on public`() {
        val day = txn.date
        val from = { start: java.time.LocalDate -> Ledger(books = listOf(book.copy(visibility = "public", publicFrom = start.toString())), txns = listOf(txn)) }
        assertTrue(from(day).isPublic(txn))
        assertTrue(from(day.minusDays(1)).isPublic(txn))
        assertFalse(from(day.plusDays(1)).isPublic(txn))
        assertTrue(from(day.plusDays(1)).isPublic(txn.copy(visibility = "public")))
        assertFalse(from(day).isPublic(txn.copy(visibility = "private")))
    }

    @Test
    fun `a bill waiting is new, changed or going off, the site's part read back`() {
        val public = ledger(txn, public = true)
        val new = public.pendingBills().single()
        assertEquals(cc.hyoban.moments.ui.publish.BillGroup.NEW, new.group)
        val published = txn.copy(published = new.part.toString(), amount = 1300)
        val changed = ledger(published, public = true).pendingBills().single()
        assertEquals(cc.hyoban.moments.ui.publish.BillGroup.CHANGED, changed.group)
        assertEquals(1250L, changed.before?.cents)
        assertEquals(1300L, changed.after?.cents)
        assertEquals("餐饮 › 零食", changed.before?.category)
        assertEquals(cc.hyoban.moments.ui.publish.BillGroup.OFF, ledger(published).pendingBills().single().group)
    }

    @Test
    fun `a published bill deleted here is taken off, with no undo`() {
        val change = Ledger(books = listOf(book), gone = listOf(txn.copy(published = "{}"))).pendingBills().single()
        assertTrue(change.deleted)
        assertNull(change.part)
        assertNull(change.undo())
    }

    @Test
    fun `a bill an import gave a new id takes back the site's`() {
        val part = ledger(txn, public = true).publicPart(txn)!!
        val at = java.time.Instant.ofEpochMilli(txn.time).atOffset(java.time.ZoneOffset.ofHours(8)).toString()
        val site = listOf(
            SiteBill("OLD", at, part, moment = false),
            SiteBill("M1", at, part, moment = true),
            SiteBill("OTHER", at, Entries.billPart("expense", 99.0, "CNY", null, null), moment = false),
        )
        val renamed = txn.copy(id = "NEW", time = txn.time + 60_000)
        assertEquals("OLD", ledger(renamed).readoptions(site)["NEW"]?.uid)
        assertEquals("M1", ledger(renamed.copy(moment = "M1")).readoptions(site)["NEW"]?.uid)
        assertTrue(ledger(renamed.copy(published = part.toString())).readoptions(site).isEmpty())
        assertTrue(ledger(txn.copy(id = "OLD")).readoptions(site).isEmpty())
        assertTrue(ledger(renamed.copy(time = txn.time + 11 * 60_000)).readoptions(site).isEmpty())
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
    fun `an expense publishes what its refunds brought back, up to what it cost, unless one is private`() {
        val refund = { id: String, amount: Long -> Txn(id = id, book = "B", kind = TxnKind.REFUND, time = time + 1, offsetMinutes = 480, amount = amount, currency = "CNY", related = txn.id) }
        fun part(vararg refunds: Txn) = ledger(txn, *refunds, public = true).publicPart(txn)!!
        assertNull(part()["refunded"])
        assertEquals("12.5", part(refund("R1", 1250))["amount"]!!.jsonPrimitive.content)
        assertEquals("12.5", part(refund("R1", 1250))["refunded"]!!.jsonPrimitive.content)
        assertEquals("5.0", part(refund("R1", 300), refund("R2", 200))["refunded"]!!.jsonPrimitive.content)
        assertEquals("12.5", part(refund("R1", 2000))["refunded"]!!.jsonPrimitive.content)
        assertNull(part(refund("R1", 300).copy(visibility = "private"))["refunded"])
        assertNull(ledger(txn, refund("R1", 300), public = true).publicPart(refund("R1", 300)))
        val published = txn.copy(published = part().toString())
        assertEquals(1, ledger(published, refund("R1", 300), public = true).pendingBills().size)
    }

    @Test
    fun `a trip's or a day's spending counts a refund with its expense, wherever either was written`() {
        val refund = Txn(id = "R1", book = "B", kind = TxnKind.REFUND, time = time + 86_400_000, offsetMinutes = 480, amount = 500, currency = "CNY", related = txn.id)
        val books = ledger(txn, refund)
        assertEquals(750L, cc.hyoban.moments.ledger.spendingOf(books, listOf(txn), "CNY").expense)
        assertEquals(0L, cc.hyoban.moments.ledger.spendingOf(books, listOf(refund), "CNY").expense)
        assertEquals(750L, cc.hyoban.moments.ledger.spendingOf(books, listOf(txn, refund), "CNY").expense)
        val alone = refund.copy(related = null)
        assertEquals(-500L, cc.hyoban.moments.ledger.spendingOf(ledger(txn, alone), listOf(alone), "CNY").expense)
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
            books = listOf(book.copy(visibility = "public", publicFrom = "2026-11-15")),
            accounts = emptyList(),
            categories = listOf(food, snack),
            txns = listOf(txn.copy(visibility = "private", published = "{}")),
        )
        val back = cc.hyoban.moments.ledger.LedgerBackup.decode(cc.hyoban.moments.ledger.LedgerBackup.encode(backup))
        assertEquals("public", back.books.single().visibility)
        assertEquals("2026-11-15", back.books.single().publicFrom)
        assertEquals("private", back.txns.single().visibility)
        assertEquals("{}", back.txns.single().published)
    }
}
