package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.placeOfTxn
import cc.hyoban.moments.ledger.spendingOf
import cc.hyoban.moments.ledger.tripOfTxn
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.Space

/** The bills [filter] picks, and those matching [query] in their note, category or accounts. */
fun filterBills(ledger: Ledger, filter: LedgerFilter, query: String, moments: Map<String, MomentRecord>, trips: List<TripRecord>): List<Txn> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val categories = filter.category?.let { id -> setOf(id) + ledger.categories.filter { it.parent == id }.map { it.id } }
        ?: filter.categoryName?.let { name -> ledger.categories.filter { ledger.topCategoryName(it.id) == name }.map { it.id }.toSet() }
    return ledger.txns.filter { txn ->
        (filter.book == null || txn.book == filter.book) &&
            (filter.account == null || txn.payers.any { it.account == filter.account } || txn.toAccount == filter.account) &&
            (categories == null || txn.category in categories) &&
            (filter.trip == null || (txn.kind != TxnKind.TRANSFER && tripOfTxn(txn, moments, trips) == filter.trip)) &&
            (filter.place == null || (txn.kind != TxnKind.TRANSFER && placeOfTxn(txn, moments) == filter.place)) &&
            (filter.day == null || txn.dateKey == filter.day) &&
            (filter.period == null || txn.dateKey.startsWith(filter.period)) &&
            (words.isEmpty() || words.all { word ->
                txn.note.lowercase().contains(word) ||
                    ledger.categoryPath(txn.category)?.lowercase()?.contains(word) == true ||
                    txn.payers.any { ledger.accountsById[it.account]?.name?.lowercase()?.contains(word) == true } ||
                    ledger.accountsById[txn.toAccount]?.name?.lowercase()?.contains(word) == true ||
                    (parseAmount(word)?.let { it == txn.amount } ?: false)
            })
    }
}

/**
 * Bills picked by [filter]: a trip's, a place's, a day's, a category's, or those found
 * by a search; what they came to over them, and the bills by day.
 */
@Composable
fun LedgerListScreen(container: AppContainer, filter: LedgerFilter, nav: LedgerNav) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val byUid = remember(moments) { moments.associateBy { it.uid } }
    val bills = remember(ledger, filter, query, byUid, trips) { filterBills(ledger, filter, query, byUid, trips) }
    val currency = filter.book?.let { id -> ledger.books.firstOrNull { it.id == id }?.currency } ?: ledger.book?.currency ?: "CNY"
    val sums = remember(ledger, bills, currency) { spendingOf(ledger, bills, currency) }
    val title = filter.title
        ?: filter.trip?.let { id -> trips.firstOrNull { it.id == id }?.title }
        ?: filter.place?.let { places[it]?.name }
        ?: filter.day?.let { formatDay(it) }
        ?: stringResource(R.string.action_search)

    SubPage(title, nav.back, subtitle = pluralStringResource(R.plurals.ledger_count_bills, bills.size, bills.size)) {
        BillList(ledger, bills, currency, nav, header = {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                if (filter.search) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        placeholder = { Text(stringResource(R.string.ledger_search_hint)) },
                        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("ledger_query"),
                    )
                }
                Text(
                    listOf(
                        stringResource(R.string.ledger_expense_short, money(sums.expense, currency)),
                        stringResource(R.string.ledger_income_short, money(sums.income, currency)),
                    ).joinToString(" "),
                    style = AppTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = Space.l, top = Space.s),
                )
            }
        })
    }
}
