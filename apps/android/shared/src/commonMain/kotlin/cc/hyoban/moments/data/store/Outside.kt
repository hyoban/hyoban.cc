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
data class BillChange(
    val txn: Txn,
    val part: JsonObject?,
    val label: String,
    val deleted: Boolean = false,
    /** Where and when the site has it, when the bill here has moved from there: another time or place. */
    val site: SiteSpot? = null,
) {
    /** Its entry's uid: the bill's own, a ULID. */
    val uid: String get() = txn.id
}

/** What waits to publish beside the Moments. */
data class Outside(val bills: List<BillChange> = emptyList()) {
    val size get() = bills.size
}

/**
 * What the site kept of [bills] after a publish: each bill as [kept] holds the part it went out in,
 * by the uid of the entry that carried it ([sent], the part each entry was sent with), where the
 * API kept less of it, as an API older than the app drops a field it doesn't know. Noted as
 * published so, such a bill stays waiting to publish rather than being taken for the site's.
 */
fun Outside.asKept(sent: Map<String, JsonObject>, kept: Map<String, JsonObject>): Outside = Outside(
    bills.map { change ->
        val part = change.part ?: return@map change
        val carrier = if (change.uid in sent) change.uid else change.txn.moment
        val stored = carrier?.let(kept::get)
        // Only the bill whose part its entry was sent with: a Moment carries one of its bills.
        if (stored == null || sent[carrier] != part || samePart(stored, part)) change else change.copy(part = stored)
    },
)

/** Whether two bill parts say the same, a figure the same whether written `88` or `88.0`. */
fun samePart(a: JsonObject, b: JsonObject): Boolean = a.keys == b.keys && a.all { (key, value) ->
    val other = b.getValue(key)
    val x = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()
    val y = (other as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()
    if (x != null && y != null) x == y else value == other
}

/** When and where the site has a bill on its own: its entry's time and place. */
data class SiteSpot(val at: String, val place: String?)

/** Where [Outside] comes from, and where what went is noted; the tests stand in with their own. */
interface OutsideChanges {
    /** What waits to publish, a bill's time and place told against [site], what the site has of each bill alone by uid. */
    fun pending(site: Map<String, SiteSpot> = emptyMap()): Outside

    /** After a publish went through: [sent] is the site's now. */
    suspend fun published(sent: Outside)
}

/** Whether a bill is public: its own say, else its book's, else private. */
fun Ledger.isPublic(txn: Txn): Boolean =
    txn.visibility?.let { it == "public" } ?: (books.firstOrNull { it.id == txn.book }?.publicOn(txn.date) ?: false)

/**
 * What of a bill the site may have: an expense's or an income's amount, kind, category and note,
 * when it is public, and what an expense's refunds brought back of it. A refund goes out only so, with
 * its expense: public as that is, unless it says private itself.
 */
fun Ledger.publicPart(txn: Txn): JsonObject? {
    if (!isPublic(txn) || (txn.kind != TxnKind.EXPENSE && txn.kind != TxnKind.INCOME)) return null
    val refunded = if (txn.kind != TxnKind.EXPENSE) 0 else returns[txn.id].orEmpty()
        .filter { it.kind == TxnKind.REFUND && it.visibility != "private" }
        .sumOf { it.amount }
        // What comes back beyond what was spent is earned, as the books count it, and stays here.
        .coerceAtMost(txn.amount)
    return Entries.billPart(txn.kind.value, txn.amount / 100.0, txn.currency, categoryLabel(txn), txn.note, refunded / 100.0)
}

/** A bill's category as it is shown, its parent before it: `餐饮 › 零食`. */
fun Ledger.categoryLabel(txn: Txn): String? = categoriesById[txn.category]?.let { category ->
    listOfNotNull(category.parent?.let { categoriesById[it]?.name }, category.name).joinToString(" › ")
}

/**
 * Every bill whose public part isn't what was last published, or that the site has on its own
 * ([site], by uid) at another time or place than it is here; and every one deleted that the site
 * still has. A bill a Moment or a check-in carries is at that record's time and place.
 */
fun Ledger.pendingBills(site: Map<String, SiteSpot> = emptyMap()): List<BillChange> = txns.mapNotNull { txn ->
    val part = publicPart(txn)
    val spot = site[txn.id]?.takeIf { part != null && txn.published != null && txn.moment == null && it.movedFrom(txn) }
    if (part.isPublished(txn.published) && spot == null) null else BillChange(txn, part, categoryLabel(txn).orEmpty(), site = spot)
} + gone.map { txn -> BillChange(txn, null, categoryLabel(txn) ?: txn.note, deleted = true) }

/**
 * Whether this part, or none, is what [published] says the site has: the same by what it says,
 * not by how it is written, as a part read back from the site writes `30` where the ledger's has `30.0`.
 */
private fun JsonObject?.isPublished(published: String?): Boolean {
    if (this == null || published == null) return this == null && published == null
    val site = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(published) as? JsonObject }.getOrNull() ?: return false
    return samePart(this, site)
}

/**
 * Whether the bill here is at another time or place than the site has it: its time to the second,
 * as an entry's `at` keeps it, so a bill made at 18:01:58.4 is still where the site has it.
 */
private fun SiteSpot.movedFrom(txn: Txn): Boolean {
    val at = runCatching { java.time.OffsetDateTime.parse(at).toEpochSecond() }.getOrNull() ?: return false
    return at != Math.floorDiv(txn.time, 1000L) || place != txn.place
}

/**
 * The visibility that takes this change back, saying again what the site has: private for a
 * bill going public, public for one going private; null for a public bill that changed or one
 * deleted, which have no undo.
 */
fun BillChange.undo(): String? = when {
    deleted -> null
    part == null -> "public"
    txn.published == null -> "private"
    else -> null
}

/** A bill the site has: its entry's uid and time, its bill part, and whether that entry is a Moment carrying it. */
data class SiteBill(val uid: String, val at: String, val part: JsonObject, val moment: Boolean)

/**
 * The bills here that are bills the site has though they don't say so, as an import in place of
 * the ledger once left them, under new ids and with nothing published: by the id here, the site's.
 * A bill of a Moment is the bill that Moment carries; one alone is a bill of the site's the ledger
 * has no bill for, of the same kind, amount and currency, the nearest within ten minutes (a bill
 * written here before iCost had it), each taken once.
 */
fun Ledger.readoptions(site: List<SiteBill>): Map<String, SiteBill> {
    val taken = (txns.map(Txn::id) + gone.map(Txn::id)).toSet()
    val carried = site.filter { it.moment }.associateBy { it.uid }
    val open = site.filter { !it.moment && it.uid !in taken }
        .mapNotNull { bill -> runCatching { java.time.OffsetDateTime.parse(bill.at).toInstant().toEpochMilli() }.getOrNull()?.let { it to bill } }
        .toMutableList()
    val out = mutableMapOf<String, SiteBill>()
    for (txn in txns.sortedBy { it.time }) {
        if (txn.published != null || (txn.kind != TxnKind.EXPENSE && txn.kind != TxnKind.INCOME)) continue
        val moment = txn.moment
        if (moment != null) {
            carried[moment]?.let { out[txn.id] = it }
            continue
        }
        val same = open.filter { (time, bill) ->
            kotlin.math.abs(time - txn.time) <= SAME_BILL_MS &&
                bill.part.text("kind") == txn.kind.value && bill.part.text("currency") == txn.currency &&
                bill.part.text("amount")?.toDoubleOrNull()?.let { kotlin.math.abs(it * 100 - txn.amount) < 0.5 } == true
        }.minByOrNull { (time, _) -> kotlin.math.abs(time - txn.time) } ?: continue
        open.remove(same)
        out[txn.id] = same.second
    }
    return out
}

/** How far apart a bill here and the site's can be and still be the same, as an import finds them. */
private const val SAME_BILL_MS = 10 * 60_000L

private fun JsonObject.text(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

/** A bill alone as an entry, at its local time. */
fun BillChange.entry(): JsonObject = Entries.bill(
    uid = uid,
    at = txn.at,
    place = txn.place,
    bill = part!!,
    createdAt = Instant.ofEpochMilli(txn.createdAt).atOffset(ZoneOffset.UTC).format(ISO),
    updatedAt = Instant.ofEpochMilli(txn.updatedAt).atOffset(ZoneOffset.UTC).format(ISO),
)

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
