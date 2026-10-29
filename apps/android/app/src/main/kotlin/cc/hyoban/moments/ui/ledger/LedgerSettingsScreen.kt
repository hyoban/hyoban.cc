package cc.hyoban.moments.ui.ledger

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.COMMON_CURRENCIES
import cc.hyoban.moments.ledger.ICostImport
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.LedgerBackup
import cc.hyoban.moments.ledger.Xlsx
import cc.hyoban.moments.ledger.addBook
import cc.hyoban.moments.ledger.autoLink
import cc.hyoban.moments.ledger.writeCsv
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ledger's own settings, as segmented lists: its books and their categories, the exchange
 * rates, and its data, brought in from iCost, kept in a backup of one's own, written out as
 * CSV, and linked to the Moments and trips. [startImport] opens the import's file picker at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LedgerSettingsScreen(container: AppContainer, nav: LedgerNav, startImport: Boolean = false) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var addingBook by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<LedgerBackup?>(null) }
    val importer = rememberICostImport(container)

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(LedgerBackup.encode(LedgerBackup.of(container.ledger.ledger.value))) }
                }
            }.onSuccess { show(context.getString(R.string.ledger_backup_done)) }.onFailure { show(context.getString(R.string.ledger_error_file)) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { LedgerBackup.decode(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) } }
                .onSuccess { restoring = it }
                .onFailure { show(context.getString(R.string.ledger_error_backup)) }
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { writeCsv(container.ledger.ledger.value, it) } }
            }.onSuccess { show(context.getString(R.string.ledger_csv_done)) }.onFailure { show(context.getString(R.string.ledger_error_file)) }
        }
    }
    LaunchedEffect(startImport) { if (startImport) importer.pick() }
    // An iCost export shared into the app, read as soon as this page is up.
    val shared by container.sharedExport.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) container.takeSharedExport()?.let(importer.read) }

    SubPage(stringResource(R.string.ledger_settings_title), nav.back) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl)
                    .semantics { testTagsAsResourceId = true },
            ) {
                SegmentedGroup(
                    stringResource(R.string.ledger_books),
                    stringResource(R.string.ledger_books_note),
                    ledger.books.map<Book, Segment> { book ->
                        { shapes ->
                            val count = ledger.txns.count { it.book == book.id }
                            SegmentedListItem(
                                onClick = { editingBook = book },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null) },
                                supportingContent = {
                                    Text(listOfNotNull(book.currency, pluralStringResource(R.plurals.ledger_count_bills, count, count), stringResource(R.string.ledger_archived).takeIf { book.archived }).joinToString(" "))
                                },
                                modifier = Modifier.testTag("book_${book.name}"),
                            ) { Text(book.name) }
                        }
                    } + listOf<Segment> { shapes ->
                        SegmentedListItem(
                            onClick = { addingBook = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            modifier = Modifier.testTag("add_book"),
                        ) { Text(stringResource(R.string.ledger_add_book)) }
                    },
                )
                SegmentedGroup(
                    stringResource(R.string.ledger_rates),
                    null,
                    listOf { shapes ->
                        val latest = ledger.rates.currencies.filter { it != "CNY" }.mapNotNull { ledger.rates.latest(it)?.first }.maxOrNull()
                        SegmentedListItem(
                            onClick = nav.rates,
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(Icons.Rounded.CurrencyExchange, contentDescription = null) },
                            supportingContent = latest?.let { { Text(stringResource(R.string.ledger_rates_until, it.toString())) } },
                            modifier = Modifier.testTag("ledger_rates"),
                        ) { Text(stringResource(R.string.ledger_rates)) }
                    },
                )
                AutoBillGroup()
                SegmentedGroup(
                    stringResource(R.string.ledger_data),
                    stringResource(R.string.ledger_data_note),
                    listOf(
                        { shapes ->
                            SegmentedListItem(
                                onClick = { importer.pick() }, shapes = shapes, colors = segmentColors(), enabled = !busy,
                                leadingContent = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                                supportingContent = { Text(stringResource(R.string.ledger_import_icost_note)) },
                                modifier = Modifier.testTag("import_icost"),
                            ) { Text(stringResource(R.string.ledger_import_icost)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = {
                                    scope.launch {
                                        val changed = autoLink(container.ledger.ledger.value, container.repository.moments.value, container.repository.trips.value)
                                        container.ledger.saveTxns(changed)
                                        show(context.resources.getQuantityString(R.plurals.ledger_linked, changed.size, changed.size))
                                    }
                                },
                                shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                supportingContent = { Text(stringResource(R.string.ledger_link_all_note)) },
                                modifier = Modifier.testTag("link_all"),
                            ) { Text(stringResource(R.string.ledger_link_all)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { backup.launch("ledger-${LocalDate.now()}.json") }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(Icons.Rounded.Backup, contentDescription = null) },
                            ) { Text(stringResource(R.string.ledger_backup)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                            ) { Text(stringResource(R.string.ledger_restore)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { csv.launch("ledger-${LocalDate.now()}.csv") }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(Icons.Rounded.TableChart, contentDescription = null) },
                            ) { Text(stringResource(R.string.ledger_export_csv)) }
                        },
                    ),
                )
            }
        }
    }

    importer.Dialog()
    editingBook?.let { book ->
        BookDialog(
            book,
            canDelete = ledger.books.size > 1,
            onDismiss = { editingBook = null },
            onCategories = { editingBook = null; nav.categories(book.id) },
            onDelete = { editingBook = null; scope.launch { container.ledger.deleteBook(book.id) } },
        ) { changed ->
            editingBook = null
            scope.launch { container.ledger.saveBook(changed) }
        }
    }
    if (addingBook) {
        NameDialog(stringResource(R.string.ledger_add_book), "", onDismiss = { addingBook = false }) { name ->
            addingBook = false
            scope.launch { container.ledger.addBook(name, ledger.book?.currency ?: "CNY", chinese) }
        }
    }
    restoring?.let { found ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.ledger_restore)) },
            text = { Text(stringResource(R.string.ledger_restore_note, found.txns.size, found.books.size)) },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = {
                    restoring = null
                    busy = true
                    scope.launch {
                        container.ledger.putAll(found.books, found.accounts, found.categories, found.txns, replace = true)
                        busy = false
                        show(context.getString(R.string.ledger_restore_done))
                    }
                }) { Text(stringResource(R.string.ledger_replace)) }
            },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { restoring = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A book's name and currency to change, its categories to manage, archiving and deleting it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookDialog(book: Book, canDelete: Boolean, onDismiss: () -> Unit, onCategories: () -> Unit, onDelete: () -> Unit, onSave: (Book) -> Unit) {
    var name by remember { mutableStateOf(book.name) }
    var currency by remember { mutableStateOf(book.currency) }
    var archived by remember { mutableStateOf(book.archived) }
    var confirming by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ledger_book)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ledger_book_name)) }, singleLine = true)
                Text(stringResource(R.string.ledger_book_currency), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    (COMMON_CURRENCIES.take(8) + book.currency).distinct().forEach { code ->
                        FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(code) })
                    }
                }
                FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text(stringResource(R.string.ledger_archive)) })
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onCategories, modifier = Modifier.testTag("book_categories")) {
                    Icon(Icons.Rounded.Category, contentDescription = null)
                    Text(stringResource(R.string.ledger_categories), modifier = Modifier.padding(start = Space.s))
                }
                if (canDelete) {
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirming = true }) { Text(stringResource(R.string.ledger_delete_book), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { onSave(book.copy(name = name.trim(), currency = currency, archived = archived)) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.ledger_delete_book)) },
            text = { Text(stringResource(R.string.ledger_delete_book_note, book.name)) },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirming = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** An iCost import under way: its file picker, and the dialog that says what it found before it is written. */
class ICostImporter internal constructor(val pick: () -> Unit, val read: (Uri) -> Unit, val Dialog: @Composable () -> Unit)

private class Found(val table: List<List<String>>, val merged: ICostImport.Result, val alone: ICostImport.Result)

@Composable
fun rememberICostImport(container: AppContainer): ICostImporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    var found by remember { mutableStateOf<Found?>(null) }
    var reading by remember { mutableStateOf(false) }
    val read: (Uri) -> Unit = { uri ->
        scope.launch {
            reading = true
            try {
                // The ledger read first, so what is already in it is told apart from what is new.
                container.ledger.loaded.first { it }
                found = readExport(context, uri, container.ledger.ledger.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Ledger", "iCost import failed", e)
                show(context.getString(R.string.ledger_error_import))
            } finally {
                reading = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) read(uri) }
    val dialog: @Composable () -> Unit = {
        found?.let { result ->
            ImportDialog(
                result,
                hasData = container.ledger.ledger.value.txns.isNotEmpty(),
                onDismiss = { found = null },
            ) { replace ->
                found = null
                scope.launch {
                    val chosen = if (replace) result.alone else result.merged
                    container.ledger.putAll(chosen.books, chosen.accounts, chosen.categories, chosen.txns, replace = replace)
                    // The book with the most bills is the one shown.
                    chosen.txns.groupingBy { it.book }.eachCount().maxByOrNull { it.value }?.key?.let { container.ledger.setCurrentBook(it) }
                    val linked = autoLink(container.ledger.ledger.value, container.repository.moments.value, container.repository.trips.value)
                    container.ledger.saveTxns(linked)
                    show(context.getString(R.string.ledger_import_done, chosen.txns.size, linked.size))
                }
            }
        }
        if (reading) {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text(stringResource(R.string.ledger_importing)) })
        }
    }
    return remember(picker) {
        ICostImporter(
            pick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip")) },
            read = read,
            Dialog = dialog,
        )
    }
}

private suspend fun readExport(context: Context, uri: Uri, ledger: Ledger): Found = withContext(Dispatchers.Default) {
    val table = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use(Xlsx::read) }
    val zone = ZoneId.systemDefault()
    Found(
        table,
        ICostImport.read(table, zone, ledger.rates, ledger) { Ulid.next() },
        ICostImport.read(table, zone, ledger.rates) { Ulid.next() },
    )
}

/** What an import found, and whether it goes beside the ledger's bills or in their place. */
@Composable
private fun ImportDialog(found: Found, hasData: Boolean, onDismiss: () -> Unit, onImport: (replace: Boolean) -> Unit) {
    var replace by rememberSaveable { mutableStateOf(false) }
    val result = if (replace || !hasData) found.alone else found.merged
    val report = result.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ledger_import_icost)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.ledger_import_summary, report.rows, report.txns, report.books, report.accounts))
                Text(stringResource(R.string.ledger_import_matched, report.refundsMatched + report.refundsLoose, report.refundsMatched, report.reimbursementsMatched + report.reimbursementsLoose, report.reimbursementsMatched))
                if (report.folded > 0) Text(stringResource(R.string.ledger_import_folded, report.folded))
                if (report.converted > 0) Text(stringResource(R.string.ledger_import_converted, report.converted))
                if (report.duplicates > 0) Text(stringResource(R.string.ledger_import_duplicates, report.duplicates))
                if (report.skipped.isNotEmpty()) {
                    Text(stringResource(R.string.ledger_import_skipped, report.skipped.size, report.skipped.take(5).joinToString { "#${it.first} ${it.second}" }), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.ledger_import_accounts_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasData) {
                    listOf(false to R.string.ledger_merge, true to R.string.ledger_replace).forEach { (value, label) ->
                        Row(
                            Modifier.selectable(replace == value, role = Role.RadioButton) { replace = value }.padding(vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = replace == value, onClick = null)
                            Text(stringResource(label), modifier = Modifier.padding(start = Space.s))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { onImport(replace) }, modifier = Modifier.testTag("confirm_import")) { Text(stringResource(R.string.ledger_import)) } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
