package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Discount
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.ui.draw.clip
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ledger.Reimburse
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * One bill: its amount, what it is and where it moved money, what it saved or cost besides,
 * its note and tags, the Moment, place, trip and day it belongs to, and its refunds and
 * reimbursements, with ways to add them, change it and delete it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun BillScreen(container: AppContainer, id: String, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val txn = ledger.txnsById[id] ?: return
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    val returns = ledger.returns[txn.id].orEmpty()
    val moment = txn.moment?.let { uid -> moments.firstOrNull { it.uid == uid } }
    val placeId = txn.place ?: moment?.places?.firstOrNull()
    val tripId = txn.trip ?: moment?.let { tripOf(it, trips)?.id }

    SubPage(
        txnTitle(txn, ledger),
        nav.back,
        subtitle = ledger.books.firstOrNull { it.id == txn.book }?.name,
        actions = {
            Tooltip(stringResource(R.string.action_edit), below = true) {
                IconButton(onClick = { nav.entry(LedgerEntryArgs(edit = txn.id)) }, modifier = Modifier.testTag("edit_bill")) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
            Tooltip(stringResource(R.string.action_delete), below = true) {
                IconButton(onClick = { deleting = true }, modifier = Modifier.testTag("delete_bill")) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl),
            ) {
                Column(Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.s).semantics(mergeDescendants = true) {}) {
                    Text(
                        if (txn.kind == TxnKind.TRANSFER) transferLabel(txn.transfer) else kindLabel(txn.kind),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (txn.kind == TxnKind.TRANSFER) money(txn.amount, txn.currency) else signedMoney(txn.amount, txn.currency, txn.incoming),
                        style = MaterialTheme.typography.displaySmall,
                        color = amountColor(txn),
                        modifier = Modifier.testTag("bill_amount"),
                    )
                    if (txn.discount > 0 && txn.kind == TxnKind.EXPENSE) {
                        Text(
                            stringResource(R.string.ledger_saved, money(txn.discount, txn.currency), money(txn.amount + txn.discount, txn.currency)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val details = buildList<@Composable (ListItemShapes) -> Unit> {
                    add { shapes -> InfoRow(shapes, Icons.Rounded.Event, stringResource(R.string.ledger_time), formatDay(txn.dateKey) + " " + txn.local.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                    if (txn.kind == TxnKind.TRANSFER) {
                        txn.account?.let(ledger.accountsById::get)?.let { from ->
                            add { shapes ->
                                InfoRow(shapes, accountKindIcon(from.kind), stringResource(R.string.ledger_from_account), from.name,
                                    trailing = txn.accountAmount?.let { money(it, from.currency) }) { nav.account(from.id) }
                            }
                        }
                        txn.toAccount?.let(ledger.accountsById::get)?.let { to ->
                            add { shapes ->
                                InfoRow(shapes, accountKindIcon(to.kind), stringResource(R.string.ledger_to_account), to.name,
                                    trailing = txn.toAmount?.let { money(it, to.currency) }) { nav.account(to.id) }
                            }
                        }
                        if (txn.fee > 0) add { shapes -> InfoRow(shapes, Icons.Rounded.RequestQuote, stringResource(R.string.ledger_fee), money(txn.fee, txn.currency)) }
                        if (txn.discount > 0) add { shapes -> InfoRow(shapes, Icons.Rounded.Discount, stringResource(R.string.ledger_discount), money(txn.discount, txn.currency)) }
                    } else {
                        txn.payers.forEach { split ->
                            val account = ledger.accountsById[split.account] ?: return@forEach
                            add { shapes ->
                                InfoRow(shapes, accountKindIcon(account.kind), stringResource(R.string.ledger_account), account.name,
                                    trailing = if (txn.splits.isNotEmpty()) money(split.amount, txn.currency)
                                    else txn.accountAmount?.let { money(it, account.currency) }) { nav.account(account.id) }
                            }
                        }
                    }
                    if (txn.note.isNotBlank()) add { shapes -> InfoRow(shapes, Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.ledger_note), txn.note, lines = 6) }
                    when (txn.reimburse) {
                        Reimburse.PENDING -> add { shapes -> InfoRow(shapes, Icons.Rounded.RequestQuote, stringResource(R.string.ledger_reimbursable), stringResource(R.string.ledger_reimburse_pending)) }
                        Reimburse.DONE -> add { shapes -> InfoRow(shapes, Icons.Rounded.RequestQuote, stringResource(R.string.ledger_reimbursable), stringResource(R.string.ledger_reimburse_done)) }
                        Reimburse.NONE -> Unit
                    }
                    if (txn.excluded) add { shapes -> InfoRow(shapes, Icons.Rounded.Block, stringResource(R.string.ledger_excluded), stringResource(R.string.ledger_excluded_note)) }
                }
                SegmentedGroup(null, null, details)

                if (txn.tags.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        txn.tags.forEach { tag ->
                            AssistChip(onClick = { nav.list(LedgerFilter(title = "#$tag", tag = tag)) }, label = { Text("#$tag") })
                        }
                    }
                }

                // Where it leads among the Moments' pages.
                val links = buildList<@Composable (ListItemShapes) -> Unit> {
                    if (moment != null) {
                        add { shapes ->
                            InfoRow(shapes, Icons.Rounded.PhotoLibrary, stringResource(R.string.ledger_link_moment), moment.text.lines().firstOrNull()?.ifBlank { null } ?: formatDay(moment.dateKey)) { nav.moment(moment.uid) }
                        }
                    }
                    placeId?.let { place ->
                        add { shapes -> InfoRow(shapes, Icons.Rounded.Place, stringResource(R.string.ledger_link_place), places[place]?.name ?: place) { nav.place(place) } }
                    }
                    tripId?.let { trip ->
                        trips.firstOrNull { it.id == trip }?.let { record ->
                            add { shapes -> InfoRow(shapes, Icons.Rounded.Luggage, stringResource(R.string.ledger_link_trip), record.title) { nav.trip(record.id) } }
                        }
                    }
                    add { shapes -> InfoRow(shapes, Icons.Rounded.CalendarToday, stringResource(R.string.label_whole_day), formatDay(txn.dateKey)) { nav.day(txn.dateKey) } }
                }
                SegmentedGroup(stringResource(R.string.ledger_links), null, links)

                // The expense it pays back, or what has paid this one back.
                txn.related?.let(ledger.txnsById::get)?.let { expense ->
                    SegmentedGroup(stringResource(R.string.ledger_original_bill), null, listOf { shapes ->
                        InfoRow(shapes, txnIcon(expense, ledger), txnTitle(expense, ledger), formatDay(expense.dateKey), trailing = money(expense.amount, expense.currency)) { nav.bill(expense.id) }
                    })
                }
                if (txn.kind == TxnKind.EXPENSE) {
                    if (returns.isNotEmpty()) {
                        SegmentedGroup(
                            stringResource(R.string.ledger_returns),
                            pluralStringResource(R.plurals.ledger_count_bills, returns.size, returns.size),
                            returns.sortedBy { it.time }.map { back ->
                                { shapes ->
                                    InfoRow(shapes, txnIcon(back, ledger), kindLabel(back.kind), formatDay(back.dateKey) + " " + (back.account?.let(ledger.accountsById::get)?.name ?: ""),
                                        trailing = signedMoney(back.amount, back.currency, true)) { nav.bill(back.id) }
                                }
                            },
                        )
                    }
                    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        FilledTonalButton(onClick = { nav.entry(LedgerEntryArgs(kind = TxnKind.REFUND, related = txn.id)) }, modifier = Modifier.testTag("refund")) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                            Text(stringResource(R.string.ledger_action_refund), modifier = Modifier.padding(start = Space.s))
                        }
                        if (txn.reimburse != Reimburse.NONE) {
                            FilledTonalButton(onClick = { nav.entry(LedgerEntryArgs(kind = TxnKind.REIMBURSEMENT, related = txn.id)) }, modifier = Modifier.testTag("reimburse")) {
                                Icon(Icons.Rounded.RequestQuote, contentDescription = null)
                                Text(stringResource(R.string.ledger_record_reimbursement), modifier = Modifier.padding(start = Space.s))
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleting) {
        val dependents = returns.size
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.ledger_delete_bill)) },
            text = { if (dependents > 0) Text(pluralStringResource(R.plurals.ledger_delete_with_returns, dependents, dependents)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        container.ledger.deleteTxns(listOf(txn.id))
                        nav.back()
                    }
                }, modifier = Modifier.testTag("confirm_delete")) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A detail of a bill or an account as a row of a segmented list: what it is above, its value, and where it leads. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InfoRow(
    shapes: ListItemShapes,
    icon: ImageVector,
    label: String,
    value: String,
    trailing: String? = null,
    lines: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    val trailingContent: (@Composable () -> Unit)? = when {
        trailing != null -> { { Text(trailing, style = MaterialTheme.typography.titleSmall) } }
        onClick != null -> { { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) } }
        else -> null
    }
    if (onClick == null) {
        // Leading nowhere, a plain row on the group's fill rather than one that answers a tap.
        ListItem(
            headlineContent = { Text(value, maxLines = lines, overflow = TextOverflow.Ellipsis) },
            overlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(containerColor = LocalSurfaceLevel.current.raised.fill(), leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.clip(shapes.shape),
        )
        return
    }
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        overlineContent = { Text(label) },
        trailingContent = trailingContent,
    ) { Text(value, maxLines = lines, overflow = TextOverflow.Ellipsis) }
}
