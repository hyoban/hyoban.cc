package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.cardBorder
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.spendingOf
import cc.hyoban.moments.ledger.txnsOfDay
import cc.hyoban.moments.ledger.txnsOfMoment
import cc.hyoban.moments.ledger.txnsOfPlace
import cc.hyoban.moments.ledger.txnsOfTrip
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

/** Which of the Moments' pages a spending card is on. */
sealed interface SpendingOf {
    data class Trip(val id: String) : SpendingOf
    data class Place(val id: String) : SpendingOf
    data class Day(val date: String) : SpendingOf
}

internal fun billsOf(of: SpendingOf, ledger: Ledger, moments: Map<String, MomentRecord>, trips: List<TripRecord>): List<Txn> = when (of) {
    is SpendingOf.Trip -> txnsOfTrip(ledger, of.id, moments, trips)
    is SpendingOf.Place -> txnsOfPlace(ledger, of.id, moments)
    is SpendingOf.Day -> txnsOfDay(ledger, of.date)
}

/**
 * What a trip, a place or a day cost, from the ledger on this phone: a card with the sum and
 * how many bills, leading to them; nothing when it has none.
 */
@Composable
fun SpendingCard(container: AppContainer, of: SpendingOf, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val byUid = remember(moments) { moments.associateBy { it.uid } }
    val bills = remember(ledger, of, byUid, trips) { billsOf(of, ledger, byUid, trips) }
    if (bills.isEmpty()) return
    val currency = ledger.book?.currency ?: "CNY"
    val sums = remember(ledger, bills, currency) { spendingOf(ledger, bills, currency) }
    // A trip's days spend from other books too, a road trip's tolls and charging: those days' whole spending.
    val days = (of as? SpendingOf.Trip)?.let { trip -> trips.firstOrNull { it.id == trip.id } }
    val whole = remember(ledger, days, currency) {
        days?.let { trip -> spendingOf(ledger, ledger.txns.filter { it.kind != TxnKind.TRANSFER && it.dateKey >= trip.start && it.dateKey <= trip.end }, currency) }
    }
    Surface(
        onClick = onOpen,
        shape = AppTheme.shapes.large,
        color = LocalSurfaceLevel.current.raised.fill(),
        border = cardBorder(),
        modifier = modifier.padding(horizontal = Space.gutter).fillMaxWidth().testTag("spending"),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.ledger_spent, money(sums.expense, currency))) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.ledger_count_bills, bills.size, bills.size),
                        if (sums.income > 0) stringResource(R.string.ledger_income_short, money(sums.income, currency)) else null,
                        whole?.takeIf { it.expense != sums.expense }?.let { stringResource(R.string.ledger_trip_days_spent, money(it.expense, currency)) },
                        if (sums.unconverted > 0) stringResource(R.string.ledger_some_unconverted) else null,
                    ).joinToString(" "),
                )
            },
            leadingContent = { LeadingIcon(AppIcons.Receipt) },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/**
 * A Moment's bills as rows of its page's segmented list, each leading to its bill; with none
 * and a meal's bill written in the Moment, a row that writes the bill from it.
 */
fun momentBillRows(
    ledger: Ledger,
    moment: MomentRecord,
    onBill: (String) -> Unit,
    onNewBill: (String) -> Unit,
): List<@Composable (ListItemShapes) -> Unit> {
    val bills = txnsOfMoment(ledger, moment.uid)
    if (bills.isNotEmpty()) {
        return bills.map { txn ->
            { shapes ->
                SegmentedListItem(
                    onClick = { onBill(txn.id) },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(txnIcon(txn, ledger), contentDescription = null) },
                    supportingContent = { Text(txnSupporting(txn, ledger), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    // Its amount in figures, as the timeline has it, before the arrow every link on the page ends in.
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(
                                if (txn.kind == TxnKind.TRANSFER) money(txn.amount, txn.currency) else signedMoney(txn.amount, txn.currency, txn.incoming),
                                style = AppTheme.typography.body,
                                color = amountColor(txn),
                            )
                            Icon(AppIcons.ChevronRight, contentDescription = null)
                        }
                    },
                    modifier = Modifier.testTag("moment_bill_${txn.id}"),
                ) { Text(txnTitle(txn, ledger), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    val bill = moment.food?.bill ?: return emptyList()
    return listOf { shapes ->
        SegmentedListItem(
            onClick = { onNewBill(moment.uid) },
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Plus, contentDescription = null) },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
            supportingContent = { Text(stringResource(R.string.ledger_from_moment_note)) },
            modifier = Modifier.testTag("moment_new_bill"),
        ) { Text(stringResource(R.string.ledger_from_moment, money(Math.round(bill.amount * 100), "CNY"))) }
    }
}
