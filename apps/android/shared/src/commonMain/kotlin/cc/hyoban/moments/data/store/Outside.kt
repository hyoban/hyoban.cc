package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// What publishing sends beside the Moments, from the store that is the phone's own: the
// ledger's public bills. Each is private until said public; what is public of it is worked out
// here, and only that leaves the phone. Each remembers what last went, so 发布更改 lists what
// changed and a bill made private again is taken off. The footprints never leave the phone.

/** A bill whose public part isn't what the site has: [part] to send, or null to take it off. */
data class BillChange(val txn: Txn, val part: JsonObject?, val label: String) {
    /** Its entry's uid: the bill's own, a ULID. */
    val uid: String get() = txn.id
}

/** What waits to publish beside the Moments. */
data class Outside(val bills: List<BillChange> = emptyList()) {
    val size get() = bills.size
}

/** Where [Outside] comes from, and where what went is noted; the tests stand in with their own. */
interface OutsideChanges {
    fun pending(): Outside

    /** After a publish went through: [sent] is the site's now. */
    suspend fun published(sent: Outside)
}

/** Whether a bill is public: its own say, else its book's, else private. */
fun Ledger.isPublic(txn: Txn): Boolean =
    txn.visibility?.let { it == "public" } ?: (books.firstOrNull { it.id == txn.book }?.publicOn(txn.date) ?: false)

/** What of a bill the site may have: an expense's or an income's amount, kind, category and note, when it is public. */
fun Ledger.publicPart(txn: Txn): JsonObject? {
    if (!isPublic(txn) || (txn.kind != TxnKind.EXPENSE && txn.kind != TxnKind.INCOME)) return null
    return Entries.billPart(txn.kind.value, txn.amount / 100.0, txn.currency, categoryLabel(txn), txn.note)
}

/** A bill's category as it is shown, its parent before it: `餐饮 › 零食`. */
fun Ledger.categoryLabel(txn: Txn): String? = categoriesById[txn.category]?.let { category ->
    listOfNotNull(category.parent?.let { categoriesById[it]?.name }, category.name).joinToString(" › ")
}

/** Every bill whose public part isn't what was last published. */
fun Ledger.pendingBills(): List<BillChange> = txns.mapNotNull { txn ->
    val part = publicPart(txn)
    if (part?.toString() == txn.published) null else BillChange(txn, part, categoryLabel(txn).orEmpty())
}

/**
 * The visibility that takes this change back, saying again what the site has: private for a
 * bill going public, public for one going private; null for a public bill that changed, which
 * has no undo.
 */
fun BillChange.undo(): String? = when {
    part == null -> "public"
    txn.published == null -> "private"
    else -> null
}

/** A bill alone as an entry, at its local time. */
fun BillChange.entry(): JsonObject = Entries.bill(
    uid = uid,
    at = txn.local.atOffset(ZoneOffset.ofTotalSeconds(txn.offsetMinutes * 60)).format(ISO),
    place = txn.place,
    bill = part!!,
    createdAt = Instant.ofEpochMilli(txn.createdAt).atOffset(ZoneOffset.UTC).format(ISO),
    updatedAt = Instant.ofEpochMilli(txn.updatedAt).atOffset(ZoneOffset.UTC).format(ISO),
)

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
