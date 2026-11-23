package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.store.Records
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.data.things.Checkins
import cc.hyoban.moments.data.trips.isInTrip
import cc.hyoban.moments.ledger.AutoLinks
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.spendingOf
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ledger.txnsOfPlace
import cc.hyoban.moments.ledger.txnsOfTrip
import cc.hyoban.moments.ui.feed.JoinBuilder
import cc.hyoban.moments.ui.feed.JoinScope
import cc.hyoban.moments.ui.feed.JoinSpan
import cc.hyoban.moments.ui.feed.TimelineJoin
import cc.hyoban.moments.ui.feed.DayFigure
import cc.hyoban.moments.ui.feed.DayMarks
import cc.hyoban.moments.ui.feed.SpanFigure
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ui.DayMark
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.ledger_expense_short
import cc.hyoban.moments.res.ledger_spent
import org.jetbrains.compose.resources.stringResource

/**
 * The check-ins with neither words nor photos, stops of their own among the Moments: on the
 * timeline the phone's own too, an import's waiting to be published among them; in a trip those in
 * its time, the private ones out of sight in 演示模式; at a place those there, as the site shows
 * them. On a day's map, each at its place, else where the footprints were in the middle of its stay.
 */
internal object CheckinJoin : TimelineJoin {
    override val order = 0

    @Composable override fun input(container: AppContainer): Any? {
        val all by container.repository.checkins.collectAsStateWithLifecycle()
        val shown by container.shownCheckins.collectAsStateWithLifecycle()
        return all to shown
    }

    override fun join(scope: JoinScope, input: Any?, into: JoinBuilder) {
        @Suppress("UNCHECKED_CAST") val (all, shown) = input as Pair<Checkins, Checkins>
        val checkins = when (val span = scope.span) {
            is JoinSpan.Days -> all.byDay.filterKeys(span::keep)
            is JoinSpan.Trip -> all.byDay.filterKeys { it in span.trip.start..span.trip.end }
                .mapValues { (_, day) -> day.filter { isInTrip(span.trip, it.at) && !(scope.demo && it.private) } }
                .filterValues { it.isNotEmpty() }
            is JoinSpan.Place -> shown.byPlace[span.id].orEmpty().groupBy { it.at.take(10) }
        }
        into.put(Module.CHECKIN, checkins)
        checkins.forEach { (day, list) -> list.forEach { into.anchor(day, CheckinAnchor(it)) } }
        into.marks(CheckinMarks)
    }
}

/** The check-ins a page joined, by day. */
internal fun joinedCheckins(of: (Module) -> Map<String, List<Checkin>>?): Map<String, List<Checkin>> = of(Module.CHECKIN).orEmpty()

private object CheckinMarks : DayMarks {
    override fun marks(scope: cc.hyoban.moments.ui.feed.MarkScope): List<DayMark> =
        joinedCheckins { scope.records.of(it) }[scope.day].orEmpty().mapNotNull { checkin ->
            val at = scope.placed(checkin.place) ?: checkin.span?.let { scope.tracked((it.first + it.last) / 2) } ?: return@mapNotNull null
            DayMark(at, DayMark.Kind.STOP, scope.kindIcon(checkin.place) ?: AppIcons.Flag)
        }
}

/** The bills a page joined: the ledger, and those shown linked to a Moment or a check-in and loose. */
internal class LedgerJoined(val ledger: Ledger, var linked: Map<String, List<Txn>>, var loose: Map<String, List<Txn>>) {
    /** Takes the bills [ids] out of where they were shown, as a charge shows them with it. */
    fun take(ids: Set<String>) {
        if (ids.isEmpty()) return
        linked = linked.mapValues { (_, txns) -> txns.filterNot { it.id in ids } }.filterValues { it.isNotEmpty() }
        loose = loose.mapValues { (_, txns) -> txns.filterNot { it.id in ids } }.filterValues { it.isNotEmpty() }
    }
}

/** What the car's charges are paired with: the bills shown and the Moment or check-in each goes with. */
internal class BillsShown(val bills: List<Txn>, val linkOf: (Txn) -> String?)

/**
 * The bills of every book the ledger keeps open (all but the archived), money out and in and
 * back: one linked to a Moment or a check-in shown goes in its bubble whenever it was paid, the
 * rest loose on their days, and each day's spending on its header. On the timeline those on the
 * days shown; in a trip those paid in its span, and a public one of its Moments' or check-ins'
 * from any time, its spending what was paid in its span; at a place those paid there, what was
 * spent there at the page's head. On a day's map, each where it was paid.
 */
internal object LedgerJoin : TimelineJoin {
    override val order = 1

    @Composable override fun input(container: AppContainer): Any? {
        val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
        return ledger
    }

    override fun join(scope: JoinScope, input: Any?, into: JoinBuilder) {
        val ledger = input as Ledger
        val book = ledger.book
        val currency = book?.currency ?: "CNY"
        val span = scope.span
        into.put(Module.LEDGER, LedgerJoined(ledger, emptyMap(), emptyMap()))
        // The timeline shows no bills without a book to sum them in.
        if (book == null && span is JoinSpan.Days) return
        val shown = ledger.books.filterNot { it.archived }.mapTo(HashSet()) { it.id }
        val checkins = joinedCheckins { into.of(it) }.values.flatten()
        val uids = scope.moments.mapTo(HashSet()) { it.uid }.apply { checkins.forEach { add(it.uid) } }
        val links = AutoLinks(scope.moments, checkins)
        var spent: Map<String, Long> = emptyMap()
        val bills = when (span) {
            is JoinSpan.Days -> ledger.txns.filter { txn ->
                txn.book in shown && txn.kind in SHOWN_KINDS && (span.keepTxn(txn) || links.of(txn) in uids)
            }.also { bills ->
                // Each day's spending is the bills paid on the days shown alone.
                val ids = bills.filter { span.keepTxn(it) }.mapTo(HashSet()) { it.id }
                spent = summarize(ledger.entries.filter { it.txn.id in ids }, ledger.rates, currency, null, null).expenseByDay.mapKeys { it.key.toString() }
            }
            is JoinSpan.Trip -> {
                val inSpan = txnsOfTrip(ledger, span.trip).filter { it.book in shown && it.kind in SHOWN_KINDS }
                spent = inSpan.groupBy { it.dateKey }.mapValues { (_, txns) -> spendingOf(ledger, txns, currency).expense }
                val ids = inSpan.mapTo(HashSet()) { it.id }
                inSpan + ledger.txns.filter {
                    it.id !in ids && it.book in shown && it.kind in SHOWN_KINDS && it.moment in uids && ledger.isPublic(it)
                }
            }
            is JoinSpan.Place -> txnsOfPlace(ledger, span.id, scope.allMoments.associateBy { it.uid })
                .filter { it.book in shown && it.kind in SHOWN_KINDS }
                .also { placeBills ->
                    spent = placeBills.groupBy { it.dateKey }.mapValues { (_, txns) -> spendingOf(ledger, txns, currency).expense }
                    placeBills.takeIf { it.isNotEmpty() }?.let { spendingOf(ledger, it, currency).expense }?.takeIf { it > 0 }?.let { total ->
                        into.total(SpanFigure { stringResource(Res.string.ledger_spent, money(total, currency)) })
                    }
                }
        }
        into.put(BILLS_SHOWN, BillsShown(bills) { txn -> links.of(txn)?.takeIf { it in uids } })
        val (withMoment, without) = bills.partition { links.of(it) in uids }
        val linked = withMoment.groupBy { links.of(it)!! }.mapValues { (_, txns) -> txns.sortedBy { it.time } }
        val loose = without.groupBy { it.dateKey }
        into.put(Module.LEDGER, LedgerJoined(ledger, linked, loose))
        linked.forEach { (uid, txns) -> txns.forEach { into.attach(uid, BillRecord(it, ledger), rank = 1) } }
        loose.forEach { (day, txns) -> txns.forEach { into.loose(day, BillRecord(it, ledger)) } }
        into.figure(DayFigure { day -> spent[day]?.takeIf { it > 0 }?.let { stringResource(Res.string.ledger_expense_short, money(it, currency)) } })
        into.marks(BillMarks)
    }
}

/** Where the bills shown are kept for the charges, apart from the ledger's own. */
private const val BILLS_SHOWN = "bills-shown"

/**
 * The day's bills on its map by their category: where it was paid, else its Moment's place or
 * its check-in's, else that of the check-in it was paid in, else where the footprints were then.
 */
private object BillMarks : DayMarks {
    override fun marks(scope: cc.hyoban.moments.ui.feed.MarkScope): List<DayMark> {
        val joined = scope.records.of<LedgerJoined>(Module.LEDGER) ?: return emptyList()
        val checkins = joinedCheckins { scope.records.of(it) }[scope.day].orEmpty()
        val dayBills = (joined.loose[scope.day].orEmpty() + (scope.moments.map { it.uid } + checkins.map { it.uid }).flatMap { joined.linked[it].orEmpty() }).distinctBy { it.id }
        val byUid = scope.moments.associateBy { it.uid }
        return dayBills.mapNotNull { txn ->
            val at = scope.placed(txn.place)
                ?: scope.placed(txn.moment?.let(byUid::get)?.places?.firstOrNull())
                ?: scope.placed(checkins.firstOrNull { it.uid == txn.moment }?.place)
                ?: scope.placed(checkins.filter { it.place != null && it.span?.contains(txn.time) == true }.minByOrNull { it.span!!.last - it.span!!.first }?.place)
                ?: scope.tracked(txn.time)
                ?: return@mapNotNull null
            DayMark(at, DayMark.Kind.RECORD, cc.hyoban.moments.ui.ledger.txnIcon(txn, joined.ledger))
        }
    }
}

/**
 * The car's charges and what it drove each day it moved, on the timeline's days and in a trip's
 * time; none at a place. A charge and the bill paid for it at its very time are one line, where
 * that bill would go, else with the Moment or check-in at its own time.
 */
internal object CarJoin : TimelineJoin {
    override val order = 2

    @Composable override fun input(container: AppContainer): Any? {
        val records by container.repository.records.collectAsStateWithLifecycle()
        return records
    }

    override fun join(scope: JoinScope, input: Any?, into: JoinBuilder) {
        val records = input as Records
        val (charges, keep) = when (val span = scope.span) {
            is JoinSpan.Days -> {
                val inDays = { day: String -> span.keep(day) }
                chargesOf(records.entries.values, inDays) to inDays
            }
            is JoinSpan.Trip -> {
                val inTripDay = { day: String -> span.trip.start <= day && day <= span.trip.end }
                chargesOf(records.entries.values, inTripDay).filter { isInTrip(span.trip, it.at) } to inTripDay
            }
            is JoinSpan.Place -> return
        }
        val joined = into.of<LedgerJoined>(Module.LEDGER)
        val shown = into.of<BillsShown>(BILLS_SHOWN)
        val at = momentTimes(scope.moments, joinedCheckins { into.of(it) }.values.flatten())
        val (loose, linked, paid) = placeCharges(charges, shown?.bills.orEmpty(), shown?.linkOf ?: { null }, at)
        into.put(Module.CAR, loose)
        // A charge with the bill paid for it shows that bill, which is then not shown on its own.
        joined?.take(paid)
        into.take(paid.mapTo(HashSet()) { "bill-$it" })
        val ledger = joined?.ledger
        linked.forEach { (uid, list) -> list.forEach { into.attach(uid, ChargeRecord(it, ledger), rank = 0) } }
        loose.forEach { (day, list) -> list.forEach { into.loose(day, ChargeRecord(it, ledger)) } }
        drivesOf(records.vehicleMonths.values, keep).forEach { (day, drive) -> into.loose(day, DriveRecord(drive)) }
    }
}

/** Whether [txn] falls in the timeline's days. */
private fun JoinSpan.Days.keepTxn(txn: Txn) =
    (filter.year == null || txn.date.year == filter.year) && (filter.month == null || txn.date.monthValue == filter.month)
