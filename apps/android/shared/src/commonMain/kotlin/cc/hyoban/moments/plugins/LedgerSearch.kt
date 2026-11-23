package cc.hyoban.moments.plugins

import androidx.compose.runtime.Composable
import cc.hyoban.moments.data.store.categoryLabel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.SearchFound
import cc.hyoban.moments.ui.feed.SearchKind
import cc.hyoban.moments.ui.feed.SearchRow
import cc.hyoban.moments.ui.feed.SearchScope
import cc.hyoban.moments.ui.feed.ownYear
import cc.hyoban.moments.ui.theme.tokens

/** The bills the search lists: by their category and note. */
internal object BillsSearch : SearchKind {
    override val key = "bills"
    override val label = Res.string.section_bill

    override fun matches(scope: SearchScope): List<SearchFound> {
        val joined = scope.records.of<LedgerJoined>(cc.hyoban.moments.data.spec.Module.LEDGER) ?: return emptyList()
        val ledger = joined.ledger
        return (joined.linked.values.flatten() + joined.loose.values.flatten()).filter { txn ->
            scope.text.isEmpty() || txn.note.contains(scope.text, ignoreCase = true) ||
                ledger.categoryLabel(txn)?.contains(scope.text, ignoreCase = true) == true
        }.map { BillFound(it, ledger) }
    }
}

private class BillFound(val txn: cc.hyoban.moments.ledger.Txn, val ledger: cc.hyoban.moments.ledger.Ledger) : SearchFound {
    override val key get() = "bill-${txn.id}"
    override val at get() = txn.local.toString()

    @Composable override fun Row(scope: SearchScope, modifier: Modifier) = SearchBill(txn, ledger, modifier, openBill(txn.id))
}

/** A bill found, as the timeline lists one, its category's icon in a photo's room and its day in the line under it, since the list spans days. */
@Composable
private fun SearchBill(txn: cc.hyoban.moments.ledger.Txn, ledger: cc.hyoban.moments.ledger.Ledger, modifier: Modifier, onClick: () -> Unit) {
    SearchRow(
        lead = { Icon(cc.hyoban.moments.ui.ledger.txnIcon(txn, ledger), contentDescription = null, tint = tokens.tx2) },
        onClick = onClick,
        modifier = modifier.testTag("bill_${txn.id}"),
        trailing = {
            Text(cc.hyoban.moments.ui.ledger.billAmount(txn, ledger), style = AppTheme.typography.body, color = cc.hyoban.moments.ui.ledger.amountColor(txn))
            Text(txn.local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), style = AppTheme.typography.caption, color = tokens.tx3)
        },
    ) {
        Text(cc.hyoban.moments.ui.ledger.txnTitle(txn, ledger), style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(
                cc.hyoban.moments.ui.format.formatDay(txn.dateKey, ownYear(txn.dateKey)),
                cc.hyoban.moments.ui.ledger.txnSupporting(txn, ledger, withTime = false, place = cc.hyoban.moments.footprints.footprintPlace(txn)),
            ).filter { it.isNotEmpty() }.joinToString(" "),
            style = AppTheme.typography.caption,
            color = tokens.tx2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
