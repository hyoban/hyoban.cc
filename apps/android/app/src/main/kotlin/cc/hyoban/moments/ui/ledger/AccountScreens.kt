package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.ledger.Account
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.COMMON_CURRENCIES
import cc.hyoban.moments.ledger.currencySymbol
import cc.hyoban.moments.ledger.uses
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.launch

/** An amount that may be below zero, as an account's balance is: `-1200.5`. */
private fun parseSigned(text: String): Long? {
    val trimmed = text.trim()
    val negative = trimmed.startsWith("-")
    return parseAmount(trimmed.removePrefix("-").removePrefix("+"))?.let { if (negative) -it else it }
}

/** An account: its balance in its currency, a way to set it to what it really is, and its bills. */
@Composable
fun AccountScreen(container: AppContainer, id: String, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val account = ledger.accountsById[id] ?: return
    val scope = rememberCoroutineScope()
    val balance = ledger.balances[id] ?: 0
    val bills = remember(ledger, id) { ledger.txns.filter { it.uses(id) } }
    var calibrating by remember { mutableStateOf(false) }

    SubPage(
        account.name,
        nav.back,
        subtitle = accountKindLabel(account.kind) + " " + account.currency,
        actions = {
            Tooltip(stringResource(R.string.action_edit), below = true) {
                IconButton(onClick = { nav.editAccount(id) }, modifier = Modifier.testTag("edit_account")) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        },
    ) {
        BillList(ledger, bills, account.currency, nav, header = {
            Column(Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.s, bottom = Space.s)) {
                Text(stringResource(R.string.ledger_balance_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(balance, account.currency), style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("account_balance"))
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FilledTonalButton(onClick = { nav.entry(LedgerEntryArgs(account = id)) }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text(stringResource(R.string.ledger_new_bill), modifier = Modifier.padding(start = Space.s))
                    }
                    OutlinedButton(onClick = { calibrating = true }, modifier = Modifier.testTag("calibrate")) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Text(stringResource(R.string.ledger_calibrate), modifier = Modifier.padding(start = Space.s))
                    }
                }
            }
        })
    }

    if (calibrating) {
        var value by remember { mutableStateOf(fieldOf(kotlin.math.abs(balance)).let { if (balance < 0) "-$it" else it }) }
        AlertDialog(
            onDismissRequest = { calibrating = false },
            title = { Text(stringResource(R.string.ledger_calibrate)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(stringResource(R.string.ledger_calibrate_note), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value, { value = it },
                        prefix = { Text(currencySymbol(account.currency)) },
                        isError = parseSigned(value) == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("calibrate_field"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = parseSigned(value) ?: return@TextButton
                    calibrating = false
                    scope.launch { container.ledger.saveAccount(account.copy(opening = account.opening + target - balance)) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { calibrating = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * An account to add or change, a full-screen dialog: its name, kind and currency, what it
 * held before its first bill, whether it counts in the net assets, and archiving it, or
 * deleting it while no bill uses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountEditScreen(container: AppContainer, id: String?, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val original = id?.let(ledger.accountsById::get)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(original?.kind ?: AccountKind.DEBIT) }
    var currency by rememberSaveable { mutableStateOf(original?.currency ?: ledger.book?.currency ?: "CNY") }
    var opening by rememberSaveable { mutableStateOf(original?.opening?.let { if (it < 0) "-" + fieldOf(-it) else fieldOf(it) }.orEmpty()) }
    var counted by rememberSaveable { mutableStateOf(original?.counted ?: true) }
    var archived by rememberSaveable { mutableStateOf(original?.archived ?: false) }
    var note by rememberSaveable { mutableStateOf(original?.note.orEmpty()) }
    val used = original != null && ledger.txns.any { it.uses(original.id) }
    var deleting by remember { mutableStateOf(false) }

    fun save() {
        if (name.isBlank()) return
        val account = Account(
            id = original?.id ?: Ulid.next(),
            name = name.trim(),
            kind = kind,
            currency = currency,
            opening = parseSigned(opening.ifBlank { "0" }) ?: 0,
            counted = counted,
            archived = archived,
            note = note.trim(),
            position = original?.position ?: ledger.accounts.size,
        )
        scope.launch {
            container.ledger.saveAccount(account)
            nav.back()
        }
    }

    SubPage(
        stringResource(if (original == null) R.string.ledger_add_account else R.string.ledger_edit_account),
        nav.back,
        close = true,
        actions = { TextButton(onClick = ::save, enabled = name.isNotBlank(), modifier = Modifier.testTag("save_account")) { Text(stringResource(R.string.action_save)) } },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl),
            ) {
                Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ledger_account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("account_name"))
                    Text(stringResource(R.string.ledger_account_kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        AccountKind.entries.forEach { entry ->
                            FilterChip(
                                selected = kind == entry,
                                onClick = { kind = entry },
                                label = { Text(accountKindLabel(entry)) },
                                leadingIcon = { Icon(accountKindIcon(entry), contentDescription = null) },
                            )
                        }
                    }
                    Box {
                        var open by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("account_currency")) {
                            Text(stringResource(R.string.ledger_currency) + " " + currency)
                        }
                        DropdownMenu(open, onDismissRequest = { open = false }) {
                            (COMMON_CURRENCIES + ledger.rates.currencies.sorted()).distinct().forEach { code ->
                                DropdownMenuItem(text = { Text(code) }, onClick = { currency = code; open = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        opening, { opening = it },
                        label = { Text(stringResource(R.string.ledger_opening)) },
                        supportingText = { Text(stringResource(if (kind.liability) R.string.ledger_opening_owed_note else R.string.ledger_opening_note)) },
                        prefix = { Text(currencySymbol(currency)) },
                        isError = opening.isNotBlank() && parseSigned(opening) == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.ledger_note)) }, modifier = Modifier.fillMaxWidth())
                }
                SegmentedGroup(
                    null,
                    null,
                    listOf(
                        { shapes -> SwitchRow(counted, Icons.Rounded.Visibility, stringResource(R.string.ledger_counted), "counted", shapes, supporting = stringResource(R.string.ledger_counted_note)) { counted = !counted } },
                        { shapes -> SwitchRow(archived, Icons.Rounded.Archive, stringResource(R.string.ledger_archive), "archived", shapes, supporting = stringResource(R.string.ledger_archive_note)) { archived = !archived } },
                    ),
                )
                if (original != null) {
                    TextButton(
                        onClick = { deleting = true },
                        enabled = !used,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l),
                    ) { Text(stringResource(if (used) R.string.ledger_account_in_use else R.string.ledger_delete_account), color = if (used) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (deleting && original != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.ledger_delete_account)) },
            text = { Text(original.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        container.ledger.deleteAccount(original.id)
                        nav.back()
                        nav.back()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
