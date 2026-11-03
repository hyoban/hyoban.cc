package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.layout.Box
import cc.hyoban.moments.ui.components.SIDE_COLUMN
import cc.hyoban.moments.ui.components.SideBySide
import cc.hyoban.moments.ui.components.isPhoneLandscape
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.data.store.isPublic
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.AppIcons
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
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.AssistChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ListItemDefaults
import cc.hyoban.moments.ui.design.ListItemShapes
import androidx.compose.ui.draw.clip
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
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
 * its note, where the footprints put it, the Moment, place, trip and day it belongs to, and its refunds and
 * reimbursements, with ways to add them, change it and delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillScreen(container: AppContainer, id: String, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val txn = ledger.txnsById[id] ?: return
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    val returns = ledger.returns[txn.id].orEmpty()
    val moment = txn.moment?.let { uid -> moments.firstOrNull { it.uid == uid } }
    val placeId = txn.place ?: moment?.places?.firstOrNull()
    val tripId = txn.trip ?: moment?.let { tripOf(it, trips)?.id }
    val footprintPlace = cc.hyoban.moments.footprints.footprintPlace(txn)
    val billPlaces = cc.hyoban.moments.footprints.LocalBillPlaces.current
    // The footprints' spot and its name, while it is being kept as a place in the place picker.
    var keeping by remember { mutableStateOf<Pair<cc.hyoban.moments.data.local.Fix, String>?>(null) }
    keeping?.let { (point, name) ->
        cc.hyoban.moments.ui.compose.KeepPlace(container, point, name, onDismiss = { keeping = null }) { place ->
            container.ledger.saveTxn(txn.copy(place = place))
        }
    }

    SubPage(
        txnTitle(txn, ledger),
        nav.back,
        subtitle = ledger.books.firstOrNull { it.id == txn.book }?.name,
        actions = {
            Tooltip(stringResource(R.string.action_edit), below = true) {
                IconButton(onClick = { nav.entry(LedgerEntryArgs(edit = txn.id)) }, modifier = Modifier.testTag("edit_bill")) {
                    Icon(AppIcons.Pen, contentDescription = stringResource(R.string.action_edit))
                }
            }
            Tooltip(stringResource(R.string.action_delete), below = true) {
                IconButton(onClick = { deleting = true }, modifier = Modifier.testTag("delete_bill")) {
                    Icon(AppIcons.Trash, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    ) {
        // On a phone on its side what it was and how much stand in a column at the start, the rest
        // beside them (H20).
        val sideways = isPhoneLandscape()
        val header: @Composable () -> Unit = {
                Column(Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.s).semantics(mergeDescendants = true) {}) {
                    Text(
                        if (txn.kind == TxnKind.TRANSFER) transferLabel(txn.transfer) else kindLabel(txn.kind),
                        style = AppTheme.typography.label,
                        color = tokens.tx2,
                    )
                    Text(
                        if (txn.kind == TxnKind.TRANSFER) money(txn.amount, txn.currency) else signedMoney(txn.amount, txn.currency, txn.incoming),
                        style = AppTheme.typography.titlePage,
                        color = amountColor(txn),
                        modifier = Modifier.testTag("bill_amount"),
                    )
                    if (txn.discount > 0 && txn.kind == TxnKind.EXPENSE) {
                        Text(
                            stringResource(R.string.ledger_saved, money(txn.discount, txn.currency), money(txn.amount + txn.discount, txn.currency)),
                            style = AppTheme.typography.body,
                            color = tokens.tx2,
                        )
                    }
                }
        }
        val page: @Composable () -> Unit = { PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl),
            ) {
                if (!sideways) header()

                val details = buildList<@Composable (ListItemShapes) -> Unit> {
                    add { shapes -> InfoRow(shapes, AppIcons.Calendar, stringResource(R.string.ledger_time), formatDay(txn.dateKey) + " " + txn.local.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                    // Where the footprints were then, for a bill with no place of its own; its day's page shows them.
                    // Kept as a place, it becomes the bill's.
                    footprintPlace?.let { name ->
                        add { shapes ->
                            SegmentedListItem(
                                onClick = { nav.day(txn.dateKey) },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.Pin, contentDescription = null) },
                                overlineContent = { Text(stringResource(R.string.ledger_footprint_place)) },
                                trailingContent = {
                                    IconButton(onClick = { scope.launch { billPlaces?.pointAt(txn.time)?.let { keeping = it to name } } }, modifier = Modifier.testTag("bill_save_place")) {
                                        Icon(AppIcons.Plus, contentDescription = stringResource(R.string.share_save_place))
                                    }
                                },
                            ) { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (txn.kind == TxnKind.TRANSFER) {
                        txn.account?.let(ledger.accountsById::get)?.let { from ->
                            add { shapes ->
                                InfoRow(shapes, accountIcon(from), stringResource(R.string.ledger_from_account), accountName(from),
                                    trailing = txn.accountAmount?.let { money(it, from.currency) }) { nav.account(from.id) }
                            }
                        }
                        txn.toAccount?.let(ledger.accountsById::get)?.let { to ->
                            add { shapes ->
                                InfoRow(shapes, accountIcon(to), stringResource(R.string.ledger_to_account), accountName(to),
                                    trailing = txn.toAmount?.let { money(it, to.currency) }) { nav.account(to.id) }
                            }
                        }
                        if (txn.fee > 0) add { shapes -> InfoRow(shapes, AppIcons.Bill, stringResource(R.string.ledger_fee), money(txn.fee, txn.currency)) }
                        if (txn.discount > 0) add { shapes -> InfoRow(shapes, AppIcons.Discount, stringResource(R.string.ledger_discount), money(txn.discount, txn.currency)) }
                    } else {
                        txn.payers.forEach { split ->
                            val account = ledger.accountsById[split.account] ?: return@forEach
                            add { shapes ->
                                InfoRow(shapes, accountIcon(account), stringResource(R.string.ledger_account), accountName(account),
                                    trailing = if (txn.splits.isNotEmpty()) money(split.amount, txn.currency)
                                    else txn.accountAmount?.let { money(it, account.currency) }) { nav.account(account.id) }
                            }
                        }
                    }
                    if (txn.note.isNotBlank()) add { shapes -> InfoRow(shapes, AppIcons.Notes, stringResource(R.string.ledger_note), txn.note, lines = 6) }
                    when (txn.reimburse) {
                        Reimburse.PENDING -> add { shapes -> InfoRow(shapes, AppIcons.Bill, stringResource(R.string.ledger_reimbursable), stringResource(R.string.ledger_reimburse_pending)) }
                        Reimburse.DONE -> add { shapes -> InfoRow(shapes, AppIcons.Bill, stringResource(R.string.ledger_reimbursable), stringResource(R.string.ledger_reimburse_done)) }
                        Reimburse.NONE -> Unit
                    }
                    if (txn.excluded) add { shapes -> InfoRow(shapes, AppIcons.Block, stringResource(R.string.ledger_excluded), stringResource(R.string.ledger_excluded_note)) }
                }
                SegmentedGroup(null, null, details)

                // Who sees it: its book's say for new bills, until it says otherwise itself.
                if (txn.kind == cc.hyoban.moments.ledger.TxnKind.EXPENSE || txn.kind == cc.hyoban.moments.ledger.TxnKind.INCOME) {
                    val public = ledger.isPublic(txn)
                    SegmentedGroup(
                        null,
                        stringResource(if (public) R.string.changes_bill_public_note else R.string.moment_private_note),
                        listOf { shapes ->
                            cc.hyoban.moments.ui.settings.SwitchRow(public, AppIcons.Eye, stringResource(R.string.visibility_public), "bill_public", shapes) {
                                scope.launch { container.ledger.saveTxn(txn.copy(visibility = if (public) "private" else "public")) }
                            }
                        },
                    )
                }

                // Where it leads among the Moments' pages.
                val links = buildList<@Composable (ListItemShapes) -> Unit> {
                    if (moment != null) {
                        add { shapes ->
                            InfoRow(shapes, AppIcons.Photos, stringResource(R.string.ledger_link_moment), moment.text.lines().firstOrNull()?.ifBlank { null } ?: formatDay(moment.dateKey)) { nav.moment(moment.uid) }
                        }
                    }
                    placeId?.let { place ->
                        add { shapes -> InfoRow(shapes, AppIcons.Pin, stringResource(R.string.ledger_link_place), places[place]?.name ?: place) { nav.place(place) } }
                    }
                    tripId?.let { trip ->
                        trips.firstOrNull { it.id == trip }?.let { record ->
                            add { shapes -> InfoRow(shapes, AppIcons.Luggage, stringResource(R.string.ledger_link_trip), record.title) { nav.trip(record.id) } }
                        }
                    }
                    add { shapes -> InfoRow(shapes, AppIcons.Calendar, stringResource(R.string.label_whole_day), formatDay(txn.dateKey)) { nav.day(txn.dateKey) } }
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
                                    InfoRow(shapes, txnIcon(back, ledger), kindLabel(back.kind), formatDay(back.dateKey) + " " + (back.account?.let(ledger.accountsById::get)?.name ?: ""),
                                        trailing = signedMoney(back.amount, back.currency, true)) { nav.bill(back.id) }
                                }
                            },
                        )
                    }
                    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        TextButton(onClick = { nav.entry(LedgerEntryArgs(kind = TxnKind.REFUND, related = txn.id)) }, modifier = Modifier.testTag("refund")) {
                            Icon(AppIcons.Undo, contentDescription = null)
                            Text(stringResource(R.string.ledger_action_refund), modifier = Modifier.padding(start = Space.s))
                        }
                        if (txn.reimburse != Reimburse.NONE) {
                            TextButton(onClick = { nav.entry(LedgerEntryArgs(kind = TxnKind.REIMBURSEMENT, related = txn.id)) }, modifier = Modifier.testTag("reimburse")) {
                                Icon(AppIcons.Bill, contentDescription = null)
                                Text(stringResource(R.string.ledger_record_reimbursement), modifier = Modifier.padding(start = Space.s))
                            }
                        }
                    }
                }
            }
        } }
        if (sideways) {
            SideBySide(
                Modifier.fillMaxSize(),
                sideWidth = SIDE_COLUMN + Space.gutter,
                sideInset = 0.dp,
                side = { Column(Modifier.padding(top = LocalTopClearance.current)) { header() } },
            ) { Box(Modifier.weight(1f)) { page() } }
        } else {
            page()
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
        trailing != null -> { { Text(trailing, style = AppTheme.typography.body.strong) } }
        onClick != null -> { { Icon(AppIcons.ChevronRight, contentDescription = null) } }
        else -> null
    }
    if (onClick == null) {
        // Leading nowhere, a plain row on the group's fill rather than one that answers a tap.
        ListItem(
            headlineContent = { Text(value, maxLines = lines, overflow = TextOverflow.Ellipsis) },
            overlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(containerColor = LocalSurfaceLevel.current.raised.fill(), leadingIconColor = tokens.tx2),
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
