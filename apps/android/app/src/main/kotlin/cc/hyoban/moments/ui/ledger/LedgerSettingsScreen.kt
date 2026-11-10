package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.ListItemShapes
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.RadioButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.heading
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.COMMON_CURRENCIES
import cc.hyoban.moments.ledger.ICostBackup
import cc.hyoban.moments.ledger.ICostImport
import cc.hyoban.moments.ledger.ICostRow
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
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记账, a page under 我, as segmented lists: its books, each opening a sheet with its name,
 * currency and categories; the exchange rates, in a sheet too; 自动记账; and its data, brought in
 * from iCost, kept in a backup of one's own, written out as CSV, and linked to the Moments and
 * trips. Nothing here opens a page further down. [startImport] opens the import's file picker at once.
 */
@Composable
fun LedgerSettingsScreen(container: AppContainer, nav: LedgerNav, startImport: Boolean = false) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var addingBook by remember { mutableStateOf(false) }
    var rates by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<LedgerBackup?>(null) }
    val importer = rememberICostImport(container)

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(LedgerBackup.encode(LedgerBackup.of(container.ledger.ledger.value))) }
                }
            }.onSuccess { show(textOf(Res.string.ledger_backup_done)) }.onFailure { show(textOf(Res.string.ledger_error_file)) }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { LedgerBackup.decode(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) } }
                .onSuccess { restoring = it }
                .onFailure { show(textOf(Res.string.ledger_error_backup)) }
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { writeCsv(container.ledger.ledger.value, it) } }
            }.onSuccess { show(textOf(Res.string.ledger_csv_done)) }.onFailure { show(textOf(Res.string.ledger_error_file)) }
        }
    }
    LaunchedEffect(startImport) { if (startImport) importer.pick() }
    // An iCost export shared into the app, read as soon as this page is up.
    val shared by container.sharedExport.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) container.takeSharedExport()?.let(importer.read) }

    SubPage(stringResource(Res.string.ledger_settings_title), nav.back) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl)
                    .semantics { testTagsAsResourceId = true },
            ) {
                SegmentedGroup(
                    stringResource(Res.string.ledger_books),
                    null,
                    ledger.books.map<Book, Segment> { book ->
                        { shapes ->
                            val count = ledger.txns.count { it.book == book.id }
                            SegmentedListItem(
                                onClick = { editingBook = book },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.Book, contentDescription = null) },
                                supportingContent = {
                                    Text(listOfNotNull(book.currency, pluralStringResource(Res.plurals.ledger_count_bills, count, count), stringResource(Res.string.ledger_archived).takeIf { book.archived }).joinToString(" "))
                                },
                                modifier = Modifier.testTag("book_${book.name}"),
                            ) { Text(book.name) }
                        }
                    } + listOf<Segment> { shapes ->
                        SegmentedListItem(
                            onClick = { addingBook = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(AppIcons.Plus, contentDescription = null) },
                            modifier = Modifier.testTag("add_book"),
                        ) { Text(stringResource(Res.string.ledger_add_book)) }
                    },
                )
                SegmentedGroup(
                    stringResource(Res.string.ledger_rates),
                    null,
                    listOf { shapes ->
                        val latest = ledger.rates.currencies.filter { it != "CNY" }.mapNotNull { ledger.rates.latest(it)?.first }.maxOrNull()
                        SegmentedListItem(
                            onClick = { rates = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(AppIcons.Exchange, contentDescription = null) },
                            supportingContent = latest?.let { { Text(stringResource(Res.string.ledger_rates_until, it.toString())) } },
                            modifier = Modifier.testTag("ledger_rates"),
                        ) { Text(stringResource(Res.string.ledger_rates)) }
                    },
                )
                AutoBillGroup()
                SegmentedGroup(
                    stringResource(Res.string.ledger_data),
                    stringResource(Res.string.ledger_data_note),
                    listOf(
                        { shapes ->
                            SegmentedListItem(
                                onClick = {
                                    scope.launch {
                                        val changed = autoLink(container.ledger.ledger.value, container.repository.moments.value, container.repository.trips.value)
                                        container.ledger.saveTxns(changed)
                                        show(pluralTextOf(Res.plurals.ledger_linked, changed.size, changed.size))
                                    }
                                },
                                shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.Link, contentDescription = null) },
                                supportingContent = { Text(stringResource(Res.string.ledger_link_all_note)) },
                                modifier = Modifier.testTag("link_all"),
                            ) { Text(stringResource(Res.string.ledger_link_all)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { backup.launch("ledger-${LocalDate.now()}.json") }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.CloudUpload, contentDescription = null) },
                            ) { Text(stringResource(Res.string.ledger_backup)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
                            ) { Text(stringResource(Res.string.ledger_restore)) }
                        },
                        { shapes ->
                            SegmentedListItem(
                                onClick = { csv.launch("ledger-${LocalDate.now()}.csv") }, shapes = shapes, colors = segmentColors(),
                                leadingContent = { Icon(AppIcons.Table, contentDescription = null) },
                            ) { Text(stringResource(Res.string.ledger_export_csv)) }
                        },
                    ),
                )
            }
        }
    }

    importer.Dialog()
    editingBook?.let { book ->
        BookSheet(
            container,
            book,
            canDelete = ledger.books.size > 1,
            onDismiss = { editingBook = null },
            onDelete = { editingBook = null; scope.launch { container.ledger.deleteBook(book.id) } },
        ) { changed ->
            editingBook = null
            scope.launch { container.ledger.saveBook(changed) }
        }
    }
    if (rates) RatesSheet(container) { rates = false }
    if (addingBook) {
        NameDialog(stringResource(Res.string.ledger_add_book), "", onDismiss = { addingBook = false }) { name ->
            addingBook = false
            scope.launch { container.ledger.addBook(name, ledger.book?.currency ?: "CNY", chinese) }
        }
    }
    restoring?.let { found ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(Res.string.ledger_restore)) },
            text = { Text(stringResource(Res.string.ledger_restore_note, found.txns.size, found.books.size)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    busy = true
                    scope.launch {
                        container.ledger.putAll(found.books, found.accounts, found.categories, found.txns, replace = true)
                        busy = false
                        show(textOf(Res.string.ledger_restore_done))
                    }
                }) { Text(stringResource(Res.string.ledger_replace)) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/**
 * A book in a sheet over the page: its name and currency to change, its categories to manage
 * right there, archiving and deleting it, the name, currency and archiving kept on 保存.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookSheet(container: AppContainer, book: Book, canDelete: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Book) -> Unit) {
    var name by remember { mutableStateOf(book.name) }
    var currency by remember { mutableStateOf(book.currency) }
    var archived by remember { mutableStateOf(book.archived) }
    var confirming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                Text(book.name, style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(stringResource(Res.string.ledger_book_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.l).testTag("book_name"),
                )
                SheetCaption(stringResource(Res.string.ledger_book_currency))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    (COMMON_CURRENCIES.take(8) + book.currency).distinct().forEach { code ->
                        FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(code) })
                    }
                }
                SheetCaption(stringResource(Res.string.ledger_categories))
                BookCategories(container, book.id)
            }
            SegmentedGroup(
                null,
                null,
                listOfNotNull<Segment>(
                    { shapes ->
                        SwitchRow(archived, AppIcons.Archive, stringResource(Res.string.ledger_archive), "book_archive", shapes,
                            supporting = stringResource(Res.string.ledger_archive_note)) { archived = !archived }
                    },
                    if (canDelete) { shapes ->
                        SegmentedListItem(
                            onClick = { confirming = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(AppIcons.Trash, contentDescription = null, tint = tokens.danger) },
                            modifier = Modifier.testTag("book_delete"),
                        ) { Text(stringResource(Res.string.ledger_delete_book), color = tokens.danger) }
                    } else null,
                ),
            )
            Button(
                onClick = { onSave(book.copy(name = name.trim(), currency = currency, archived = archived)) },
                enabled = name.isNotBlank(),
                modifier = Modifier.align(Alignment.End).padding(start = Space.gutter, end = Space.gutter, top = Space.l).testTag("book_save"),
            ) { Text(stringResource(Res.string.action_save)) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.ledger_delete_book)) },
            text = { Text(stringResource(Res.string.ledger_delete_book_note, book.name)) },
            confirmButton = { TextButton(onClick = { confirming = false; onDelete() }) { Text(stringResource(Res.string.action_delete), color = tokens.danger) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** What heads a part of a sheet: the second grey, clear of what is over it. */
@Composable
private fun SheetCaption(title: String) {
    Text(
        title,
        style = AppTheme.typography.caption,
        color = tokens.tx2,
        modifier = Modifier.padding(top = Space.xl, bottom = Space.s).semantics { heading() },
    )
}

/** An iCost import under way: its file picker, and the dialog that says what it found before it is written. */
class ICostImporter internal constructor(val pick: () -> Unit, val read: (Uri) -> Unit, val Dialog: @Composable () -> Unit)

private class Found(val merged: ICostImport.Result, val alone: ICostImport.Result)

@Composable
fun rememberICostImport(container: AppContainer): ICostImporter {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    var found by remember { mutableStateOf<Found?>(null) }
    var reading by remember { mutableStateOf(false) }
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
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
                show(textOf(Res.string.ledger_error_import))
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
                hasData = ledger.txns.isNotEmpty(),
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
                    show(pluralTextOf(Res.plurals.ledger_import_done, chosen.txns.size, chosen.txns.size, linked.size))
                }
            }
        }
        if (reading) {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text(stringResource(Res.string.ledger_importing)) })
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

/** Reads iCost's backup or its bill export, whichever [uri] is, beside [ledger] and on its own. */
private suspend fun readExport(context: Context, uri: Uri, ledger: Ledger): Found = withContext(Dispatchers.IO) {
    val zone = ZoneId.systemDefault()
    val dir = File(context.cacheDir, "icost").apply { deleteRecursively(); mkdirs() }
    try {
        val file = File(dir, "export")
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        if (ICostBackup.isBackup(file)) {
            val database = File(dir, "database.db")
            ICostBackup.unpack(file, database)
            val tables = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> ICostBackup.tables(db::rows) }
            withContext(Dispatchers.Default) {
                Found(
                    ICostBackup.read(tables, zone, ledger.rates, ledger) { Ulid.next() },
                    ICostBackup.read(tables, zone, ledger.rates) { Ulid.next() },
                )
            }
        } else {
            val table = file.inputStream().use(Xlsx::read)
            withContext(Dispatchers.Default) {
                Found(
                    ICostImport.read(table, zone, ledger.rates, ledger) { Ulid.next() },
                    ICostImport.read(table, zone, ledger.rates) { Ulid.next() },
                )
            }
        }
    } finally {
        dir.deleteRecursively()
    }
}

/** The rows [sql] gives, each column as text. */
private fun SQLiteDatabase.rows(sql: String): List<ICostRow> = rawQuery(sql, null).use { cursor ->
    buildList {
        while (cursor.moveToNext()) {
            add((0 until cursor.columnCount).associate { cursor.getColumnName(it) to if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }
}

/** What an import found, and whether it goes beside the ledger's bills or in their place. */
@Composable
private fun ImportDialog(found: Found, hasData: Boolean, onDismiss: () -> Unit, onImport: (replace: Boolean) -> Unit) {
    var replace by rememberSaveable { mutableStateOf(false) }
    val result = if (replace || !hasData) found.alone else found.merged
    val report = result.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ledger_import_icost)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(Res.string.ledger_import_summary, report.rows, report.txns, report.books, report.accounts))
                Text(stringResource(Res.string.ledger_import_matched, report.refundsMatched + report.refundsLoose, report.refundsMatched, report.reimbursementsMatched + report.reimbursementsLoose, report.reimbursementsMatched))
                if (report.folded > 0) Text(pluralStringResource(Res.plurals.ledger_import_folded, report.folded, report.folded))
                if (report.calibrated > 0) Text(pluralStringResource(Res.plurals.ledger_import_calibrated, report.calibrated, report.calibrated))
                if (report.adjusted > 0) Text(pluralStringResource(Res.plurals.ledger_import_adjusted, report.adjusted, report.adjusted))
                if (report.converted > 0) Text(pluralStringResource(Res.plurals.ledger_import_converted, report.converted, report.converted))
                if (report.duplicates > 0) Text(pluralStringResource(Res.plurals.ledger_import_duplicates, report.duplicates, report.duplicates))
                if (report.skipped.isNotEmpty()) {
                    Text(pluralStringResource(Res.plurals.ledger_import_skipped, report.skipped.size, report.skipped.size, report.skipped.take(5).joinToString { "#${it.first} ${it.second}" }), color = tokens.danger)
                }
                Text(stringResource(if (report.balances) Res.string.ledger_import_backup_note else Res.string.ledger_import_accounts_note), style = AppTheme.typography.caption, color = tokens.tx2)
                if (hasData) {
                    listOf(false to Res.string.ledger_merge, true to Res.string.ledger_replace).forEach { (value, label) ->
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
        confirmButton = { TextButton(onClick = { onImport(replace) }, modifier = Modifier.testTag("confirm_import")) { Text(stringResource(Res.string.ledger_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
