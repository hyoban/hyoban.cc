package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.DISCOUNT_CATEGORY
import cc.hyoban.moments.ledger.FEE_CATEGORY
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Summary
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import java.time.LocalDate
import kotlin.math.roundToInt

/** How long a stretch the sums are over. */
private enum class Span(val label: Int) {
    MONTH(R.string.ledger_span_month),
    YEAR(R.string.ledger_span_year),
    ALL(R.string.ledger_span_all),
}

/** The name of a summed category: the user's, or what a transfer's fee and discount are. */
@Composable
fun categoryName(ledger: Ledger, id: String?): String = when (id) {
    FEE_CATEGORY -> stringResource(R.string.ledger_fee)
    DISCOUNT_CATEGORY -> stringResource(R.string.ledger_discount)
    null -> stringResource(R.string.ledger_uncategorized)
    else -> ledger.categoriesById[id]?.name ?: id
}

/**
 * The book's sums over a month, a year or all of it, stepped through with arrows: what was
 * spent, earned and left, spending by day or month as bars, and by category with each one's
 * share, each leading to its bills.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsView(ledger: Ledger, books: Set<String>, currency: String, nav: LedgerNav, byName: Boolean = false) {
    var span by rememberSaveable { mutableIntStateOf(0) }
    var anchorDay by rememberSaveable { mutableLongStateOf(LocalDate.now().withDayOfMonth(1).toEpochDay()) }
    var income by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val mode = Span.entries[span]
    val anchor = LocalDate.ofEpochDay(anchorDay)
    val (start, end) = when (mode) {
        Span.MONTH -> anchor to anchor.plusMonths(1)
        Span.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(1).plusYears(1)
        Span.ALL -> null to null
    }
    val entries = remember(ledger, books) { ledger.entries.filter { it.txn.book in books } }
    // Across books, a category is summed with those of the same name in the others.
    val topOf: (String?) -> String? = if (byName) ledger::topCategoryName else ledger::topCategory
    val summary = remember(entries, start, end, currency, byName) { summarize(entries, ledger.rates, currency, start, end, topOf) }
    val book = books.singleOrNull()
    val period = when (mode) {
        Span.MONTH -> anchor.toString().take(7)
        Span.YEAR -> anchor.year.toString()
        Span.ALL -> null
    }
    val periodLabel = when (mode) {
        Span.MONTH -> formatMonth(anchor.toString()).let { (month, year) -> if (month.endsWith("月")) "${year}年$month" else "$month $year" }
        Span.YEAR -> anchor.year.toString()
        Span.ALL -> stringResource(R.string.ledger_span_all)
    }

    PageWidth(maxColumns = 1) { columns ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = columns.margin, end = columns.margin, bottom = LocalBottomClearance.current + LocalFabClearance.current + Space.xl),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l)) {
                Span.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = span == index,
                        onClick = { span = index },
                        shape = SegmentedButtonDefaults.itemShape(index, Span.entries.size),
                        modifier = Modifier.testTag("span_${entry.name.lowercase()}"),
                    ) { Text(stringResource(entry.label)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (mode != Span.ALL) {
                    IconButton(onClick = { anchorDay = (if (mode == Span.MONTH) anchor.minusMonths(1) else anchor.minusYears(1)).toEpochDay() }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.ledger_previous_period))
                    }
                }
                Text(periodLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = Space.s))
                if (mode != Span.ALL) {
                    IconButton(onClick = { anchorDay = (if (mode == Span.MONTH) anchor.plusMonths(1) else anchor.plusYears(1)).toEpochDay() }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.ledger_next_period))
                    }
                }
            }
            Totals(summary, currency)
            Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilterChip(selected = !income, onClick = { income = false }, label = { Text(stringResource(R.string.ledger_kind_expense)) })
                FilterChip(selected = income, onClick = { income = true }, label = { Text(stringResource(R.string.ledger_kind_income)) })
            }
            if (mode != Span.ALL) {
                Bars(summary, income, mode == Span.MONTH, start!!, end!!, currency)
            }
            val byCategory = if (income) summary.incomeByCategory else summary.expenseByCategory
            val total = byCategory.sumOf { it.second }.coerceAtLeast(1)
            if (byCategory.isNotEmpty()) {
                SegmentedGroup(
                    stringResource(if (income) R.string.ledger_income_by_category else R.string.ledger_expense_by_category),
                    if (summary.unconverted > 0) stringResource(R.string.ledger_unconverted_note) else null,
                    byCategory.map { (category, cents) ->
                        { shapes ->
                            val share = cents.toFloat() / total
                            val name = categoryName(ledger, category)
                            SegmentedListItem(
                                onClick = {
                                    if (category?.startsWith("@") != true) {
                                        nav.list(
                                            if (byName) LedgerFilter(title = name, categoryName = category, period = period)
                                            else LedgerFilter(title = name, book = book, category = category, period = period),
                                        )
                                    }
                                },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { LeadingIcon(categoryIcon(category?.let { if (it.startsWith("@")) it else ledger.categoriesById[it]?.name ?: it })) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                        Text("${(share * 1000).roundToInt() / 10.0}%")
                                        LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                                    }
                                },
                                trailingContent = { Text(formatMoney(cents, currency), style = MaterialTheme.typography.titleSmall) },
                            ) { Text(name, maxLines = 1) }
                        }
                    },
                )
            }
        }
    }
}

/** What was spent, earned and left, side by side on a card. */
@Composable
private fun Totals(summary: Summary, currency: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = LocalSurfaceLevel.current.raised.fill(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
    ) {
        Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            listOf(
                R.string.ledger_kind_expense to summary.expense,
                R.string.ledger_kind_income to summary.income,
                R.string.ledger_balance to summary.balance,
            ).forEach { (label, cents) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoney(cents, currency), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
        }
    }
}

/** Spending or earning by day of a month, or by month of a year, as bars from a common baseline. */
@Composable
private fun Bars(summary: Summary, income: Boolean, byDay: Boolean, start: LocalDate, end: LocalDate, currency: String) {
    val days = if (income) summary.incomeByDay else summary.expenseByDay
    val values: List<Long> = if (byDay) {
        generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.map { days[it] ?: 0 }.toList()
    } else {
        (1..12).map { month -> days.filterKeys { it.monthValue == month }.values.sum() }
    }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val bar = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val peak = values.indexOf(values.max())
    val description = stringResource(R.string.ledger_chart_description, formatMoney(values.max(), currency))
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
            val slot = size.width / values.size
            val width = (slot * 0.6f).coerceAtLeast(2f)
            values.forEachIndexed { index, value ->
                if (value <= 0) return@forEachIndexed
                val height = (value.toFloat() / max) * (size.height - 4f)
                drawRoundRect(
                    bar.copy(alpha = if (index == peak) 1f else 0.7f),
                    topLeft = Offset(index * slot + (slot - width) / 2, size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 3, width / 3),
                )
            }
            drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth()) {
            val labels = if (byDay) listOf("1", "${values.size / 2 + 1}", "${values.size}") else listOf("1", "6", "12")
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = when (index) {
                        0 -> androidx.compose.ui.text.style.TextAlign.Start
                        1 -> androidx.compose.ui.text.style.TextAlign.Center
                        else -> androidx.compose.ui.text.style.TextAlign.End
                    },
                )
            }
        }
    }
}
