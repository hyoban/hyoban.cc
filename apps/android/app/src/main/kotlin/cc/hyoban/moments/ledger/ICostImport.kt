package cc.hyoban.moments.ledger

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * A bill export of iCost (https://help.icostapp.com), read into the ledger. iCost writes one
 * row per bill, and a few more it derives: a transfer's discount as an income of its own, a
 * refund beyond what was spent as 退款收入. Those are folded back into the bill they came from,
 * and each refund and reimbursement is matched to its expense by its book, category, amount and
 * time, the export naming no bill by id.
 */
object ICostImport {
    /** What came of the import, for the page to show before it is written. */
    data class Report(
        val rows: Int,
        val books: Int,
        val accounts: Int,
        val txns: Int,
        val refundsMatched: Int,
        val refundsLoose: Int,
        val reimbursementsMatched: Int,
        val reimbursementsLoose: Int,
        /** Rows folded into the bill iCost derived them from. */
        val folded: Int,
        /** Rows already in the ledger, left out. */
        val duplicates: Int,
        /** Amounts moved between currencies at the day's rate, the export having only one of them. */
        val converted: Int,
        /** Rows that couldn't be read, by their row number and why. */
        val skipped: List<Pair<Int, String>>,
    )

    data class Result(
        val books: List<Book>,
        val accounts: List<Account>,
        val categories: List<Category>,
        val txns: List<Txn>,
        val report: Report,
    )

    /** The columns by the names iCost gives them, in Chinese and in English. */
    private val COLUMNS = mapOf(
        "date" to listOf("日期", "Date", "Time"),
        "type" to listOf("类型", "Type"),
        "amount" to listOf("金额", "Amount"),
        "category" to listOf("一级分类", "Primary Category", "Category"),
        "subcategory" to listOf("二级分类", "Secondary Category", "Subcategory"),
        "account" to listOf("账户1", "Account 1", "Account1"),
        "account2" to listOf("账户2", "Account 2", "Account2"),
        "note" to listOf("备注", "Note"),
        "currency" to listOf("货币", "Currency"),
        "tags" to listOf("标签", "Tags", "Tag"),
        "book" to listOf("账本", "Ledger", "Book"),
        "refund" to listOf("退款", "Refund"),
        "discount" to listOf("优惠", "Discount"),
        "fee" to listOf("手续费", "Fee"),
    )

    private val TIME_FORMATS = listOf(
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "yyyy年MM月dd日 HH:mm:ss", "yyyy年MM月dd日 HH:mm", "yyyy年MM月dd日",
    ).map { DateTimeFormatter.ofPattern(it) }

    private fun parseTime(text: String): LocalDateTime? {
        val value = text.trim()
        for (format in TIME_FORMATS) {
            runCatching { return LocalDateTime.parse(value, format) }
            runCatching { return java.time.LocalDate.parse(value, format).atStartOfDay() }
        }
        // A spreadsheet's own date, days since 1899-12-30 and the time as a fraction of a day.
        value.toDoubleOrNull()?.takeIf { it > 20000 }?.let { serial ->
            val seconds = Math.round(serial * 86400)
            return LocalDateTime.of(1899, 12, 30, 0, 0).plusSeconds(seconds)
        }
        return null
    }

    private class Row(
        val number: Int,
        val time: LocalDateTime,
        val stamp: String,
        val type: String,
        val amount: Long,
        val category: String,
        val subcategory: String,
        val account: String,
        val account2: String,
        val splits: List<Pair<String, Long>>,
        /** With the row's tags at its end. */
        val note: String,
        val currency: String,
        val book: String,
        val refund: Long,
        val discount: Long,
        val fee: Long,
    )

    /** `红包[-23.89]/中国银行 储蓄卡4049[-0.01]`: each account and its share. */
    internal fun parseSplits(text: String): List<Pair<String, Long>> {
        if (!text.contains('[')) return emptyList()
        return Regex("""([^/\[\]]+)\[([-+]?[\d.,]+)]""").findAll(text).map { match ->
            match.groupValues[1].trim() to (parseCents(match.groupValues[2]) ?: 0)
        }.toList()
    }

    internal fun parseTags(text: String): List<String> =
        text.split('#', ' ', '　').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private val TRANSFER_TYPES = mapOf(
        "转账" to TransferType.TRANSFER,
        "还款" to TransferType.REPAY,
        "借入" to TransferType.BORROW,
        "借出" to TransferType.LEND,
        "收款" to TransferType.COLLECT,
    )

    /** An account's kind from how it was used and what it is called. */
    internal fun kindOf(name: String, person: Boolean): AccountKind {
        fun has(vararg words: String) = words.any { name.contains(it, ignoreCase = true) }
        return when {
            person -> AccountKind.PERSON
            has("贷") -> AccountKind.LOAN
            has("信用", "花呗", "白条", "credit") -> AccountKind.CREDIT
            has("券", "积分", "极分") -> AccountKind.STORED
            has("现金", "纸币", "cash") -> AccountKind.CASH
            has("余额宝", "定存", "理财", "基金", "黄金", "股票", "证券", "USDT", "SafePal", "币", "投资") -> AccountKind.INVESTMENT
            has("储蓄", "借记", "银行", "OCBC", "debit", "bank") -> AccountKind.DEBIT
            has("微信", "支付宝", "余额", "红包", "PayPal", "wallet") -> AccountKind.WALLET
            has("卡") -> AccountKind.STORED
            else -> AccountKind.OTHER
        }
    }

    /**
     * Reads [table], its header first, into bills of the ledger, beside what [existing] holds:
     * a book, an account or a category of the same name is taken as it is, and a bill already
     * there is left out. Times are read in [zone]; amounts the export has in another currency
     * than their account's are moved over at [rates].
     */
    fun read(table: List<List<String>>, zone: ZoneId, rates: Rates, existing: Ledger = Ledger(), newId: () -> String): Result {
        val header = table.firstOrNull().orEmpty().map { it.trim() }
        val index = COLUMNS.mapValues { (_, names) -> header.indexOfFirst { it in names } }
        require(index.getValue("date") >= 0 && index.getValue("type") >= 0 && index.getValue("amount") >= 0) { "Not an iCost export" }
        fun List<String>.at(column: String) = index.getValue(column).takeIf { it >= 0 }?.let { getOrNull(it) }.orEmpty().trim()

        val skipped = mutableListOf<Pair<Int, String>>()
        val total = table.drop(1).count { cells -> cells.any { it.isNotBlank() } }
        val rows = table.drop(1).mapIndexedNotNull { i, cells ->
            val number = i + 2
            if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
            val time = parseTime(cells.at("date"))
            val amount = parseCents(cells.at("amount"))
            if (time == null || amount == null) {
                skipped += number to cells.joinToString(" ").take(80)
                return@mapIndexedNotNull null
            }
            val account = cells.at("account")
            val splits = parseSplits(account)
            Row(
                number, time, cells.at("date"), cells.at("type"), amount,
                cells.at("category"), cells.at("subcategory"),
                if (splits.isEmpty()) account else "", cells.at("account2"), splits,
                noteWithTags(cells.at("note"), parseTags(cells.at("tags"))), cells.at("currency").uppercase().ifEmpty { "CNY" },
                cells.at("book").ifEmpty { "默认账本" },
                parseCents(cells.at("refund")) ?: 0, parseCents(cells.at("discount")) ?: 0, parseCents(cells.at("fee")) ?: 0,
            )
        }

        // Books, in the order they first come, summed in the currency most of their bills are in.
        val books = mutableMapOf<String, Book>()
        existing.books.forEach { books[it.name] = it }
        rows.groupBy { it.book }.forEach { (name, bookRows) ->
            if (name !in books) {
                val currency = bookRows.groupingBy { it.currency }.eachCount().maxBy { it.value }.key
                books[name] = Book(newId(), name, currency, position = books.size)
            }
        }

        // Accounts: a person is one lent to or borrowed from; the currency, the one most bills
        // paid from it are in, or for one only ever paid into, those paid into it.
        val people = rows.filter { TRANSFER_TYPES[it.type] in listOf(TransferType.BORROW, TransferType.LEND, TransferType.COLLECT) }
            .flatMap { row ->
                when (TRANSFER_TYPES[row.type]) {
                    TransferType.LEND -> listOf(row.account2)
                    else -> listOf(row.account)
                }
            }.filter { it.isNotBlank() }.toSet()
        val paying = mutableMapOf<String, MutableMap<String, Int>>()
        val receiving = mutableMapOf<String, MutableMap<String, Int>>()
        for (row in rows) {
            (listOf(row.account) + row.splits.map { it.first }).filter { it.isNotBlank() }.forEach { name ->
                paying.getOrPut(name) { mutableMapOf() }.merge(row.currency, 1, Int::plus)
            }
            if (row.account2.isNotBlank()) receiving.getOrPut(row.account2) { mutableMapOf() }.merge(row.currency, 1, Int::plus)
        }
        val accounts = mutableMapOf<String, Account>()
        existing.accounts.forEach { accounts[it.name] = it }
        (paying.keys + receiving.keys).forEach { name ->
            if (name in accounts) return@forEach
            val currency = (paying[name] ?: receiving[name])!!.maxBy { it.value }.key
            accounts[name] = Account(newId(), name, kindOf(name, name in people), currency, position = accounts.size)
        }

        // Categories of each book, the first level and the second under it.
        val categories = mutableMapOf<String, Category>()
        existing.categories.forEach { category ->
            val parent = category.parent?.let(existing.categoriesById::get)?.name
            categories[categoryKey(category.book, category.type, parent, category.name)] = category
        }
        fun category(book: String, type: CategoryType, name: String, sub: String): String? {
            if (name.isBlank()) return null
            val bookId = books.getValue(book).id
            val top = categories.getOrPut(categoryKey(bookId, type, null, name)) {
                Category(newId(), bookId, type, name, position = categories.size)
            }
            if (sub.isBlank()) return top.id
            return categories.getOrPut(categoryKey(bookId, type, name, sub)) {
                Category(newId(), bookId, type, sub, parent = top.id, position = categories.size)
            }.id
        }

        var converted = 0
        fun offset(time: LocalDateTime) = zone.rules.getOffset(time).totalSeconds / 60
        fun epoch(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()
        fun accountId(name: String) = name.takeIf { it.isNotBlank() }?.let { accounts.getValue(it).id }
        /** What [cents] of [currency] came to in [name]'s currency, where that isn't [currency]. */
        fun inAccount(name: String, cents: Long, currency: String, time: LocalDateTime): Long? {
            val account = accounts[name] ?: return null
            if (account.currency == currency) return null
            return rates.convert(cents, currency, account.currency, time.toLocalDate())?.also { converted++ }
        }

        // The rows iCost derived from a transfer's discount, by the transfer's time and amount.
        val discountRows = rows.filter { it.type.endsWith("优惠") && it.type != "优惠" }.toMutableList()
        val refundRows = rows.filter { it.type == "退款入账" }
        val surplusRows = rows.filter { it.type == "退款收入" }.toMutableList()
        val reimbursementRows = rows.filter { it.type == "报销入账" }
        var folded = 0

        val txns = mutableListOf<Txn>()
        val ofRow = mutableMapOf<Row, Txn>()
        fun base(row: Row, kind: TxnKind) = Txn(
            id = newId(),
            book = books.getValue(row.book).id,
            kind = kind,
            time = epoch(row.time),
            offsetMinutes = offset(row.time),
            amount = row.amount,
            currency = row.currency,
            note = row.note,
        )

        for (row in rows) {
            val transfer = TRANSFER_TYPES[row.type]
            val txn = when {
                row.type == "支出" || row.type == "已报销" -> base(row, TxnKind.EXPENSE).copy(
                    category = category(row.book, CategoryType.EXPENSE, row.category, row.subcategory),
                    account = accountId(row.account),
                    accountAmount = inAccount(row.account, row.amount, row.currency, row.time),
                    splits = row.splits.map { Split(accounts.getValue(it.first).id, it.second) },
                    discount = row.discount,
                    reimburse = if (row.type == "已报销") Reimburse.DONE else Reimburse.NONE,
                )
                row.type == "收入" -> base(row, TxnKind.INCOME).copy(
                    category = category(row.book, CategoryType.INCOME, row.category, row.subcategory),
                    account = accountId(row.account),
                    accountAmount = inAccount(row.account, row.amount, row.currency, row.time),
                    splits = row.splits.map { Split(accounts.getValue(it.first).id, it.second) },
                )
                transfer != null -> {
                    val discountRow = if (row.discount > 0) {
                        discountRows.firstOrNull { it.stamp == row.stamp && it.amount == row.discount }?.also { discountRows.remove(it); folded++ }
                    } else null
                    base(row, TxnKind.TRANSFER).copy(
                        transfer = transfer,
                        account = accountId(row.account),
                        accountAmount = inAccount(row.account, row.amount, row.currency, row.time),
                        toAccount = accountId(row.account2),
                        toAmount = inAccount(row.account2, row.amount, row.currency, row.time),
                        fee = row.fee,
                        discount = row.discount,
                        discountAccount = discountRow?.account?.let(::accountId),
                    )
                }
                row.type == "退款入账" -> base(row, TxnKind.REFUND).copy(
                    category = category(row.book, CategoryType.EXPENSE, row.category, row.subcategory),
                    account = accountId(row.account),
                    accountAmount = inAccount(row.account, row.amount, row.currency, row.time),
                )
                row.type == "报销入账" -> base(row, TxnKind.REIMBURSEMENT).copy(
                    category = category(row.book, CategoryType.EXPENSE, row.category, row.subcategory),
                    account = accountId(row.account),
                    accountAmount = inAccount(row.account, row.amount, row.currency, row.time),
                )
                // Left for after the matching, which may fold them away.
                row.type == "退款收入" || row.type.endsWith("优惠") -> null
                else -> {
                    // A type this doesn't know.
                    skipped += row.number to row.type
                    null
                }
            } ?: continue
            txns += txn
            ofRow[row] = txn
        }

        // Each refunded expense in time order, its refunds those of its book and category after
        // it, or a day before at most, as one written down ahead of it: one that is exactly the
        // refund first, else the earliest that add up to it.
        val expenses = rows.filter { (it.type == "支出") && it.refund > 0 }
        val unmatched = refundRows.toMutableList()
        val related = mutableMapOf<Row, Row>()
        fun candidates(expense: Row, from: List<Row>) = from.filter {
            it.book == expense.book && it.category == expense.category && it.subcategory == expense.subcategory && it.time >= expense.time.minusDays(1)
        }
        val waiting = expenses.toMutableList()
        for (expense in expenses) {
            val exact = candidates(expense, unmatched).firstOrNull { it.amount == expense.refund } ?: continue
            related[exact] = expense
            unmatched.remove(exact)
            waiting.remove(expense)
        }
        for (expense in waiting) {
            var left = expense.refund
            val picked = mutableListOf<Row>()
            for (candidate in candidates(expense, unmatched)) {
                if (candidate.amount <= left) {
                    picked += candidate
                    left -= candidate.amount
                }
                if (left == 0L) break
            }
            if (left == 0L) picked.forEach { related[it] = expense; unmatched.remove(it) }
        }
        // A surplus iCost derived is what a refund brought back past its expense's amount.
        for ((refund, expense) in related) {
            val surplus = expense.refund - expense.amount
            if (surplus <= 0) continue
            surplusRows.firstOrNull { it.stamp == refund.stamp && abs(it.amount - surplus) <= 1 && it.book == refund.book }?.let {
                surplusRows.remove(it)
                folded++
            }
        }

        // Each reimbursement to the reimbursed expense of its book and category before it, of
        // the same amount if there is one.
        val reimbursed = rows.filter { it.type == "已报销" }.toMutableList()
        var reimbursementsMatched = 0
        for (row in reimbursementRows) {
            val before = reimbursed.filter { it.book == row.book && it.category == row.category && it.subcategory == row.subcategory && it.time <= row.time }
            val expense = before.firstOrNull { it.amount == row.amount } ?: before.firstOrNull() ?: continue
            reimbursed.remove(expense)
            related[row] = expense
            reimbursementsMatched++
        }

        // What is left of the derived rows: a surplus no refund accounts for is earned, a
        // discount no transfer took is an income of its own.
        for (row in surplusRows + discountRows) {
            txns += base(row, TxnKind.INCOME).copy(
                category = category(row.book, CategoryType.INCOME, row.category, row.subcategory),
                account = accountId(row.account),
            )
        }

        // A bill already in the ledger, as an earlier import of the same export left it, isn't
        // written twice: it takes the id it has there, so a refund or reimbursement new to this
        // export still finds the expense it is for. Two alike in one export match two there.
        val names = accounts.values.associate { it.id to it.name }
        fun key(txn: Txn, accountName: (String?) -> String?) =
            listOf(txn.time, txn.kind.value, txn.amount, txn.currency, txn.note, accountName(txn.account), accountName(txn.toAccount)).joinToString("|")
        val have = existing.txns.groupBy { txn -> key(txn) { id -> existing.accountsById[id]?.name } }.mapValues { it.value.map(Txn::id).toMutableList() }
        val existingId = mutableMapOf<String, String>()
        for (txn in txns) have[key(txn, names::get)]?.removeFirstOrNull()?.let { existingId[txn.id] = it }
        fun idOf(txn: Txn) = existingId[txn.id] ?: txn.id
        val relatedOf = related.entries.mapNotNull { (child, parent) ->
            val txn = ofRow[child] ?: return@mapNotNull null
            val expense = ofRow[parent] ?: return@mapNotNull null
            txn.id to idOf(expense)
        }.toMap()
        txns.replaceAll { txn -> txn.copy(id = idOf(txn), related = relatedOf[txn.id] ?: txn.related) }
        val known = existingId.values.toSet()

        val fresh = txns.filter { it.id !in known }

        val newBooks = books.values.filter { book -> existing.books.none { it.id == book.id } }
        val newAccounts = accounts.values.filter { account -> existing.accounts.none { it.id == account.id } }
        val newCategories = categories.values.filter { category -> existing.categories.none { it.id == category.id } }
        val refunds = fresh.filter { it.kind == TxnKind.REFUND }
        val reimbursements = fresh.filter { it.kind == TxnKind.REIMBURSEMENT }
        return Result(
            newBooks,
            newAccounts,
            newCategories,
            fresh,
            Report(
                rows = total,
                books = newBooks.size,
                accounts = newAccounts.size,
                txns = fresh.size,
                refundsMatched = refunds.count { it.related != null },
                refundsLoose = refunds.count { it.related == null },
                reimbursementsMatched = reimbursements.count { it.related != null },
                reimbursementsLoose = reimbursements.count { it.related == null },
                folded = folded,
                duplicates = txns.size - fresh.size,
                converted = converted,
                skipped = skipped,
            ),
        )
    }

    private fun categoryKey(book: String, type: CategoryType, parent: String?, name: String) = "$book\u0000${type.value}\u0000${parent.orEmpty()}\u0000$name"
}
