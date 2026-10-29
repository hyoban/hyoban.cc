package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.design.HorizontalDivider
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.ledger.TxnRow
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary

// The ledger on the timeline: the bills of the books shown, those written with a Moment in its
// card and the rest in groups between the Moments of their day, and each day's spending beside
// its date. Only what was spent and earned; transfers and the like stay in the ledger's pages.

/** The bills the timeline shows, by where they go, and what each day spent, in the book's currency. */
class TimelineBills(
    val ledger: Ledger,
    /** Bills written with a Moment, by its uid, oldest first as its card lists them. */
    val linked: Map<String, List<Txn>>,
    /** The rest, by day, newest first. */
    val loose: Map<String, List<Txn>>,
    /** What each day spent, `YYYY-MM-DD` to cents, in [currency]. */
    val spent: Map<String, Long>,
    val currency: String,
    /** The car's charges, from the API's second shape, by day. */
    val charges: Map<String, List<Charge>> = emptyMap(),
) {
    companion object {
        val EMPTY = TimelineBills(Ledger(), emptyMap(), emptyMap(), emptyMap(), "CNY")
    }
}

/** A charge on the timeline: when it started, AC or DC, how much went in and for how long. */
data class Charge(val uid: String, val at: String, val dc: Boolean, val kwh: Double?, val minutes: Int?)

/** What the timeline shows, as its filter row chooses: everything, or one plugin's records. */
enum class FeedKind { ALL, MOMENTS, BILLS, CHARGES }

/** The bills to show beside [moments], of the books the ledger shows, on the days [filter] keeps. */
@Composable
fun rememberTimelineBills(container: AppContainer, moments: List<MomentRecord>, filter: TimeFilter, kind: FeedKind = FeedKind.ALL): TimelineBills {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val second by container.repository.second.collectAsStateWithLifecycle()
    return remember(ledger, second, moments, filter, kind) {
        val charges = if (kind != FeedKind.ALL && kind != FeedKind.CHARGES) emptyMap() else second.entries.values.mapNotNull { entry ->
            val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
                ?: return@mapNotNull null
            val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val text = { key: String -> (charge[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            Charge(
                (entry["uid"] as kotlinx.serialization.json.JsonPrimitive).content,
                at,
                text("type") == "DC",
                text("energyWh")?.toDoubleOrNull()?.div(1000),
                text("durationMin")?.toDoubleOrNull()?.toInt(),
            )
        }.filter { charge ->
            (filter.year == null || charge.at.take(4).toInt() == filter.year) &&
                (filter.month == null || charge.at.substring(5, 7).toInt() == filter.month)
        }.groupBy { it.at.take(10) }
        val book = ledger.book
        if (book == null || (kind != FeedKind.ALL && kind != FeedKind.BILLS)) {
            return@remember TimelineBills(ledger, emptyMap(), emptyMap(), emptyMap(), book?.currency ?: "CNY", charges)
        }
        val shown = ledger.shownBooks
        val uids = moments.mapTo(HashSet()) { it.uid }
        val bills = ledger.txns.filter { txn ->
            txn.book in shown && txn.kind in SHOWN_KINDS &&
                (filter.year == null || txn.date.year == filter.year) &&
                (filter.month == null || txn.date.monthValue == filter.month)
        }
        val (withMoment, without) = bills.partition { it.moment != null && it.moment in uids }
        val ids = bills.mapTo(HashSet()) { it.id }
        val sums = summarize(ledger.entries.filter { it.txn.id in ids }, ledger.rates, book.currency, null, null)
        TimelineBills(
            ledger,
            linked = withMoment.groupBy { it.moment!! }.mapValues { (_, txns) -> txns.sortedBy { it.time } },
            loose = without.groupBy { it.dateKey },
            spent = sums.expenseByDay.mapKeys { it.key.toString() },
            currency = book.currency,
            charges = charges,
        )
    }
}

/** What the timeline lists of the ledger: money out and in, and money back. */
private val SHOWN_KINDS = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.REFUND, TxnKind.REIMBURSEMENT)

/** One day's spending, for its pill; null when it spent nothing. */
@Composable
fun daySpent(bills: TimelineBills, date: String): String? = bills.spent[date]?.takeIf { it > 0 }?.let { money(it, bills.currency) }

/** What a day holds, in the order the timeline shows it: newest first, the bills between them gathered. */
sealed interface DayItem {
    val key: String

    data class Moment(val moment: MomentRecord) : DayItem {
        override val key get() = moment.uid
    }

    data class Bills(val txns: List<Txn>) : DayItem {
        override val key get() = "bills-${txns.first().id}"
    }

    data class ChargeItem(val charge: Charge) : DayItem {
        override val key get() = "charge-${charge.uid}"
    }
}

/** A charge as a row on the page: its icon, AC or DC and how much, and when and how long in figures. */
@Composable
fun ChargeRow(charge: Charge, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("charge_${charge.uid}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(AppIcons.Ev, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    androidx.compose.ui.res.stringResource(if (charge.dc) cc.hyoban.moments.R.string.charge_dc else cc.hyoban.moments.R.string.charge_ac),
                    charge.kwh?.let { "%.1f kWh".format(it) },
                ).joinToString("\u2003"),
                style = AppTheme.typography.body,
            )
            Text(
                listOfNotNull(charge.at.drop(11).take(5).ifEmpty { null }, charge.minutes?.let { "$it min" }).joinToString("\u2003"),
                style = AppTheme.typography.numCaption,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [moments], [bills] and [charges] of one day merged by the time of day, newest first, bills next to each other in one group. */
fun dayItems(moments: List<MomentRecord>, bills: List<Txn>, charges: List<Charge> = emptyList()): List<DayItem> {
    if (bills.isEmpty() && charges.isEmpty()) return moments.map(DayItem::Moment)
    val timed = moments.map { it.occurredAt.drop(11).take(8) to DayItem.Moment(it) as Any } +
        bills.map { it.local.toLocalTime().toString().take(8) to it as Any } +
        charges.map { it.at.drop(11).take(8) to DayItem.ChargeItem(it) as Any }
    val out = mutableListOf<DayItem>()
    var group = mutableListOf<Txn>()
    for ((_, item) in timed.sortedByDescending { it.first }) {
        if (item is Txn) {
            group += item
        } else {
            if (group.isNotEmpty()) out += DayItem.Bills(group).also { group = mutableListOf() }
            out += item as DayItem
        }
    }
    if (group.isNotEmpty()) out += DayItem.Bills(group)
    return out
}

/** Bills next to each other on the timeline: rows on the page, hairlines between them, lined up with the Moments' words. */
@Composable
fun BillGroup(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        txns.forEachIndexed { index, txn ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = dividerColor())
            TxnRow(txn, ledger, onClick = { onBill(txn.id) }, modifier = Modifier.bleed(Space.l))
        }
    }
}

/** The bills written with a Moment, at its foot under a hairline. */
@Composable
fun LinkedBills(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit) {
    HorizontalDivider(Modifier.padding(top = Space.s), color = dividerColor())
    txns.forEach { txn -> TxnRow(txn, ledger, onClick = { onBill(txn.id) }, modifier = Modifier.bleed(Space.l)) }
}

/** Out past its edges by [by] each side, so a list row's own inset lines its content up with what is around it. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

/**
 * Where a trip begins on the timeline (its last day, as the newest come first): a row on the
 * page, its route icon, its name, its dates and length, and an arrow; it grows into the trip's
 * page when tapped.
 */
@Composable
fun TripBanner(trip: TripRecord, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .sharedCard(tripCardKey(trip.id))
            .clip(AppTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = Space.m)
            .testTag("trip_${trip.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(AppIcons.Route, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(trip.title, style = AppTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripSummary(trip, count), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}
