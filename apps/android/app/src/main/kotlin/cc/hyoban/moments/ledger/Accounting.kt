package cc.hyoban.moments.ledger

import java.time.LocalDate

/** The category a transfer's fee is summed under, as an expense. */
const val FEE_CATEGORY = "@fee"

/** The category a transfer's discount is summed under, as an income. */
const val DISCOUNT_CATEGORY = "@discount"

enum class Flow { EXPENSE, INCOME }

/**
 * What one bill adds to the books' sums, in its own currency: a refund takes from its
 * expense's category up to what was spent, and what it brings back beyond that is earned.
 */
data class Entry(val txn: Txn, val flow: Flow, val category: String?, val amount: Long)

internal fun entriesOf(ledger: Ledger): List<Entry> {
    val out = mutableListOf<Entry>()
    // How much of each expense is still there to be refunded, as its refunds come in order.
    val left = mutableMapOf<String, Long>()
    for (txn in ledger.txns.asReversed()) {
        if (txn.excluded) continue
        when (txn.kind) {
            TxnKind.EXPENSE -> when (txn.reimburse) {
                Reimburse.NONE -> out += Entry(txn, Flow.EXPENSE, txn.category, txn.amount)
                // Not one's own spending until it is known how much comes back.
                Reimburse.PENDING -> Unit
                Reimburse.DONE -> {
                    val back = ledger.returns[txn.id].orEmpty().filter { it.kind == TxnKind.REIMBURSEMENT }.sumOf { it.amount }
                    val net = txn.amount - back
                    if (net > 0) out += Entry(txn, Flow.EXPENSE, txn.category, net)
                    if (net < 0) out += Entry(txn, Flow.INCOME, txn.category, -net)
                }
            }
            TxnKind.INCOME -> out += Entry(txn, Flow.INCOME, txn.category, txn.amount)
            TxnKind.REFUND -> {
                val expense = txn.related?.let(ledger.txnsById::get)
                if (expense == null) {
                    out += Entry(txn, Flow.EXPENSE, txn.category, -txn.amount)
                } else {
                    val room = left.getOrPut(expense.id) { expense.amount }
                    val back = minOf(room, txn.amount)
                    left[expense.id] = room - back
                    if (back > 0) out += Entry(txn, Flow.EXPENSE, expense.category, -back)
                    if (txn.amount > back) out += Entry(txn, Flow.INCOME, expense.category, txn.amount - back)
                }
            }
            // Summed with its expense; one for no expense is earned.
            TxnKind.REIMBURSEMENT -> if (txn.related == null || ledger.txnsById[txn.related] == null) out += Entry(txn, Flow.INCOME, txn.category, txn.amount)
            TxnKind.TRANSFER -> {
                if (txn.fee > 0) out += Entry(txn, Flow.EXPENSE, FEE_CATEGORY, txn.fee)
                if (txn.discount > 0) out += Entry(txn, Flow.INCOME, DISCOUNT_CATEGORY, txn.discount)
            }
        }
    }
    return out
}

/**
 * What a bill does to each account it touches, in that account's currency: paid from, paid
 * into, or for a transfer, out of one and into the other, its fee out of and its discount
 * into theirs.
 */
fun effectsOf(txn: Txn, accounts: Map<String, Account>, rates: Rates): List<Pair<String, Long>> {
    fun into(account: String, cents: Long, recorded: Long? = null): Pair<String, Long> {
        val currency = accounts[account]?.currency ?: txn.currency
        val value = when {
            currency == txn.currency -> cents
            recorded != null -> recorded
            else -> rates.convert(cents, txn.currency, currency, txn.date) ?: cents
        }
        return account to value
    }
    val single = txn.splits.isEmpty()
    return when (txn.kind) {
        TxnKind.EXPENSE -> txn.payers.map { into(it.account, it.amount, txn.accountAmount.takeIf { single }).let { (a, v) -> a to -v } }
        TxnKind.INCOME, TxnKind.REFUND, TxnKind.REIMBURSEMENT -> txn.payers.map { into(it.account, it.amount, txn.accountAmount.takeIf { single }) }
        TxnKind.TRANSFER -> buildList {
            val from = txn.account
            txn.account?.let { add(into(it, txn.amount, txn.accountAmount).let { (a, v) -> a to -v }) }
            txn.toAccount?.let { add(into(it, txn.amount, txn.toAmount)) }
            if (txn.fee > 0) (txn.feeAccount ?: from)?.let { add(into(it, txn.fee).let { (a, v) -> a to -v }) }
            if (txn.discount > 0) (txn.discountAccount ?: from)?.let { add(into(it, txn.discount)) }
        }
    }
}

internal fun balancesOf(ledger: Ledger): Map<String, Long> {
    val out = ledger.accounts.associate { it.id to it.opening }.toMutableMap()
    for (txn in ledger.txns) {
        effectsOf(txn, ledger.accountsById, ledger.rates).forEach { (account, cents) -> out[account] = (out[account] ?: 0) + cents }
    }
    return out
}

/** The sums of one book over a stretch of days, in [currency]. */
data class Summary(
    val expense: Long,
    val income: Long,
    /** By first-level category, largest first. */
    val expenseByCategory: List<Pair<String?, Long>>,
    val incomeByCategory: List<Pair<String?, Long>>,
    /** By day, for the days that had any. */
    val expenseByDay: Map<LocalDate, Long>,
    val incomeByDay: Map<LocalDate, Long>,
    /** How many entries came in a currency with no rate, summed as if it were [currency]. */
    val unconverted: Int,
) {
    val balance get() = income - expense
}

/**
 * The sums of [entries] from [start] up to but not including [end] (all of them when either
 * is null) in [currency], those of first-level categories by [topOf].
 */
fun summarize(
    entries: List<Entry>,
    rates: Rates,
    currency: String,
    start: LocalDate?,
    end: LocalDate?,
    topOf: (String?) -> String? = { it },
): Summary {
    var expense = 0L
    var income = 0L
    var unconverted = 0
    val expenseBy = mutableMapOf<String?, Long>()
    val incomeBy = mutableMapOf<String?, Long>()
    val expenseDays = mutableMapOf<LocalDate, Long>()
    val incomeDays = mutableMapOf<LocalDate, Long>()
    for (entry in entries) {
        val date = entry.txn.date
        if (start != null && date < start) continue
        if (end != null && date >= end) continue
        val value = rates.convert(entry.amount, entry.txn.currency, currency, date) ?: entry.amount.also { unconverted++ }
        val top = if (entry.category?.startsWith("@") == true) entry.category else topOf(entry.category)
        when (entry.flow) {
            Flow.EXPENSE -> {
                expense += value
                expenseBy[top] = (expenseBy[top] ?: 0) + value
                expenseDays[date] = (expenseDays[date] ?: 0) + value
            }
            Flow.INCOME -> {
                income += value
                incomeBy[top] = (incomeBy[top] ?: 0) + value
                incomeDays[date] = (incomeDays[date] ?: 0) + value
            }
        }
    }
    return Summary(
        expense,
        income,
        expenseBy.entries.filter { it.value != 0L }.sortedByDescending { it.value }.map { it.key to it.value },
        incomeBy.entries.filter { it.value != 0L }.sortedByDescending { it.value }.map { it.key to it.value },
        expenseDays,
        incomeDays,
        unconverted,
    )
}

/** The net of what [txns] spent and earned, in [currency]: what a trip, a place or a day cost. */
fun spendingOf(ledger: Ledger, txns: Collection<Txn>, currency: String): Summary {
    val ids = txns.map { it.id }.toSet()
    // A refund of one of them counts with it, wherever it was written.
    val entries = ledger.entries.filter { it.txn.id in ids || (it.txn.related != null && it.txn.related in ids) }
    return summarize(entries, ledger.rates, currency, null, null, ledger::topCategory)
}

/** The net assets, what is held and what is owed, in [currency] at the latest rates. */
data class Assets(val net: Long, val held: Long, val owed: Long)

fun assetsOf(ledger: Ledger, currency: String, today: LocalDate = LocalDate.now()): Assets {
    var held = 0L
    var owed = 0L
    for (account in ledger.accounts) {
        if (!account.counted || account.archived) continue
        val balance = ledger.balances[account.id] ?: 0
        val value = ledger.rates.convert(balance, account.currency, currency, today) ?: balance
        if (value >= 0) held += value else owed -= value
    }
    return Assets(held - owed, held, owed)
}
