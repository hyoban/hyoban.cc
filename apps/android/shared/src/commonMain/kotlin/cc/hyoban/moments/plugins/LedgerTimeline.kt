package cc.hyoban.moments.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.BubbleFoot
import cc.hyoban.moments.ui.feed.BubbleShape
import cc.hyoban.moments.ui.feed.DayAnchor
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.LocalCheckinNav
import cc.hyoban.moments.ui.feed.PageRow
import cc.hyoban.moments.ui.feed.RecordRow
import cc.hyoban.moments.ui.feed.TAIL
import cc.hyoban.moments.ui.feed.bubbleWidth
import cc.hyoban.moments.ui.feed.instantOf
import cc.hyoban.moments.ui.feed.timeSpan
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.billAmount
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnSupporting
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What the timeline lists of the ledger: money out and in, and money back. */
val SHOWN_KINDS = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.REFUND, TxnKind.REIMBURSEMENT)


/** Opens a bill's page, where the ledger's pages are. */
@Composable
internal fun openBill(id: String): () -> Unit {
    val nav = LocalLedgerNav.current
    return { nav?.bill?.invoke(id) }
}

/**
 * A bill on the timeline: on the page a row by its time, in a records bubble a line, and at the
 * foot of the Moment or check-in it is linked to a line too, in the card on the page.
 */
class BillRecord(val txn: Txn, val ledger: Ledger) : DayRecord {
    override val key get() = "bill-${txn.id}"
    override val time get() = txn.local.toLocalTime().toString().take(8)
    override val instant get() = txn.time

    @Composable override fun open(onDay: (() -> Unit)?): (() -> Unit)? = openBill(txn.id)

    @Composable override fun Row(modifier: Modifier) = BillRow(txn, ledger, onClick = openBill(txn.id), modifier)

    @Composable override fun Line(large: Boolean, timed: Boolean) = RecordRow(
        icon = { Icon(txnIcon(txn, ledger), contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
        title = txnTitle(txn, ledger),
        time = txn.local.format(TIME),
        end = billAmount(txn, ledger),
        endColor = amountColor(txn),
        large = large,
        timed = timed,
        tag = "bill_${txn.id}",
    )

    @Composable override fun Attached(large: Boolean, bubbleTime: String?) {
        val at = txn.local.format(TIME)
        Box(Modifier.clickable(onClick = openBill(txn.id))) {
            RecordRow(
                icon = { Icon(txnIcon(txn, ledger), contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                title = txnTitle(txn, ledger),
                time = at,
                end = billAmount(txn, ledger),
                endColor = amountColor(txn),
                large = large,
                timed = at != bubbleTime,
                tag = "bill_${txn.id}",
                timeAtEnd = true,
            )
        }
    }

    @Composable override fun AttachedRow() = BillRow(txn, ledger, onClick = openBill(txn.id))
}

/** A bill as a row on the timeline's page: its time, its category's icon, its name and what sets it apart, and its amount. */
@Composable
fun BillRow(txn: Txn, ledger: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(txnIcon(txn, ledger), contentDescription = null) },
        title = txnTitle(txn, ledger),
        supporting = txnSupporting(txn, ledger, withTime = false, place = cc.hyoban.moments.footprints.footprintPlace(txn)),
        onClick = onClick,
        modifier = modifier.testTag("bill_${txn.id}"),
        time = txn.local.format(TIME),
        below = { cc.hyoban.moments.ui.ledger.RefundTag(txn, ledger) },
    ) {
        Text(cc.hyoban.moments.ui.ledger.billAmount(txn, ledger), style = AppTheme.typography.body, color = amountColor(txn))
    }
}

