package cc.hyoban.moments.ledger

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

// The ledger: books of bills over accounts in their own currencies, kept on this phone alone.
// Amounts are whole cents (a hundredth of the currency's unit, whatever its minor unit), always
// positive; what a bill does to its accounts and to the books' sums follows from its kind.

/** How many of the latest bills say which accounts are picked most. */
private const val RECENT_FOR_USE = 200

/** A ledger book, its bills and categories its own, summed in its [currency]. */
@Serializable
data class Book(
    val id: String,
    val name: String,
    val currency: String = "CNY",
    val position: Int = 0,
    val archived: Boolean = false,
)

/** What sort of account: how the assets page groups it and whether its balance is owed. */
@Serializable
enum class AccountKind(val value: String) {
    CASH("cash"),
    DEBIT("debit"),
    CREDIT("credit"),
    WALLET("wallet"),
    STORED("stored"),
    INVESTMENT("investment"),
    LOAN("loan"),
    PERSON("person"),
    OTHER("other");

    /** Whether its balance is normally below zero, money owed rather than held. */
    val liability get() = this == CREDIT || this == LOAN

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: OTHER
    }
}

/**
 * Where money is: a card, a wallet, a loan, someone it is lent to. Its balance, in its
 * [currency], is [opening] plus what its bills did to it; a credit account's below zero.
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind = AccountKind.OTHER,
    val currency: String = "CNY",
    val opening: Long = 0,
    /** Counted in the net assets; a hidden one still keeps its bills. */
    val counted: Boolean = true,
    val archived: Boolean = false,
    val note: String = "",
    val position: Int = 0,
)

@Serializable
enum class CategoryType(val value: String) {
    EXPENSE("expense"),
    INCOME("income");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: EXPENSE
    }
}

/** A bill's category in one book, under a [parent] when it is a second-level one. */
@Serializable
data class Category(
    val id: String,
    val book: String,
    val type: CategoryType,
    val name: String,
    val parent: String? = null,
    val position: Int = 0,
)

@Serializable
enum class TxnKind(val value: String) {
    EXPENSE("expense"),
    INCOME("income"),
    TRANSFER("transfer"),
    /** Money back for an expense, its [Txn.related]: less spent, any excess earned. */
    REFUND("refund"),
    /** Money back for an expense to be reimbursed, its [Txn.related]. */
    REIMBURSEMENT("reimbursement");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: EXPENSE
    }
}

/** What a transfer is: the same move of money between two accounts, named as iCost names it. */
@Serializable
enum class TransferType(val value: String) {
    TRANSFER("transfer"),
    /** Paying off a credit or loan account, or a person lent from. */
    REPAY("repay"),
    BORROW("borrow"),
    LEND("lend"),
    /** Taking back what was lent. */
    COLLECT("collect");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: TRANSFER
    }
}

/** Whether an expense is to be paid back by someone else, and whether it has been. */
@Serializable
enum class Reimburse(val value: String) {
    NONE("none"),
    PENDING("pending"),
    DONE("done");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** A share of a bill paid from one account, in the bill's currency. */
@Serializable
data class Split(val account: String, val amount: Long)

@Serializable
data class Txn(
    val id: String,
    val book: String,
    val kind: TxnKind,
    /** When, in epoch milliseconds, and the offset it was written at, for its local day and time. */
    val time: Long,
    val offsetMinutes: Int,
    val amount: Long,
    val currency: String,
    val transfer: TransferType = TransferType.TRANSFER,
    val category: String? = null,
    /** The account paid from or into; for a transfer, the one it goes out of. */
    val account: String? = null,
    /** What it came to in [account]'s currency, where that isn't the bill's. */
    val accountAmount: Long? = null,
    /** Paid from several accounts, in place of [account]. */
    val splits: List<Split> = emptyList(),
    /** A transfer's account it goes into, and what arrived there in its currency. */
    val toAccount: String? = null,
    val toAmount: Long? = null,
    /** A transfer's fee, an expense of [feeAccount]; its discount, an income of [discountAccount]. */
    val fee: Long = 0,
    val feeAccount: String? = null,
    /** An expense's discount is what it saved, on record only; a transfer's comes back as income. */
    val discount: Long = 0,
    val discountAccount: String? = null,
    /** Tags, which the ledger doesn't keep apart, are at its end as `#tag`. */
    val note: String = "",
    /** The expense a refund or a reimbursement is for. */
    val related: String? = null,
    val reimburse: Reimburse = Reimburse.NONE,
    /** Kept out of the books' sums, still moving its accounts. */
    val excluded: Boolean = false,
    /** The Moment it belongs to, its place and its trip, as the site names them. */
    val moment: String? = null,
    val place: String? = null,
    val trip: String? = null,
    val createdAt: Long = time,
    val updatedAt: Long = time,
) {
    val local: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
    val date: LocalDate get() = local.toLocalDate()
    /** The day it happened, `YYYY-MM-DD`, as a Moment's day is keyed. */
    val dateKey: String get() = date.toString()

    /** The accounts it pays from or into, each with its share in the bill's currency. */
    val payers: List<Split> get() = if (splits.isNotEmpty()) splits else listOfNotNull(account?.let { Split(it, amount) })
}

/** Everything in the ledger, as the store last read it. */
data class Ledger(
    val books: List<Book> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** Newest first. */
    val txns: List<Txn> = emptyList(),
    val rates: Rates = Rates.EMPTY,
    val currentBook: String? = null,
    /** Every book shown at once, the tab's bills and sums across them all. */
    val allBooks: Boolean = false,
) {
    val accountsById: Map<String, Account> by lazy { accounts.associateBy { it.id } }
    val categoriesById: Map<String, Category> by lazy { categories.associateBy { it.id } }
    val txnsById: Map<String, Txn> by lazy { txns.associateBy { it.id } }
    /** The book chosen, where a new bill goes, even while every book is shown. */
    val book: Book? get() = books.firstOrNull { it.id == currentBook } ?: books.firstOrNull { !it.archived } ?: books.firstOrNull()

    /** The books whose bills the tab shows: the chosen one, or all but the archived. */
    val shownBooks: Set<String> get() = if (allBooks) books.filter { !it.archived }.map { it.id }.toSet() else setOfNotNull(book?.id)

    /** A first-level category's name, by which every book's categories are summed together. */
    fun topCategoryName(id: String?): String? = categoriesById[topCategory(id)]?.name ?: id

    /** The refunds and reimbursements of each expense, by its id. */
    val returns: Map<String, List<Txn>> by lazy {
        txns.filter { it.related != null && (it.kind == TxnKind.REFUND || it.kind == TxnKind.REIMBURSEMENT) }.groupBy { it.related!! }
    }

    /** What each bill adds to or takes from the books' sums, oldest first. */
    val entries: List<Entry> by lazy { entriesOf(this) }

    /** How many of the latest bills each of [keys]' values is in, for what's picked most to come first. */
    private fun usesOf(keys: (Txn) -> Iterable<String?>): Map<String, Int> {
        val uses = HashMap<String, Int>()
        txns.asSequence().take(RECENT_FOR_USE).forEach { txn -> keys(txn).filterNotNull().toSet().forEach { uses.merge(it, 1, Int::plus) } }
        return uses
    }

    /**
     * The accounts open to pick, the ones the latest bills used most first; the rest, and ties,
     * keep their own order.
     */
    val accountsByUse: List<Account> by lazy {
        val uses = usesOf { listOf(it.account, it.toAccount) + it.splits.map(Split::account) }
        accounts.filter { !it.archived }.sortedByDescending { uses[it.id] ?: 0 }
    }

    /** How many of the latest bills each category is in, a first-level one with its second-level ones'. */
    val categoryUses: Map<String, Int> by lazy { usesOf { listOf(it.category, topCategory(it.category)) } }

    /** How many of the latest bills each place is in. */
    val placeUses: Map<String, Int> by lazy { usesOf { listOf(it.place) } }

    /** The currencies a bill can be in, the latest bills' most used first, then the common ones and the rest. */
    val currenciesByUse: List<String> by lazy {
        val uses = usesOf { listOf(it.currency) }
        (uses.keys.sorted() + COMMON_CURRENCIES + rates.currencies.sorted()).distinct().sortedByDescending { uses[it] ?: 0 }
    }

    /** Each account's balance in its own currency. */
    val balances: Map<String, Long> by lazy { balancesOf(this) }

    /** A category's own name, under its parent's when it has one: `餐饮 › 零食`. */
    fun categoryPath(id: String?): String? {
        val category = categoriesById[id] ?: return null
        val parent = category.parent?.let(categoriesById::get) ?: return category.name
        return "${parent.name} › ${category.name}"
    }

    /** The first-level category a bill is summed under. */
    fun topCategory(id: String?): String? {
        val category = categoriesById[id] ?: return id
        return category.parent ?: category.id
    }
}

/** Cents from a written amount, `-8.76` or `5.840000000000003`, rounded to the cent; null for none. */
fun parseCents(text: String?): Long? {
    val value = text?.trim()?.replace(",", "")?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
    return (abs(value) * 100).roundToLong()
}

/** Cents written out with two decimals, or none when whole: `12`, `8.76`, `-0.50`. */
fun formatCents(cents: Long, always: Boolean = false): String {
    val sign = if (cents < 0) "-" else ""
    val whole = abs(cents) / 100
    val part = abs(cents) % 100
    val grouped = "%,d".format(java.util.Locale.ROOT, whole)
    return if (part == 0L && !always) "$sign$grouped" else "$sign$grouped.${part.toString().padStart(2, '0')}"
}

/** The symbol a currency is written with, else its code. */
fun currencySymbol(code: String): String = when (code) {
    "CNY" -> "¥"
    "USD" -> "$"
    "HKD" -> "HK$"
    "SGD" -> "S$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "JP¥"
    "KRW" -> "₩"
    "TWD" -> "NT$"
    "MOP" -> "MOP$"
    "AUD" -> "A$"
    "CAD" -> "C$"
    "THB" -> "฿"
    else -> "$code "
}

/** An amount with its currency's symbol: `¥58.99`, `-HK$8.76`. */
fun formatMoney(cents: Long, currency: String): String {
    val sign = if (cents < 0) "-" else ""
    return sign + currencySymbol(currency) + formatCents(abs(cents))
}

/** The currencies offered for a book, an account or a bill, the commonest first. */
val COMMON_CURRENCIES = listOf(
    "CNY", "USD", "HKD", "SGD", "JPY", "EUR", "GBP", "KRW", "TWD", "MOP", "AUD", "CAD", "NZD", "THB", "MYR", "CHF",
)

/** [note] with [tags] after it as `#tag`, apart by spaces, leaving out those it already has. */
fun noteWithTags(note: String, tags: List<String>): String {
    val words = note.trim().split(Regex("\\s+")).toSet()
    return (listOf(note.trim()) + tags.map { "#" + it.trim().removePrefix("#") }.filter { it.length > 1 && it !in words }.distinct())
        .filter { it.isNotEmpty() }.joinToString(" ")
}
