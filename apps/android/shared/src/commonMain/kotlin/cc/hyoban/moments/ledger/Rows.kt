package cc.hyoban.moments.ledger

// The ledger's rows as its SQLite database keeps them, read the same way wherever it is read:
// by the phone's store, and from the phone's backup on a desktop or a tablet.

/** The columns of `txns` a bill is read from. */
const val TXN_COLUMNS = "id, book, kind, transfer, time, offset, amount, currency, category, account, account_amount, splits, " +
    "to_account, to_amount, fee, fee_account, discount, discount_account, note, related, reimburse, excluded, moment, place, trip, " +
    "created, updated, visibility, published"

/** A bill from its row, each column read by name as text ([s]) or a whole number ([l]). */
fun txnOf(s: (String) -> String?, l: (String) -> Long?): Txn = Txn(
    id = s("id")!!,
    book = s("book")!!,
    kind = TxnKind.of(s("kind")),
    transfer = TransferType.of(s("transfer")),
    time = l("time")!!,
    offsetMinutes = l("offset")!!.toInt(),
    amount = l("amount")!!,
    currency = s("currency")!!,
    category = s("category"),
    account = s("account"),
    accountAmount = l("account_amount"),
    splits = decodeSplits(s("splits")),
    toAccount = s("to_account"),
    toAmount = l("to_amount"),
    fee = l("fee") ?: 0,
    feeAccount = s("fee_account"),
    discount = l("discount") ?: 0,
    discountAccount = s("discount_account"),
    note = s("note").orEmpty(),
    related = s("related"),
    reimburse = Reimburse.of(s("reimburse")),
    excluded = l("excluded") == 1L,
    moment = s("moment"),
    place = s("place"),
    trip = s("trip"),
    createdAt = l("created")!!,
    updatedAt = l("updated")!!,
    visibility = s("visibility"),
    published = s("published"),
)

/** Splits as `account\tcents` lines. */
fun encodeSplits(splits: List<Split>): String? = splits.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.account}\t${it.amount}" }

fun decodeSplits(text: String?): List<Split> = text.orEmpty().lines().mapNotNull { line ->
    val parts = line.split('\t')
    if (parts.size == 2) parts[1].toLongOrNull()?.let { Split(parts[0], it) } else null
}

/** Whether a bill moves money in or out of [account] in any way. */
fun Txn.uses(account: String): Boolean =
    this.account == account || toAccount == account || feeAccount == account || discountAccount == account || splits.any { it.account == account }
