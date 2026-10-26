package cc.hyoban.moments.ledger

import java.io.Writer
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The whole ledger as a file of the user's own: what restoring it, here or on another phone, reads back. */
@Serializable
data class LedgerBackup(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val books: List<Book>,
    val accounts: List<Account>,
    val categories: List<Category>,
    val txns: List<Txn>,
) {
    companion object {
        const val FORMAT = "cc.hyoban.moments.ledger"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

        fun of(ledger: Ledger) = LedgerBackup(books = ledger.books, accounts = ledger.accounts, categories = ledger.categories, txns = ledger.txns)

        fun encode(backup: LedgerBackup): String = json.encodeToString(serializer(), backup)

        fun decode(text: String): LedgerBackup = json.decodeFromString(serializer(), text).also {
            require(it.format == FORMAT) { "Not a ledger backup" }
        }
    }
}

/**
 * The bills as CSV, a row each with the columns iCost's own export has, for a spreadsheet to
 * read: not a backup, which [LedgerBackup] is.
 */
fun writeCsv(ledger: Ledger, writer: Writer) {
    val time = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    fun cell(text: String) = if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    fun amount(cents: Long) = formatCents(cents, always = true).replace(",", "")
    val books = ledger.books.associate { it.id to it.name }
    writer.write("﻿")
    writer.write(listOf("日期", "类型", "金额", "一级分类", "二级分类", "账户1", "账户2", "备注", "货币", "标签", "账本", "退款", "优惠", "手续费").joinToString(",") + "\r\n")
    for (txn in ledger.txns.asReversed()) {
        val category = ledger.categoriesById[txn.category]
        val parent = category?.parent?.let(ledger.categoriesById::get)
        val type = when (txn.kind) {
            TxnKind.EXPENSE -> if (txn.reimburse == Reimburse.DONE) "已报销" else "支出"
            TxnKind.INCOME -> "收入"
            TxnKind.REFUND -> "退款入账"
            TxnKind.REIMBURSEMENT -> "报销入账"
            TxnKind.TRANSFER -> when (txn.transfer) {
                TransferType.TRANSFER -> "转账"
                TransferType.REPAY -> "还款"
                TransferType.BORROW -> "借入"
                TransferType.LEND -> "借出"
                TransferType.COLLECT -> "收款"
            }
        }
        val name = { id: String? -> id?.let(ledger.accountsById::get)?.name.orEmpty() }
        val payer = if (txn.splits.isNotEmpty()) txn.splits.joinToString("/") { "${name(it.account)}[-${amount(it.amount)}]" } else name(txn.account)
        val refunded = ledger.returns[txn.id].orEmpty().filter { it.kind == TxnKind.REFUND }.sumOf { it.amount }
        val signed = if (txn.kind == TxnKind.EXPENSE) "-" + amount(txn.amount) else amount(txn.amount)
        writer.write(
            listOf(
                txn.local.format(time), type, signed,
                parent?.name ?: category?.name.orEmpty(), if (parent != null) category?.name.orEmpty() else "",
                payer, name(txn.toAccount), txn.note, txn.currency, txn.tags.joinToString(" ") { "#$it" }, books[txn.book].orEmpty(),
                if (refunded > 0) amount(refunded) else "", if (txn.discount > 0) amount(txn.discount) else "", if (txn.fee > 0) amount(txn.fee) else "",
            ).joinToString(",", transform = ::cell) + "\r\n",
        )
    }
}
