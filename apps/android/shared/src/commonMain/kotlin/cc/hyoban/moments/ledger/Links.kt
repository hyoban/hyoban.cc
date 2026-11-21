package cc.hyoban.moments.ledger

import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.data.trips.isInTrip
import java.time.OffsetDateTime

// How the ledger meets the site's records: a bill may be a Moment's or a check-in's and at its
// place, links only the ledger keeps, else taken to be the one at its very time; a trip's bills are those paid in its span, as the site counts a
// trip's public ones.

/** The trip a bill was on: the one whose span its time falls in. */
fun tripOfTxn(txn: Txn, trips: List<TripRecord>): String? = trips.firstOrNull { isInTrip(it, txn.at) }?.id

/** The place a bill was at: the one it names, else its Moment's. */
fun placeOfTxn(txn: Txn, moments: Map<String, MomentRecord>): String? =
    txn.place ?: txn.moment?.let(moments::get)?.places?.firstOrNull()

/** When a record with a time was, in epoch milliseconds; null for one known by its day alone. */
private fun millisOf(at: String): Long? = at.takeIf { it.length > 10 }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/**
 * What a bill not linked by hand is taken to belong to, by the time of each Moment and check-in
 * ([moments] first, so a Moment wins over a check-in of the same time): the one at the very same
 * time, else the check-in kept from a stay (one with an end) it was paid during, the shortest when
 * several were (a shop's inside a mall's). Worked out as it is shown, never kept on the bill.
 */
class AutoLinks(moments: Collection<MomentRecord>, checkins: Collection<Checkin> = emptyList()) {
    private val byTime: Map<Long, String> = buildMap {
        checkins.forEach { checkin -> millisOf(checkin.at)?.let { put(it, checkin.uid) } }
        moments.forEach { moment -> millisOf(moment.occurredAt)?.let { put(it, moment.uid) } }
    }

    /** The stays, shortest first, so the first that covers a time is the one it goes with. */
    private val stays: List<Pair<LongRange, String>> = checkins
        .mapNotNull { checkin -> checkin.span?.takeIf { checkin.end != null }?.let { it to checkin.uid } }
        .sortedBy { (span, _) -> span.last - span.first }

    /** The entry [txn] belongs to: the one it names, else the one at its very time, else the stay it was paid in. */
    fun of(txn: Txn): String? = txn.moment ?: byTime[txn.time] ?: stays.firstOrNull { (span, _) -> txn.time in span }?.second
}

/** The bills of a Moment: those linked to it, and those not linked by hand at its very time. */
fun txnsOfMoment(ledger: Ledger, moment: MomentRecord): List<Txn> {
    val time = millisOf(moment.occurredAt)
    return ledger.txns.filter { it.moment == moment.uid || (it.moment == null && time != null && it.time == time) }
}

/**
 * The bills on a trip, as the site has them: the public expenses and incomes paid in its span.
 * A refund isn't one of its own: it goes with its expense, in what that cost.
 */
fun txnsOfTrip(ledger: Ledger, trip: TripRecord): List<Txn> =
    ledger.txns.filter { (it.kind == TxnKind.EXPENSE || it.kind == TxnKind.INCOME) && isInTrip(trip, it.at) && ledger.isPublic(it) }

/** The bills at a place, as [placeOfTxn] has them. */
fun txnsOfPlace(ledger: Ledger, place: String, moments: Map<String, MomentRecord>): List<Txn> =
    ledger.txns.filter { it.kind != TxnKind.TRANSFER && placeOfTxn(it, moments) == place }

/** The bills of a day, `YYYY-MM-DD`. */
fun txnsOfDay(ledger: Ledger, dateKey: String): List<Txn> = ledger.txns.filter { it.kind != TxnKind.TRANSFER && it.dateKey == dateKey }
