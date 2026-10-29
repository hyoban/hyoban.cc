package cc.hyoban.moments

import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.Flow
import cc.hyoban.moments.ledger.ICostImport
import cc.hyoban.moments.ledger.noteWithTags
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Rates
import cc.hyoban.moments.ledger.Reimburse
import cc.hyoban.moments.ledger.TransferType
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.Xlsx
import cc.hyoban.moments.ledger.formatCents
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ledger.parseCents
import cc.hyoban.moments.ledger.netAssetsByMonth
import cc.hyoban.moments.ledger.runningTotals
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ledger.weekdayAverages
import java.time.YearMonth
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LedgerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val header = listOf("日期", "类型", "金额", "一级分类", "二级分类", "账户1", "账户2", "备注", "货币", "标签", "账本", "退款", "优惠", "手续费")
    private val rates = Rates.EMPTY.with(mapOf("SGD" to mapOf(LocalDate.of(2025, 1, 1) to 0.2), "HKD" to mapOf(LocalDate.of(2025, 1, 1) to 1.0)))

    private fun row(date: String, type: String, amount: String, category: String = "", sub: String = "", account: String = "", account2: String = "",
        note: String = "", currency: String = "CNY", tags: String = "", book: String = "默认账本", refund: String = "", discount: String = "") =
        listOf(date, type, amount, category, sub, account, account2, note, currency, tags, book, refund, discount, "")

    private var next = 0
    private fun read(vararg rows: List<String>, existing: Ledger = Ledger()) =
        ICostImport.read(listOf(header) + rows.toList(), zone, rates, existing) { "id${next++}" }

    private fun ledgerOf(result: ICostImport.Result) = Ledger(result.books, result.accounts, result.categories, result.txns.sortedByDescending { it.time }, rates)

    @Test fun parsesAmountsToCents() {
        assertEquals(876L, parseCents("-8.76"))
        assertEquals(584L, parseCents("5.840000000000003"))
        assertEquals(250000L, parseCents("-2500.00"))
        assertNull(parseCents(""))
        assertEquals("1,234.50", formatCents(123450))
        assertEquals("12", formatCents(1200))
        assertEquals("-HK$8.76", formatMoney(-876, "HKD"))
    }

    @Test fun readsSplitsAndTags() {
        assertEquals(listOf("红包" to 2389L, "中国银行 储蓄卡4049" to 1L), ICostImport.parseSplits("红包[-23.89]/中国银行 储蓄卡4049[-0.01]"))
        assertEquals(listOf("京东", "已卖出"), ICostImport.parseTags("#京东 #已卖出"))
        assertEquals(listOf("a", "b"), ICostImport.parseTags("#a#b"))
        assertEquals("午饭 #京东 #已卖出", noteWithTags(" 午饭 ", listOf("京东", "#已卖出", "京东")))
        assertEquals("#京东", noteWithTags("", listOf("京东")))
        assertEquals("买了 #京东", noteWithTags("买了 #京东", listOf("京东")))
        assertEquals(2, Xlsx.columnOf("C12"))
        assertEquals(26, Xlsx.columnOf("AA3"))
    }

    @Test fun guessesAccountKinds() {
        assertEquals(AccountKind.CREDIT, ICostImport.kindOf("花呗", false))
        assertEquals(AccountKind.LOAN, ICostImport.kindOf("车贷", false))
        assertEquals(AccountKind.INVESTMENT, ICostImport.kindOf("余额宝", false))
        assertEquals(AccountKind.DEBIT, ICostImport.kindOf("中国银行 储蓄卡4049", false))
        assertEquals(AccountKind.WALLET, ICostImport.kindOf("微信余额（hyoban）", false))
        assertEquals(AccountKind.STORED, ICostImport.kindOf("京东卡", false))
        assertEquals(AccountKind.PERSON, ICostImport.kindOf("妈", true))
    }

    @Test fun matchesRefundsAndFoldsTheirSurplus() {
        val result = read(
            row("2025/01/03 08:54", "支出", "-183", "人情", "家人", "中国银行 储蓄卡4049", tags = "#妈-抖音", refund = "188.84"),
            row("2025/01/07 16:47", "支出", "-366", "人情", "家人", "中国银行 储蓄卡4049", refund = "188"),
            row("2025/01/08 20:31", "退款入账", "188.84", "人情", "家人", "微信余额（hyoban）"),
            row("2025/01/08 20:31", "退款收入", "5.840000000000003", "人情", "家人", "微信余额（hyoban）"),
            row("2025/01/14 09:01", "退款入账", "188", "人情", "家人", "微信余额（hyoban）"),
        )
        assertEquals(4, result.txns.size)
        assertEquals(2, result.report.refundsMatched)
        assertEquals(1, result.report.folded)
        val ledger = ledgerOf(result)
        val first = result.txns.first { it.amount == 18300L }
        assertEquals(first.id, result.txns.first { it.amount == 18884L }.related)
        // 183 spent and 188.84 back: nothing spent in the end, and 5.84 earned.
        val summary = summarize(ledger.entries.filter { it.txn.id == first.id || it.txn.related == first.id }, ledger.rates, "CNY", null, null)
        assertEquals(0L, summary.expense)
        assertEquals(584L, summary.income)
        assertEquals(-18300L + 18884L + 0L, ledger.balances.getValue(result.accounts.first { it.name.startsWith("中国") }.id) + 36600 + ledger.balances.getValue(result.accounts.first { it.name.startsWith("微信") }.id) - 18800)
    }

    @Test fun foldsTransferDiscountsAndConvertsCurrencies() {
        val result = read(
            row("2025/02/09 17:40", "还款", "658.33", "还款", "", "余额宝", "花呗", discount = "2.08"),
            row("2025/02/09 17:40", "还款优惠", "2.08", "还款", "", "余额宝"),
            row("2025/01/31 12:50", "转账", "100", "转账", "", "OCBC 360", "中国银行 储蓄卡4049", currency = "SGD"),
            row("2025/02/01 12:00", "支出", "-10", "三餐", "", "OCBC 360", currency = "SGD"),
            row("2025/02/02 12:00", "支出", "-10", "三餐", "", "中国银行 储蓄卡4049"),
        )
        assertEquals(1, result.report.folded)
        val repay = result.txns.first { it.transfer == TransferType.REPAY }
        assertEquals(208L, repay.discount)
        assertEquals(result.accounts.first { it.name == "余额宝" }.id, repay.discountAccount)
        val transfer = result.txns.first { it.currency == "SGD" && it.kind == TxnKind.TRANSFER }
        // 100 SGD at 0.2 SGD to the yuan.
        assertEquals(50000L, transfer.toAmount)
        val ledger = ledgerOf(result)
        val bank = result.accounts.first { it.name.startsWith("中国") }
        assertEquals("CNY", bank.currency)
        assertEquals(50000L - 1000L, ledger.balances[bank.id])
        val huabei = result.accounts.first { it.name == "花呗" }
        assertEquals(AccountKind.CREDIT, huabei.kind)
        assertEquals(65833L, ledger.balances[huabei.id])
        // The discount is earned.
        assertEquals(208L, ledger.entries.filter { it.flow == Flow.INCOME }.sumOf { it.amount })
    }

    @Test fun matchesReimbursementsAndLoans() {
        val result = read(
            row("2025/12/13 20:43", "已报销", "-108", "入职", "", "SafePal", note = "体检"),
            row("2025/12/23 13:58", "报销入账", "108", "入职", "", "招商银行 储蓄卡 2857"),
            row("2026/03/18 20:41", "借出", "20000", "转账", "", "中国银行 储蓄卡4049", "姐"),
            row("2026/01/01 22:59", "借入", "6000", "转账", "", "姐", "中国银行 储蓄卡4049"),
            row("2025/11/29 20:01", "支出", "-18", "饮料", "", "中国银行 储蓄卡4049[-0.01]/红包[-17.99]"),
        )
        val expense = result.txns.first { it.kind == TxnKind.EXPENSE && it.reimburse == Reimburse.DONE }
        assertEquals(expense.id, result.txns.first { it.kind == TxnKind.REIMBURSEMENT }.related)
        val ledger = ledgerOf(result)
        // Reimbursed in full: none of it is one's own spending.
        assertEquals(1800L, ledger.entries.filter { it.flow == Flow.EXPENSE }.sumOf { it.amount })
        val sister = result.accounts.first { it.name == "姐" }
        assertEquals(AccountKind.PERSON, sister.kind)
        assertEquals(2000000L - 600000L, ledger.balances[sister.id])
        val split = result.txns.first { it.splits.isNotEmpty() }
        assertEquals(listOf(1L, 1799L), split.splits.map { it.amount })
        assertNull(split.account)
    }

    @Test fun leavesOutWhatIsAlreadyThere() {
        val first = read(row("2025/01/02 18:10", "支出", "-4.28", "饮料", "", "中国银行 储蓄卡4049", note = "山楂莓莓"))
        val again = read(
            row("2025/01/02 18:10", "支出", "-4.28", "饮料", "", "中国银行 储蓄卡4049", note = "山楂莓莓"),
            row("2025/01/03 18:10", "支出", "-5", "饮料", "", "中国银行 储蓄卡4049"),
            existing = ledgerOf(first),
        )
        assertEquals(1, again.report.duplicates)
        assertEquals(1, again.txns.size)
        assertEquals(0, again.accounts.size)
        assertEquals(0, again.categories.size)
        assertEquals(first.categories.single().id, again.txns.single().category)
    }

    @Test fun leavesOutABillWrittenHereOfTheSamePayment() {
        val first = read(row("2025/01/02 08:00", "支出", "-1", "饮料", "", "中国银行 储蓄卡4049"))
        val ledger = ledgerOf(first)
        val book = ledger.books.single().id
        // Written from 自动记账 at the payment's second, with its own note and another account.
        val at = java.time.LocalDateTime.of(2025, 1, 2, 18, 10, 41).atZone(zone).toInstant().toEpochMilli()
        val here = Txn("here", book, TxnKind.EXPENSE, at, 480, 428, "CNY", note = "某某茶饮(某某店)外卖订单")
        val again = read(
            row("2025/01/02 18:10", "支出", "-4.28", "饮料", "", "中国银行 储蓄卡4049", note = "山楂莓莓"),
            // Another of the same amount within the ten minutes, with only one bill here for both.
            row("2025/01/02 18:15", "支出", "-4.28", "饮料", "", "中国银行 储蓄卡4049"),
            row("2025/01/02 18:40", "支出", "-4.28", "饮料", "", "中国银行 储蓄卡4049"),
            row("2025/01/02 18:10", "支出", "-4.29", "饮料", "", "中国银行 储蓄卡4049"),
            existing = ledger.copy(txns = listOf(here) + ledger.txns),
        )
        assertEquals(1, again.report.duplicates)
        assertEquals(3, again.txns.size)
        assertTrue(again.txns.none { it.id == "here" })
    }

    @Test fun aLaterExportAddsOnlyWhatIsNewAndKeepsItsLinks() {
        val expense = row("2025/01/03 08:54", "支出", "-183", "人情", "家人", "中国银行 储蓄卡4049", refund = "188.84")
        val repay = row("2025/02/09 17:40", "还款", "658.33", "还款", "", "余额宝", "花呗", discount = "2.08")
        val stray = row("2025/02/10 10:00", "转账优惠", "1.5", "转账", "", "余额宝")
        val first = read(expense, repay, row("2025/02/09 17:40", "还款优惠", "2.08", "还款", "", "余额宝"), stray)
        val ledger = ledgerOf(first)
        // The refund arrives later, in a later export that has everything the first had.
        val again = read(
            expense,
            repay,
            row("2025/02/09 17:40", "还款优惠", "2.08", "还款", "", "余额宝"),
            stray,
            row("2025/01/08 20:31", "退款入账", "188.84", "人情", "家人", "微信余额（hyoban）"),
            row("2025/01/08 20:31", "退款收入", "5.84", "人情", "家人", "微信余额（hyoban）"),
            existing = ledger,
        )
        assertEquals(3, again.report.duplicates)
        val refund = again.txns.single()
        assertEquals(TxnKind.REFUND, refund.kind)
        assertEquals(first.txns.first { it.kind == TxnKind.EXPENSE }.id, refund.related)
        assertEquals(1, again.report.refundsMatched)
        // Importing the same export a third time adds nothing.
        val merged = Ledger(ledger.books, ledger.accounts + again.accounts, ledger.categories + again.categories, (ledger.txns + again.txns).sortedByDescending { it.time }, rates)
        assertEquals(0, read(expense, repay, stray, existing = merged).txns.size)
    }

    @Test fun everyBookSumsCategoriesOfTheSameNameTogether() {
        val result = read(
            row("2025/01/02 12:00", "支出", "-10", "三餐", "沙县小吃", "中国银行 储蓄卡4049"),
            row("2025/01/03 12:00", "支出", "-20", "三餐", "", "中国银行 储蓄卡4049", book = "旅游账本"),
            row("2025/01/04 12:00", "支出", "-5", "饮料", "", "中国银行 储蓄卡4049", book = "旅游账本"),
        )
        val ledger = ledgerOf(result).copy(allBooks = true)
        assertEquals(2, ledger.shownBooks.size)
        val summary = summarize(ledger.entries, ledger.rates, "CNY", null, null, ledger::topCategoryName)
        assertEquals(listOf("三餐" to 3000L, "饮料" to 500L), summary.expenseByCategory)
    }

    @Test fun runningTotalsStopAtToday() {
        val start = LocalDate.of(2025, 1, 1)
        val days = mapOf(start to 100L, start.plusDays(2) to 50L, start.plusDays(5) to 7L)
        assertEquals(listOf(100L, 100L, 150L), runningTotals(days, start, start.plusMonths(1), until = start.plusDays(2)))
        assertEquals(31, runningTotals(days, start, start.plusMonths(1)).size)
    }

    @Test fun weekdayAveragesCountDaysWithNothingSpent() {
        // 2025-01-06 and 13 are Mondays; nothing on the 13th.
        val days = mapOf(LocalDate.of(2025, 1, 6) to 100L, LocalDate.of(2025, 1, 7) to 30L)
        val averages = weekdayAverages(days, LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 20))
        assertEquals(listOf(50L, 15L, 0L, 0L, 0L, 0L, 0L), averages)
    }

    @Test fun netAssetsAreTakenBackMonthByMonth() {
        val result = read(
            row("2025/01/10 12:00", "收入", "1000", "工资", "", "中国银行 储蓄卡4049"),
            row("2025/02/10 12:00", "支出", "-300", "三餐", "", "中国银行 储蓄卡4049"),
            row("2025/03/10 12:00", "支出", "-200", "三餐", "", "中国银行 储蓄卡4049"),
        )
        val ledger = ledgerOf(result)
        val months = netAssetsByMonth(ledger, "CNY", 4, today = LocalDate.of(2025, 3, 20))
        assertEquals(listOf(YearMonth.of(2024, 12), YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 3)), months.map { it.first })
        assertEquals(listOf(0L, 100000L, 70000L, 50000L), months.map { it.second })
    }

    /** The whole of a real export, when `ICOST_EXPORT` names one; it stays out of the repository. */
    @Test fun readsARealExport() {
        val path = System.getenv("ICOST_EXPORT")
        assumeTrue(path != null && File(path).exists())
        val table = File(path!!).inputStream().use(Xlsx::read)
        val bundled = File("src/main/assets/ledger-rates.json").readText().let(Rates::parseSeries)
        val result = ICostImport.read(table, zone, Rates.EMPTY.with(bundled)) { "id${next++}" }
        println(result.report)
        result.accounts.forEach { println("${it.name}\t${it.kind}\t${it.currency}") }
        val ledger = Ledger(result.books, result.accounts, result.categories, result.txns.sortedByDescending { it.time }, Rates.EMPTY.with(bundled))
        result.books.forEach { book ->
            val s = summarize(ledger.entries.filter { it.txn.book == book.id }, ledger.rates, book.currency, null, null)
            println("${book.name}\texpense ${formatCents(s.expense)}\tincome ${formatCents(s.income)}\tunconverted ${s.unconverted}")
        }
        assertEquals(0, result.report.skipped.size)
        assertNotNull(result.txns.firstOrNull())
    }
}
