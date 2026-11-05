package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.txnsOfDay
import cc.hyoban.moments.ledger.txnsOfMoment
import cc.hyoban.moments.ledger.txnsOfPlace
import cc.hyoban.moments.ledger.txnsOfTrip
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space

/** Which of the Moments' pages a set of bills is for. */
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
