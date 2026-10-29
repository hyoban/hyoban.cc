package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.SegmentedButton
import cc.hyoban.moments.ui.design.SingleChoiceSegmentedButtonRow
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.unit.Dp
import cc.hyoban.moments.ui.components.SubPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.DropdownMenuItem
import cc.hyoban.moments.ui.design.FilledTonalButton
import cc.hyoban.moments.ui.design.FloatingActionButton
import cc.hyoban.moments.ui.design.HorizontalDivider
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.netAssetsByMonth
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.assetsOf
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.railPadding
import cc.hyoban.moments.ui.components.TitleMenu
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.launch

private enum class LedgerTab(val label: Int, val tag: String) {
    BILLS(R.string.ledger_tab_bills, "ledger_bills"),
    STATS(R.string.ledger_tab_stats, "ledger_stats"),
    ASSETS(R.string.ledger_tab_assets, "ledger_assets"),
}

/**
 * The ledger, opened from 我: the book shown, its bills by month and day, its sums by category,
 * and the accounts with what they hold, as tabs under the page's bar, [startTab] chosen first.
 * Bills are written from the timeline's 记录, where they show too.
 */
@Composable
fun LedgerScreen(container: AppContainer, nav: LedgerNav, onImport: () -> Unit, startTab: Int = 0) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val loaded by container.ledger.loaded.collectAsStateWithLifecycle()
    val book = ledger.book
    var tab by rememberSaveable { mutableIntStateOf(startTab.coerceIn(0, LedgerTab.entries.lastIndex)) }

    // The chosen book's bills, or every book's, summed in the chosen book's currency.
    val shown = ledger.shownBooks
    val currency = book?.currency ?: "CNY"
    val bills = remember(ledger) { ledger.txns.filter { it.book in shown } }
    val month = remember(ledger) {
        val today = java.time.LocalDate.now().withDayOfMonth(1)
        book?.let { summarize(ledger.entries.filter { it.txn.book in shown }, ledger.rates, currency, today, today.plusMonths(1)) }
    }
    val subtitle = if (month != null) {
        stringResource(R.string.ledger_this_month, money(month.expense, currency), money(month.income, currency))
    } else {
        null
    }

    SubPage(
        when {
            book == null -> stringResource(R.string.tab_ledger)
            ledger.allBooks -> stringResource(R.string.ledger_all_books)
            else -> book.name
        },
        onBack = nav.back,
        subtitle = subtitle,
        // Switched from its name, when there are books to switch between, so the bar keeps
        // to its one action.
        titleMenu = if (ledger.books.count { !it.archived } >= 2) {
            TitleMenu(stringResource(R.string.ledger_switch_book)) { close -> BookMenuItems(container, ledger, nav, close) }
        } else {
            null
        },
        // The month's amounts hidden or shown beside them.
        subtitleAction = { HideAmountsButton() },
        actions = {
            Tooltip(stringResource(R.string.action_search), below = true) {
                IconButton(onClick = { nav.list(LedgerFilter(book = book?.id.takeIf { !ledger.allBooks }, search = true)) }, modifier = Modifier.testTag("ledger_search")) {
                    Icon(AppIcons.Search, contentDescription = stringResource(R.string.action_search))
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Clear of the tabs down the side on a phone on its side, as the lists under it are.
            // Its bills, sums and accounts, one of three in a segmented control under the bar,
            // clear of the tabs down the side on a phone on its side as the lists under it are.
            SingleChoiceSegmentedButtonRow(Modifier.railPadding().fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
                LedgerTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(selected = tab == index, onClick = { tab = index }, modifier = Modifier.testTag(entry.tag)) {
                        Text(stringResource(entry.label))
                    }
                }
            }
            when {
                !loaded -> Unit
                LedgerTab.entries[tab] == LedgerTab.ASSETS -> AssetsView(ledger, currency, nav)
                book == null || bills.isEmpty() -> EmptyLedger(onImport = onImport, onNew = { nav.entry(LedgerEntryArgs()) })
                LedgerTab.entries[tab] == LedgerTab.BILLS -> BillsView(ledger, bills, currency, nav)
                else -> StatsView(ledger, shown, currency, nav, byName = ledger.allBooks)
            }
        }
    }
}

/** The books to switch between, from the title, and the way to manage them. */
@Composable
private fun ColumnScope.BookMenuItems(container: AppContainer, ledger: Ledger, nav: LedgerNav, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ledger_all_books)) },
        trailingIcon = if (ledger.allBooks) { { Icon(AppIcons.Check, contentDescription = null) } } else null,
        onClick = {
            close()
            scope.launch { container.ledger.showAllBooks() }
        },
        modifier = Modifier.testTag("all_books"),
    )
    HorizontalDivider()
    ledger.books.filter { !it.archived }.forEach { book ->
        DropdownMenuItem(
            text = { Text(book.name) },
            trailingIcon = if (!ledger.allBooks && book.id == ledger.book?.id) { { Icon(AppIcons.Check, contentDescription = null) } } else null,
            onClick = {
                close()
                scope.launch { container.ledger.setCurrentBook(book.id) }
            },
        )
    }
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.ledger_manage_books)) }, onClick = { close(); nav.settings() })
}

/** Hides the ledger's amounts, or shows them again: an eye beside the month's sums. */
@Composable
private fun HideAmountsButton() {
    val settings = LocalSettings.current
    val label = stringResource(if (settings.ledgerHidden) R.string.ledger_show_amounts else R.string.ledger_hide_amounts)
    Tooltip(label, below = true) {
        IconButton(onClick = { settings.changeLedgerHidden(!settings.ledgerHidden) }, modifier = Modifier.testTag("ledger_hide")) {
            Icon(
                if (settings.ledgerHidden) AppIcons.EyeOff else AppIcons.Eye,
                contentDescription = label,
                tint = AppTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A ledger with no bills yet: where they come from. */
@Composable
private fun EmptyLedger(onImport: () -> Unit, onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Text(stringResource(R.string.ledger_empty_title), style = AppTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.ledger_empty_body),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onImport, modifier = Modifier.testTag("ledger_import_empty")) {
            Icon(AppIcons.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Space.s))
            Text(stringResource(R.string.ledger_import_icost))
        }
    }
}

/** A row of a long list of bills: a month's header, a day's, or a bill. */
private sealed interface BillLine {
    val key: String

    data class Month(val month: String, val expense: Long, val income: Long) : BillLine {
        override val key get() = "m$month"
    }

    data class Day(val date: String, val expense: Long, val income: Long) : BillLine {
        override val key get() = "d$date"
    }

    data class Bill(val txn: Txn) : BillLine {
        override val key get() = txn.id
    }
}

/** [bills] newest first, under their months and days, each with what it spent and earned in [currency]. */
private fun linesOf(ledger: Ledger, bills: List<Txn>, currency: String): List<BillLine> {
    val ids = bills.map { it.id }.toSet()
    val sums = summarize(ledger.entries.filter { it.txn.id in ids }, ledger.rates, currency, null, null)
    val out = mutableListOf<BillLine>()
    var month: String? = null
    var day: String? = null
    for (txn in bills) {
        val key = txn.dateKey
        if (key.take(7) != month) {
            month = key.take(7)
            val days = { map: Map<java.time.LocalDate, Long> -> map.filterKeys { it.toString().startsWith(month!!) }.values.sum() }
            out += BillLine.Month(month!!, days(sums.expenseByDay), days(sums.incomeByDay))
        }
        if (key != day) {
            day = key
            val date = txn.date
            out += BillLine.Day(key, sums.expenseByDay[date] ?: 0, sums.incomeByDay[date] ?: 0)
        }
        out += BillLine.Bill(txn)
    }
    return out
}

/** Bills newest first, under months and days with their sums. */
@Composable
fun BillList(
    ledger: Ledger,
    bills: List<Txn>,
    currency: String,
    nav: LedgerNav,
    state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
) {
    val lines = remember(ledger, bills, currency) { linesOf(ledger, bills, currency) }
    // Bills of more than one book say which each is in.
    val showBook = remember(bills) { bills.distinctBy { it.book }.size > 1 }
    PageWidth(maxColumns = 1) { columns ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = columns.margin, end = columns.margin, bottom = LocalBottomClearance.current + LocalFabClearance.current + Space.xl),
        ) {
            if (header != null) item(key = "header") { header() }
            items(lines, key = { it.key }, contentType = { it::class }) { line ->
                when (line) {
                    is BillLine.Month -> MonthTotals(line.month, line.expense, line.income, currency)
                    is BillLine.Day -> DayTotals(line.date, line.expense, line.income, currency)
                    is BillLine.Bill -> TxnRow(line.txn, ledger, onClick = { nav.bill(line.txn.id) }, modifier = Modifier.padding(horizontal = Space.xs), showBook = showBook)
                }
            }
        }
    }
}

@Composable
private fun BillsView(ledger: Ledger, bills: List<Txn>, currency: String, nav: LedgerNav) {
    val state = rememberLazyListState()
    BillList(ledger, bills, currency, nav, state)
}

/** A month's start in the bills: the month large, its year, and what it spent and earned. */
@Composable
private fun MonthTotals(month: String, expense: Long, income: Long, currency: String) {
    val (name, year) = formatMonth("$month-01")
    Column(Modifier.fillMaxWidth().padding(start = Space.l + Space.xs, end = Space.l + Space.xs, top = Space.xl).semantics(mergeDescendants = true) { heading() }) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(name, style = AppTheme.typography.headlineSmall)
            Text(year, style = AppTheme.typography.titleMedium, color = AppTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.xxs))
        }
        Text(
            listOf(
                stringResource(R.string.ledger_expense_short, money(expense, currency)),
                stringResource(R.string.ledger_income_short, money(income, currency)),
                stringResource(R.string.ledger_balance_short, money(income - expense, currency)),
            ).joinToString(" "),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The accounts: the net assets over what is held and what is owed, in [currency], then the
 * accounts by kind as segmented lists, each with its balance in its own currency.
 */
@Composable
private fun AssetsView(ledger: Ledger, currency: String, nav: LedgerNav) {
    val assets = remember(ledger, currency) { assetsOf(ledger, currency) }
    PageWidth(maxColumns = 1) { columns ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = columns.margin, end = columns.margin, bottom = LocalBottomClearance.current + LocalFabClearance.current + Space.xl),
        ) {
            Column(Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.xl).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.ledger_net_assets), style = AppTheme.typography.labelLarge, color = AppTheme.colorScheme.onSurfaceVariant)
                Text(money(assets.net, currency), style = AppTheme.typography.displaySmall, modifier = Modifier.testTag("net_assets"))
                Text(
                    stringResource(R.string.ledger_assets_line, money(assets.held, currency), money(assets.owed, currency)),
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colorScheme.onSurfaceVariant,
                )
            }
            NetAssetsTrend(ledger, currency)
            val open = ledger.accounts.filter { !it.archived }
            AccountKind.entries.forEach { kind ->
                val accounts = open.filter { it.kind == kind }
                if (accounts.isEmpty()) return@forEach
                SegmentedGroup(
                    accountKindLabel(kind),
                    null,
                    accounts.map { account ->
                        { shapes ->
                            val balance = ledger.balances[account.id] ?: 0
                            SegmentedListItem(
                                onClick = { nav.account(account.id) },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(accountKindIcon(account.kind), contentDescription = null) },
                                supportingContent = if (!account.counted || account.note.isNotBlank()) {
                                    { Text(listOfNotNull(stringResource(R.string.ledger_not_counted).takeIf { !account.counted }, account.note.takeIf { it.isNotBlank() }).joinToString(" "), maxLines = 2) }
                                } else null,
                                trailingContent = { Text(money(balance, account.currency), style = AppTheme.typography.titleSmall) },
                                modifier = Modifier.testTag("account_${account.id}"),
                            ) { Text(account.name, maxLines = 1) }
                        }
                    },
                )
            }
            val archived = ledger.accounts.filter { it.archived }
            if (archived.isNotEmpty()) {
                SegmentedGroup(
                    stringResource(R.string.ledger_archived),
                    null,
                    archived.map { account ->
                        { shapes ->
                            SegmentedListItem(
                                onClick = { nav.account(account.id) },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(accountKindIcon(account.kind), contentDescription = null) },
                                trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                            ) { Text(account.name, maxLines = 1) }
                        }
                    },
                )
            }
            Spacer(Modifier.height(Space.l))
            OutlinedButton(onClick = { nav.editAccount(null) }, modifier = Modifier.padding(horizontal = Space.gutter).fillMaxWidth().testTag("add_account")) {
                Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Space.s))
                Text(stringResource(R.string.ledger_add_account))
            }
        }
    }
}

/** The net assets at the end of each of the last twelve months, a tap reading one out. */
@Composable
private fun NetAssetsTrend(ledger: Ledger, currency: String) {
    val months = remember(ledger, currency) { netAssetsByMonth(ledger, currency, 12) }
    if (ledger.txns.isEmpty()) return
    var picked by remember(months) { mutableStateOf<Int?>(null) }
    val name = { i: Int -> formatMonth(months[i].first.atDay(1).toString()).first }
    val change = months.last().second - months.first().second
    val readout = picked?.let { stringResource(R.string.ledger_month_end, name(it), money(months[it].second, currency)) }
        ?: stringResource(
            R.string.ledger_net_assets_change,
            // A year ago, so with its year.
            formatMonth(months[0].first.atDay(1).toString()).let { (month, year) -> if (month.endsWith("月")) "${year}年$month" else "$month $year" },
            (if (change >= 0) "+" else "") + money(change, currency),
        )
    Spacer(Modifier.height(Space.l))
    ChartCard(stringResource(R.string.ledger_chart_net_assets), readout, modifier = Modifier.testTag("chart_net_assets")) {
        LineChart(
            listOf(months.map { it.second }),
            listOf(AppTheme.colorScheme.primary),
            months.size,
            listOf(name(0), name(months.lastIndex)),
            picked,
            { picked = it },
            readout,
            area = true,
            zeroBased = false,
        )
    }
}
