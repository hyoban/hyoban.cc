package cc.hyoban.moments.ledger

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.isInTrip
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

// How the ledger meets the site's records: a bill may be a Moment's and at its place, links
// only the ledger keeps; a trip's bills are those paid in its span, as the site counts a
// trip's public ones.

/** The trip a bill was on: the one whose span its time falls in. */
fun tripOfTxn(txn: Txn, trips: List<TripRecord>): String? = trips.firstOrNull { isInTrip(it, txn.at) }?.id

/** The place a bill was at: the one it names, else its Moment's. */
fun placeOfTxn(txn: Txn, moments: Map<String, MomentRecord>): String? =
    txn.place ?: txn.moment?.let(moments::get)?.places?.firstOrNull()

/** The bills of a Moment. */
fun txnsOfMoment(ledger: Ledger, uid: String): List<Txn> = ledger.txns.filter { it.moment == uid }

/** The bills on a trip: those paid in its span. */
fun txnsOfTrip(ledger: Ledger, trip: TripRecord): List<Txn> =
    ledger.txns.filter { it.kind != TxnKind.TRANSFER && isInTrip(trip, it.at) }

/** The bills at a place, as [placeOfTxn] has them. */
fun txnsOfPlace(ledger: Ledger, place: String, moments: Map<String, MomentRecord>): List<Txn> =
    ledger.txns.filter { it.kind != TxnKind.TRANSFER && placeOfTxn(it, moments) == place }

/** The bills of a day, `YYYY-MM-DD`. */
fun txnsOfDay(ledger: Ledger, dateKey: String): List<Txn> = ledger.txns.filter { it.kind != TxnKind.TRANSFER && it.dateKey == dateKey }

/**
 * Links bills to what they belong to, without touching one already linked: a meal's Moment
 * that says what it cost takes the expense of that day of the same amount, the nearest in time,
 * with its place. Returns the bills it changed.
 */
fun autoLink(ledger: Ledger, moments: List<MomentRecord>): List<Txn> {
    val changed = linkedMapOf<String, Txn>()
    fun current(txn: Txn) = changed[txn.id] ?: txn
    val taken = ledger.txns.mapNotNull { it.moment }.toMutableSet()
    val expensesByDay = ledger.txns.filter { it.kind == TxnKind.EXPENSE }.groupBy { it.dateKey }
    for (moment in moments) {
        if (moment.uid in taken) continue
        val bill = moment.food?.bill ?: continue
        val cents = (bill.amount * 100).roundToLong()
        val time = runCatching { OffsetDateTime.parse(moment.occurredAt).toInstant().toEpochMilli() }.getOrNull()
        val match = expensesByDay[moment.dateKey].orEmpty()
            .filter { current(it).moment == null && it.amount == cents }
            .minByOrNull { if (time == null) 0 else abs(it.time - time) }
            ?: continue
        changed[match.id] = current(match).copy(moment = moment.uid, place = current(match).place ?: moment.places.firstOrNull())
        taken += moment.uid
    }
    return changed.values.toList()
}
