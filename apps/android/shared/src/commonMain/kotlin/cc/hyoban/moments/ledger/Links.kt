package cc.hyoban.moments.ledger

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

// How the ledger meets the site's records: a bill may be a Moment's, at its place and on its
// trip. Only the ledger keeps these links; nothing of the ledger reaches the Moments or the site.

/** The trip a bill was on: the one it names, else its Moment's. */
fun tripOfTxn(txn: Txn, moments: Map<String, MomentRecord>, trips: List<TripRecord>): String? =
    txn.trip ?: txn.moment?.let(moments::get)?.let { tripOf(it, trips)?.id }

/** The place a bill was at: the one it names, else its Moment's. */
fun placeOfTxn(txn: Txn, moments: Map<String, MomentRecord>): String? =
    txn.place ?: txn.moment?.let(moments::get)?.places?.firstOrNull()

/** The trip under way on [dateKey], `YYYY-MM-DD`, as a new bill takes it unless told otherwise. */
fun tripOnDay(dateKey: String, trips: List<TripRecord>): TripRecord? = trips.firstOrNull { dateKey >= it.start && dateKey <= it.end }

/** The bills of a Moment. */
fun txnsOfMoment(ledger: Ledger, uid: String): List<Txn> = ledger.txns.filter { it.moment == uid }

/** The bills on a trip, as [tripOfTxn] has them. */
fun txnsOfTrip(ledger: Ledger, trip: String, moments: Map<String, MomentRecord>, trips: List<TripRecord>): List<Txn> =
    ledger.txns.filter { it.kind != TxnKind.TRANSFER && tripOfTxn(it, moments, trips) == trip }

/** The bills at a place, as [placeOfTxn] has them. */
fun txnsOfPlace(ledger: Ledger, place: String, moments: Map<String, MomentRecord>): List<Txn> =
    ledger.txns.filter { it.kind != TxnKind.TRANSFER && placeOfTxn(it, moments) == place }

/** The bills of a day, `YYYY-MM-DD`. */
fun txnsOfDay(ledger: Ledger, dateKey: String): List<Txn> = ledger.txns.filter { it.kind != TxnKind.TRANSFER && it.dateKey == dateKey }

/**
 * Links bills to what they belong to, without touching one already linked: a meal's Moment
 * that says what it cost takes the expense of that day of the same amount, the nearest in time,
 * with its place; a bill of a travel book, one whose name says 旅, takes the trip under way that
 * day. Returns the bills it changed.
 */
fun autoLink(ledger: Ledger, moments: List<MomentRecord>, trips: List<TripRecord>): List<Txn> {
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
    val travel = ledger.books.filter { it.name.contains("旅") || it.name.contains("travel", ignoreCase = true) }.map { it.id }.toSet()
    for (txn in ledger.txns) {
        if (txn.book !in travel || current(txn).trip != null || txn.kind == TxnKind.TRANSFER) continue
        val trip = tripOnDay(txn.dateKey, trips) ?: continue
        changed[txn.id] = current(txn).copy(trip = trip.id)
    }
    return changed.values.toList()
}
