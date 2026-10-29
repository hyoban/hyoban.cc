package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedButton
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.SingleChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Rates
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A book's categories of either kind, each first-level one with its second-level ones under it,
 * as a segmented list; tapped, one is renamed, given a second level, merged into another or,
 * unused, deleted.
 */
@Composable
fun CategoriesScreen(container: AppContainer, book: String, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var income by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    val type = if (income) CategoryType.INCOME else CategoryType.EXPENSE
    val categories = ledger.categories.filter { it.book == book && it.type == type }
    val uses = remember(ledger) { ledger.txns.groupingBy { it.category }.eachCount() }

    SubPage(stringResource(R.string.ledger_categories), nav.back, subtitle = ledger.books.firstOrNull { it.id == book }?.name) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
                    listOf(false to R.string.ledger_kind_expense, true to R.string.ledger_kind_income).forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = income == value, onClick = { income = value }) { Text(stringResource(label)) }
                    }
                }
                categories.filter { it.parent == null }.forEach { top ->
                    val children = categories.filter { it.parent == top.id }
                    SegmentedGroup(
                        null,
                        null,
                        (listOf(top) + children).map { category ->
                            { shapes ->
                                val count = uses[category.id] ?: 0
                                SegmentedListItem(
                                    onClick = { editing = category },
                                    shapes = shapes,
                                    colors = segmentColors(),
                                    leadingContent = {
                                        if (category.parent == null) LeadingIcon(categoryIcon(category.name))
                                        else Icon(AppIcons.SubArrow, contentDescription = null)
                                    },
                                    supportingContent = { Text(pluralStringResource(R.plurals.ledger_count_bills, count, count)) },
                                    modifier = Modifier.testTag("category_row_${category.name}"),
                                ) { Text(category.name) }
                            }
                        },
                    )
                }
                OutlinedButton(onClick = { adding = "" }, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l).fillMaxWidth()) {
                    Icon(AppIcons.Plus, contentDescription = null)
                    Text(stringResource(R.string.ledger_add_category), modifier = Modifier.padding(start = Space.s))
                }
            }
        }
    }

    editing?.let { category ->
        CategoryDialog(
            category,
            used = (uses[category.id] ?: 0) + categories.filter { it.parent == category.id }.sumOf { uses[it.id] ?: 0 },
            others = categories.filter { it.id != category.id && it.parent != category.id },
            onDismiss = { editing = null },
            onRename = { name -> editing = null; scope.launch { container.ledger.saveCategory(category.copy(name = name)) } },
            onAddChild = { editing = null; adding = category.id },
            onMerge = { into -> editing = null; scope.launch { container.ledger.moveCategory(category.id, into) } },
            onDelete = { editing = null; scope.launch { container.ledger.deleteCategory(category.id) } },
        )
    }
    adding?.let { parent ->
        NameDialog(stringResource(if (parent.isEmpty()) R.string.ledger_add_category else R.string.ledger_add_subcategory), "", onDismiss = { adding = null }) { name ->
            adding = null
            scope.launch { container.ledger.saveCategory(Category(Ulid.next(), book, type, name, parent.ifEmpty { null }, position = ledger.categories.size)) }
        }
    }
}

/** What can be done to a category: its name, a second level under it, merging it, deleting it. */
@Composable
private fun CategoryDialog(
    category: Category,
    used: Int,
    others: List<Category>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAddChild: () -> Unit,
    onMerge: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }
    var merging by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ledger_category_name)) }, singleLine = true)
                if (category.parent == null) TextButton(onClick = onAddChild) { Text(stringResource(R.string.ledger_add_subcategory)) }
                if (others.isNotEmpty()) TextButton(onClick = { merging = true }) { Text(stringResource(R.string.ledger_merge_category)) }
                TextButton(onClick = onDelete, enabled = used == 0) {
                    Text(
                        if (used == 0) stringResource(R.string.action_delete) else pluralStringResource(R.plurals.ledger_category_in_use, used, used),
                        color = if (used == 0) AppTheme.colorScheme.error else AppTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (merging) {
        ChoiceSheet(
            others.map { other -> other.id to (others.firstOrNull { it.id == other.parent }?.let { "${it.name} › " }.orEmpty() + other.name) },
            onDismiss = { merging = false },
        ) { into ->
            merging = false
            onMerge(into)
        }
    }
}

/** The currencies the European Central Bank sets a rate for, which Frankfurter serves. */
private val ECB = setOf(
    "AUD", "BGN", "BRL", "CAD", "CHF", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY",
    "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD", "ZAR",
)

/**
 * The exchange rates bills in other currencies are summed at: each currency's latest, a rate
 * of one's own set for one, and the European Central Bank's brought up to date from the bar,
 * the one time the ledger goes online, and only when asked.
 */
@Composable
fun RatesScreen(container: AppContainer, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val show = rememberShowMessage()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val used = remember(ledger) { (ledger.accounts.map { it.currency } + ledger.txns.map { it.currency } + ledger.books.map { it.currency }).toSet() }
    val currencies = (ledger.rates.currencies + used - "CNY").sortedWith(compareBy({ it !in used }, { it }))

    SubPage(
        stringResource(R.string.ledger_rates),
        nav.back,
        actions = {
            Tooltip(stringResource(R.string.ledger_rates_refresh), below = true) {
                IconButton(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            try {
                                val from = currencies.mapNotNull { ledger.rates.latest(it)?.first }.minOrNull()?.minusDays(7) ?: LocalDate.now().minusYears(2)
                                val fetched = Rates.fetch(from, currencies.filter { it in ECB })
                                container.ledger.putRates(fetched)
                                show(context.getString(R.string.ledger_rates_refreshed))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                show(context.getString(R.string.ledger_rates_failed))
                            } finally {
                                refreshing = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("refresh_rates"),
                ) { Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.ledger_rates_refresh)) }
            }
        },
    ) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = columns.margin, end = columns.margin, bottom = Space.xl),
            ) {
                SegmentedGroup(
                    null,
                    stringResource(R.string.ledger_rates_note),
                    currencies.map { code ->
                        { shapes ->
                            val latest = ledger.rates.latest(code)
                            SegmentedListItem(
                                onClick = { editing = code },
                                shapes = shapes,
                                colors = segmentColors(),
                                supportingContent = { Text(latest?.first?.toString() ?: stringResource(R.string.ledger_rate_missing)) },
                                trailingContent = latest?.let { { Text("%.4f".format(1 / it.second) + " CNY", style = AppTheme.typography.titleSmall) } },
                                modifier = Modifier.testTag("rate_$code"),
                            ) { Text("1 $code") }
                        }
                    },
                )
            }
        }
    }

    editing?.let { code ->
        var value by remember { mutableStateOf(ledger.rates.latest(code)?.second?.let { "%.4f".format(1 / it) }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.ledger_rate_set, code)) },
            text = {
                OutlinedTextField(
                    value, { value = it },
                    prefix = { Text("1 $code = ") },
                    suffix = { Text("CNY") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(stringResource(R.string.ledger_rate_set_note)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val yuan = value.toDoubleOrNull()?.takeIf { it > 0 } ?: return@TextButton
                    editing = null
                    scope.launch { container.ledger.putRates(mapOf(code to mapOf(LocalDate.now() to 1 / yuan))) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
