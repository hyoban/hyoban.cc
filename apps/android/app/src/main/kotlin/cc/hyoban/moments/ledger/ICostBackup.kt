package cc.hyoban.moments.ledger

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.lingala.zip4j.ZipFile

/** One row of a table of iCost's database, each column as text, null where it is. */
typealias ICostRow = Map<String, String?>


/**
 * A backup of iCost, its `.backup` file: its own SQLite database in a zip sealed with a password
 * the app carries. Unlike the bill export it names every bill, so refunds, reimbursements and a
 * transfer's discount are put back with the bill iCost tied them to, a bill paid from several
 * accounts stays one bill, and the accounts come with their kinds and balances.
 */
object ICostBackup {
    private const val DATABASE = "database.db"
    private val PASSWORD = "HELLO_ICOST".toCharArray()

    /** Whether [file] is an iCost backup rather than a bill export. */
    fun isBackup(file: File): Boolean = runCatching { ZipFile(file).use { zip -> zip.fileHeaders.any { it.fileName == DATABASE } } }.getOrDefault(false)

    /** Takes the database out of the backup [file] into [into]. */
    fun unpack(file: File, into: File) {
        ZipFile(file, PASSWORD).use { it.extractFile(DATABASE, into.parentFile!!.path, into.name) }
    }

    /** What the import reads of iCost's database, live rows only: a deleted one has status 2. */
    class Tables(
        val books: List<ICostRow>,
        val defaultBook: ICostRow?,
        val accounts: List<ICostRow>,
        /** Deleted ones too, as old bills still name them. */
        val categories: List<ICostRow>,
        val tags: List<ICostRow>,
        /** Oldest first. */
        val records: List<ICostRow>,
    )

    /** Reads the [Tables] with [query], which runs an SQL query on the database and gives its rows. */
    fun tables(query: (String) -> List<ICostRow>) = Tables(
        books = query("SELECT pKey, title, currency, isArchived FROM ICMutipleBookModel WHERE status != 2 ORDER BY timestamp"),
        defaultBook = query("SELECT title, currency FROM ICMutipleBookDefaultStyleModel LIMIT 1").firstOrNull(),
        accounts = query(
            "SELECT pKey, accountName, classifyName, accountType, depositType, price, currency, remark, isHidden, isHiddenCount, isNoCount, isArchieve " +
                "FROM ICAssetModel WHERE status != 2 ORDER BY timestamp",
        ),
        categories = query("SELECT pKey, title, releatedTypePkey FROM ICRecordTypeModelV2"),
        tags = query("SELECT pKey, title FROM ICRecordTagModel"),
        records = query(
            "SELECT pKey, type, price, currency, CAST(timestamp AS INTEGER) AS time, typePkey, typeIconTitle, remark, mutipleBookPkey, " +
                "accountID, fromAccountID, toAccountID, extendedData FROM ICRecordModel WHERE status != 2 ORDER BY timestamp",
        ),
    )

    // A record's type in iCost's database.
    private const val EXPENSE = "0"
    private const val INCOME = "1"
    private const val ADJUSTMENT = "2"
    private const val TRANSFER = "3"
    private const val CLAIM = "4"
    private const val REFUND = "5"

    // An account's type, and of a person's, which way the money went.
    private const val CREDIT = "1"
    private const val PERSON = "2"
    private const val STORED = "3"
    private const val INVESTMENT = "4"
    private const val BORROWED = "0"

    // Where a reimbursement stands: the expense to be paid back, and the money that came back.
    private const val CLAIMED = "1"
    private const val CLAIM_PAID = "2"

    /**
     * Reads [tables] into the ledger beside what [existing] holds, as [ICostImport.read] does an
     * export: a book, an account or a category of the same name is taken as it is, and a bill
     * already there is left out. A new account opens at what makes its balance iCost's, so a
     * balance adjustment needs no bill of its own.
     */
    fun read(tables: Tables, zone: ZoneId, rates: Rates, existing: Ledger = Ledger(), newId: () -> String): ICostImport.Result {
        val skipped = mutableListOf<Pair<Int, String>>()
        val records = tables.records
        val extended = records.associate { row -> row.id to extendedOf(row["extendedData"]) }
        fun ICostRow.ext(key: String) = extended[id]?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun ICostRow.cents(key: String) = parseCents(this[key]) ?: 0

        // Books by name, the default one, whose bills name none, by its own.
        val defaultName = tables.defaultBook?.title?.takeIf { it.isNotBlank() } ?: "默认账本"
        val bookNames = tables.books.associate { it.id to it.title.ifBlank { defaultName } }
        fun bookName(row: ICostRow): String? {
            val key = row["mutipleBookPkey"]
            return if (key.isNullOrEmpty()) defaultName else bookNames[key]
        }
        val books = mutableMapOf<String, Book>()
        existing.books.forEach { books[it.name] = it }
        records.groupBy(::bookName).forEach { (name, bookRows) ->
            if (name == null || name in books) return@forEach
            val icost = tables.books.firstOrNull { it.title == name } ?: tables.defaultBook.takeIf { name == defaultName }
            val currency = icost?.get("currency")?.takeIf { it.isNotBlank() }
                ?: bookRows.groupingBy { it.currency }.eachCount().maxBy { it.value }.key
            books[name] = Book(newId(), name, currency, position = books.size, archived = icost?.get("isArchived") == "1")
        }

        // Accounts by name, of the kind iCost says, their balances set at the end. Those of one
        // name are one, archived only when all of them are and counted when any is, and
        // otherwise as the newest one still in use says, an old one being hidden for it.
        val icostAccounts = tables.accounts.associateBy { it.id }
        val accounts = mutableMapOf<String, Account>()
        existing.accounts.forEach { accounts[it.name] = it }
        for ((name, rows) in tables.accounts.groupBy { it.accountName }) {
            if (name.isBlank() || name in accounts) continue
            val archived = rows.all { it["isHidden"] == "1" || it["isArchieve"] == "1" }
            val row = rows.lastOrNull { archived || (it["isHidden"] != "1" && it["isArchieve"] != "1") } ?: rows.last()
            val classify = row["classifyName"].orEmpty().trim()
            val kind = when (row["accountType"]) {
                CREDIT -> if (classify.contains("贷")) AccountKind.LOAN else AccountKind.CREDIT
                PERSON -> AccountKind.PERSON
                STORED -> AccountKind.STORED
                INVESTMENT -> AccountKind.INVESTMENT
                else -> ICostImport.kindOf("$classify $name", person = false)
            }
            accounts[name] = Account(
                newId(), name, kind, row["currency"].orEmpty().ifBlank { "CNY" },
                counted = rows.any { it["isHiddenCount"] != "1" && it["isNoCount"] != "1" },
                archived = archived,
                note = row["remark"].orEmpty(),
                position = accounts.size,
            )
        }
        fun accountName(key: String?) = key?.let(icostAccounts::get)?.accountName
        fun accountId(key: String?) = accountName(key)?.let(accounts::get)?.id

        // Categories of each book by name, the first level and the second under it.
        val icostCategories = tables.categories.associateBy { it.id }
        val categories = mutableMapOf<String, Category>()
        existing.categories.forEach { category ->
            val parent = category.parent?.let(existing.categoriesById::get)?.name
            categories[ICostImport.categoryKey(category.book, category.type, parent, category.name)] = category
        }
        fun category(book: String, type: CategoryType, row: ICostRow): String? {
            val icost = row["typePkey"]?.let(icostCategories::get)
            val parent = icost?.get("releatedTypePkey")?.let(icostCategories::get)
            val name = (parent ?: icost)?.title ?: row["typeIconTitle"]?.trim()
            val sub = if (parent != null) icost.title else null
            if (name.isNullOrBlank()) return null
            val bookId = books.getValue(book).id
            val top = categories.getOrPut(ICostImport.categoryKey(bookId, type, null, name)) {
                Category(newId(), bookId, type, name, position = categories.size)
            }
            if (sub.isNullOrBlank()) return top.id
            return categories.getOrPut(ICostImport.categoryKey(bookId, type, name, sub)) {
                Category(newId(), bookId, type, sub, parent = top.id, position = categories.size)
            }.id
        }

        val tagTitles = tables.tags.associate { it.id to it.title }
        var converted = 0
        /** What [cents] of [currency] came to in [account]'s currency, where that isn't [currency]. */
        fun inAccount(account: String?, cents: Long, currency: String, time: Long): Long? {
            val target = existing.accountsById[account] ?: accounts.values.firstOrNull { it.id == account } ?: return null
            if (target.currency == currency) return null
            val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
            return rates.convert(cents, currency, target.currency, day)?.also { converted++ }
        }

        // What ties one record to another: the parts of a bill paid from several accounts, the
        // claim an expense and the money paid back for it share, the refunds of an expense, the
        // transfer a discount came with.
        val live = records.associateBy { it.id }
        val parts = records.filter { it.ext("combinationFlag") == "1" }
            .associate { parent -> parent.id to parent.ext("combinationRelatedPkey").orEmpty().split(',').mapNotNull { live[it.trim()] } }
        val partIds = parts.values.flatten().map { it.id }.toSet()
        val claimPaid = records.filter { it.type == CLAIM && it.ext("claimStatusType") == CLAIM_PAID }.mapNotNull { it.ext("claimId") }.toSet()
        val refundGroups = records.filter { it.type == REFUND }.mapNotNull { it.ext("refundRelatedPkey") }.toSet()
        val refunded = records.filter { it.type != REFUND }
            .flatMap { row -> row.ext("refundRelatedPkey").orEmpty().split(',').map { it.trim() }.filter { it in refundGroups }.map { it to row.id } }
            .toMap()
        val claims = records.filter { it.type == CLAIM && it.ext("claimStatusType") == CLAIMED }.mapNotNull { row -> row.ext("claimId")?.let { it to row.id } }.toMap()
        // A refund beyond what was spent comes as 退款收入 beside the refund that holds all of it.
        val refundIncome = records.filter { it.type == REFUND && it["typeIconTitle"] == "退款收入" }
        val surplus = refundIncome.filter { income ->
            records.any { it.id != income.id && it.type == REFUND && it["typeIconTitle"] != "退款收入" && it.ext("refundRelatedPkey") == income.ext("refundRelatedPkey") }
        }.map { it.id }.toSet()
        val discounts = records.filter { it.type == TRANSFER && it["typeIconTitle"].orEmpty().endsWith("优惠") }
        val discountIds = discounts.map { it.id }.toSet()
        // A transfer names the discount that came with it.
        val discounted = records.filter { it.type == TRANSFER && it.id !in discountIds }
            .mapNotNull { row -> row.ext("discountRelatedPkey")?.let { it to row.id } }.toMap()

        val txns = mutableListOf<Txn>()
        val ofRecord = mutableMapOf<String, Txn>()
        val related = mutableMapOf<String, String>()
        var folded = 0
        // The accounts balance adjustments were made to: a new one's go into its opening balance.
        val adjustedAccounts = mutableListOf<String?>()
        records.forEachIndexed { index, row ->
            val number = index + 1
            val book = bookName(row)
            val time = row["time"]?.toLongOrNull()
            val amount = parseCents(row["price"])
            if (book == null || time == null || amount == null) {
                skipped += number to listOfNotNull(row["typeIconTitle"], row["price"]).joinToString(" ")
                return@forEachIndexed
            }
            val tags = row.ext("tagKeys").orEmpty().split(',').mapNotNull { tagTitles[it.trim()] }
            val base = Txn(
                id = newId(),
                book = books.getValue(book).id,
                kind = TxnKind.EXPENSE,
                time = time,
                offsetMinutes = zone.rules.getOffset(Instant.ofEpochMilli(time)).totalSeconds / 60,
                amount = amount,
                currency = row.currency,
                note = noteWithTags(row["remark"].orEmpty(), tags),
                excluded = row.ext("isNotCountOutcome") == "1",
            )
            fun paidWith(account: String?, kind: TxnKind) = base.copy(
                kind = kind,
                account = accountId(account),
                accountAmount = inAccount(accountId(account), amount, row.currency, time),
            )
            val claim = row.ext("claimStatusType")
            val txn = when {
                row.id in partIds -> null
                row.type == EXPENSE || (row.type == CLAIM && claim != CLAIM_PAID) -> {
                    val split = parts[row.id].orEmpty().mapNotNull { part -> accountId(part["accountID"])?.let { Split(it, part.cents("price")) } }
                    val reimburse = when {
                        row.type != CLAIM -> Reimburse.NONE
                        row.ext("claimId") in claimPaid -> Reimburse.DONE
                        else -> Reimburse.PENDING
                    }
                    (if (split.isEmpty()) paidWith(row["accountID"], TxnKind.EXPENSE) else base.copy(splits = split)).copy(
                        category = category(book, CategoryType.EXPENSE, row),
                        discount = row.cents("discountPrice"),
                        reimburse = reimburse,
                    )
                }
                row.type == INCOME -> paidWith(row["accountID"], TxnKind.INCOME).copy(category = category(book, CategoryType.INCOME, row))
                row.type == ADJUSTMENT -> {
                    adjustedAccounts += accountName(row["accountID"])
                    null
                }
                row.id in discountIds -> null
                row.type == TRANSFER -> {
                    val from = row["fromAccountID"]?.let(icostAccounts::get)
                    val to = row["toAccountID"]?.let(icostAccounts::get)
                    val transfer = when {
                        to?.get("accountType") == CREDIT || row["typeIconTitle"] == "还款" -> TransferType.REPAY
                        to?.get("accountType") == PERSON -> if (to["depositType"] == BORROWED) TransferType.REPAY else TransferType.LEND
                        from?.get("accountType") == PERSON -> if (from["depositType"] == BORROWED) TransferType.BORROW else TransferType.COLLECT
                        else -> TransferType.TRANSFER
                    }
                    val fee = row.cents("feePrice")
                    val account = accountId(row["fromAccountID"])
                    val toAccount = accountId(row["toAccountID"])
                    base.copy(
                        kind = TxnKind.TRANSFER,
                        transfer = transfer,
                        account = account,
                        accountAmount = inAccount(account, amount, row.currency, time),
                        toAccount = toAccount,
                        toAmount = inAccount(toAccount, amount, row.currency, time),
                        fee = fee,
                        feeAccount = accountId(row.ext("feeInAssetPkey")).takeIf { fee > 0 },
                    )
                }
                row.type == CLAIM -> {
                    val expense = row.ext("claimId")?.let(claims::get)
                    expense?.let { related[base.id] = it }
                    val account = row["toAccountID"]?.takeIf { it.isNotEmpty() } ?: row["accountID"]
                    paidWith(account, TxnKind.REIMBURSEMENT).copy(category = expense?.let(live::get)?.let { category(book, CategoryType.EXPENSE, it) })
                }
                row.type == REFUND && row.id in surplus -> {
                    folded++
                    null
                }
                row.type == REFUND -> {
                    val expense = row.ext("refundRelatedPkey")?.let(refunded::get)
                    expense?.let { related[base.id] = it }
                    val kind = if (row["typeIconTitle"] == "退款收入") TxnKind.INCOME else TxnKind.REFUND
                    val categoryOf = expense?.let(live::get)?.takeIf { kind == TxnKind.REFUND } ?: row
                    paidWith(row["accountID"], kind).copy(
                        category = category(book, if (kind == TxnKind.REFUND) CategoryType.EXPENSE else CategoryType.INCOME, categoryOf),
                    )
                }
                else -> {
                    // A type this doesn't know.
                    skipped += number to "${row.type} ${row["typeIconTitle"].orEmpty()}"
                    null
                }
            } ?: return@forEachIndexed
            txns += txn
            ofRecord[row.id] = txn
        }
        // Related records by iCost's ids so far; now by the bills made of them.
        related.replaceAll { _, expense -> ofRecord[expense]?.id ?: expense }
        related.values.removeAll { id -> txns.none { it.id == id } }

        // A transfer's discount goes back to its transfer, else stays an income of its own.
        for (row in discounts) {
            val key = discounted[row.id] ?: row.ext("discountRelatedPkey")
            val transfer = key?.let(ofRecord::get)
            val index = txns.indexOf(transfer)
            if (key != null && transfer != null && index >= 0) {
                txns[index] = transfer.copy(discount = transfer.discount + row.cents("price"), discountAccount = accountId(row["accountID"]))
                ofRecord[key] = txns[index]
                folded++
                continue
            }
            val book = bookName(row) ?: continue
            val time = row["time"]?.toLongOrNull() ?: continue
            txns += Txn(
                id = newId(), book = books.getValue(book).id, kind = TxnKind.INCOME, time = time,
                offsetMinutes = zone.rules.getOffset(Instant.ofEpochMilli(time)).totalSeconds / 60,
                amount = row.cents("price"), currency = row.currency, note = row["remark"].orEmpty(),
                category = category(book, CategoryType.INCOME, row), account = accountId(row["accountID"]),
            )
        }

        val result = ICostImport.settle(
            existing, books.values, accounts.values, categories.values, txns, related,
            ICostImport.Report(records.size, 0, 0, 0, 0, 0, 0, 0, folded, 0, converted, skipped, balances = true),
        )

        // Each account iCost has opens where the bills iCost also has leave it at iCost's balance,
        // those of one name summed as they are taken for one; bills kept only here move it on.
        val target = tables.accounts.groupBy { it.accountName }.mapValues { (_, rows) -> rows.sumOf { signedCents(it["price"]) } }
        val all = (existing.accounts + result.accounts).associateBy { it.id }
        val ofBackup = existing.txns.filter { it.id in result.matched } + result.txns
        val moved = balancesOf(Ledger(accounts = all.values.map { it.copy(opening = 0) }, txns = ofBackup, rates = rates))
        val newIds = result.accounts.map { it.id }.toSet()
        val opened = all.values.mapNotNull { account ->
            val balance = target[account.name] ?: return@mapNotNull account.takeIf { it.id in newIds }
            account.copy(opening = balance - (moved[account.id] ?: 0)).takeIf { it.id in newIds || it.opening != account.opening }
        }
        return result.copy(
            accounts = opened,
            report = result.report.copy(
                adjusted = adjustedAccounts.count { it in target },
                calibrated = opened.count { it.id !in newIds },
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun extendedOf(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** Cents with their sign, a credit account's balance being below zero. */
    private fun signedCents(text: String?): Long {
        val cents = parseCents(text) ?: return 0
        return if (text!!.trim().startsWith("-")) -cents else cents
    }

    private val ICostRow.id get() = this["pKey"].orEmpty()
    private val ICostRow.title get() = this["title"].orEmpty().trim()
    /** An account's name, iCost keeping some with spaces at the end. */
    private val ICostRow.accountName get() = this["accountName"].orEmpty().trim().ifBlank { this["classifyName"].orEmpty().trim() }
    private val ICostRow.type get() = this["type"]
    private val ICostRow.currency get() = this["currency"].orEmpty().uppercase().ifEmpty { "CNY" }
}
