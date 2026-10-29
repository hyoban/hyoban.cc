package cc.hyoban.moments.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.DISCOUNT_CATEGORY
import cc.hyoban.moments.ledger.FEE_CATEGORY
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Summary
import cc.hyoban.moments.ledger.byMonth
import cc.hyoban.moments.ledger.byYear
import cc.hyoban.moments.ledger.daysOf
import cc.hyoban.moments.ledger.runningTotals
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ledger.weekdayAverages
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.format.formatMonthOnly
import cc.hyoban.moments.ui.format.weekdayLetters
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
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

/** A share as a percentage with one decimal at most: `12.5%`. */
private fun percent(share: Double): String = "${(share * 1000).roundToInt() / 10.0}%".replace(".0%", "%")

/** Pieces of a line, set apart by an em space. */
private fun line(vararg pieces: String?) = pieces.filterNotNull().joinToString(" ")

/**
 * The book's sums over a month, a year or all of it, stepped through with arrows: what was
 * spent, earned and left, a day's average and how it compares with the stretch before; then
 * charts of spending or earning: by day, month or year, its running total beside the last
 * stretch's, a calendar of its days, the weekdays' averages, its categories as a ring and
 * a list, and over a year or more, earning against spending and the categories by month.
 * A mark tapped reads out its value, and its bills are a button away.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsView(ledger: Ledger, books: Set<String>, currency: String, nav: LedgerNav, byName: Boolean = false) {
    var span by rememberSaveable { mutableIntStateOf(0) }
    var anchorDay by rememberSaveable { mutableLongStateOf(LocalDate.now().withDayOfMonth(1).toEpochDay()) }
    var income by rememberSaveable { mutableStateOf(false) }
    val mode = Span.entries[span]
    val anchor = LocalDate.ofEpochDay(anchorDay)
    val today = LocalDate.now()
    val (start, end) = when (mode) {
        Span.MONTH -> anchor to anchor.plusMonths(1)
        Span.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(1).plusYears(1)
        Span.ALL -> null to null
    }
    val (previousStart, previousEnd) = when (mode) {
        Span.MONTH -> anchor.minusMonths(1) to anchor
        Span.YEAR -> start!!.minusYears(1) to start
        Span.ALL -> null to null
    }
    val entries = remember(ledger, books) { ledger.entries.filter { it.txn.book in books } }
    // Across books, a category is summed with those of the same name in the others.
    val topOf: (String?) -> String? = if (byName) ledger::topCategoryName else ledger::topCategory
    val summary = remember(entries, start, end, currency, byName) { summarize(entries, ledger.rates, currency, start, end, topOf) }
    val previous = remember(entries, previousStart, currency, byName) {
        previousStart?.let { summarize(entries, ledger.rates, currency, it, previousEnd, topOf) }
    }
    val firstDay = remember(entries) { entries.minOfOrNull { it.txn.date } }
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
    // The days of the stretch so far: none for one still to come, up to today for this one.
    val rangeStart = start ?: firstDay
    val rangeEnd = (end ?: today.plusDays(1)).let { if (it > today) today.plusDays(1) else it }
    val elapsed = if (rangeStart == null || rangeStart >= rangeEnd) 0 else ChronoUnit.DAYS.between(rangeStart, rangeEnd).toInt()
    val listBills = { title: String?, day: LocalDate?, of: String? -> nav.list(LedgerFilter(title = title, book = book, day = day?.toString(), period = of)) }

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
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { anchorDay = (if (mode == Span.MONTH) anchor.minusMonths(1) else anchor.minusYears(1)).toEpochDay() }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.ledger_previous_period))
                    }
                }
                Text(periodLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = Space.s))
                if (mode != Span.ALL) {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { anchorDay = (if (mode == Span.MONTH) anchor.plusMonths(1) else anchor.plusYears(1)).toEpochDay() }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.ledger_next_period))
                    }
                }
            }
            // Against the stretch before over as many days, so a month under way meets the last one's same days.
            val compared = previous?.let { before ->
                val until = previousStart!!.plusDays(elapsed.toLong())
                before.expenseByDay.filterKeys { it < until }.values.sum()
            }
            Totals(summary, currency, average = if (elapsed > 0) summary.expense / elapsed else null, compared = compared, yearly = mode == Span.YEAR)
            if (mode != Span.ALL || firstDay != null) {
                if (mode != Span.MONTH && (summary.expense > 0 || summary.income > 0)) {
                    IncomeAgainstExpense(summary, mode == Span.YEAR, anchor.year, firstDay?.year ?: today.year, today.year, currency) { label, of -> listBills(label, null, of) }
                }
            }
            Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilterChip(selected = !income, onClick = { income = false }, label = { Text(stringResource(R.string.ledger_kind_expense)) })
                FilterChip(selected = income, onClick = { income = true }, label = { Text(stringResource(R.string.ledger_kind_income)) })
            }
            val byDay = if (income) summary.incomeByDay else summary.expenseByDay
            val byCategory = if (income) summary.incomeByCategory else summary.expenseByCategory
            if (byDay.values.none { it > 0 }) {
                Text(
                    stringResource(if (income) R.string.ledger_no_income else R.string.ledger_no_expense),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Space.xl),
                )
                return@Column
            }
            val flowKey = Triple(mode, anchorDay, income)
            Trend(mode, byDay, start, firstDay?.year ?: today.year, today.year, currency, flowKey) { label, day, of -> listBills(label, day, of) }
            if (mode != Span.ALL && start!! <= today) {
                val before = previous?.let { if (income) it.incomeByDay else it.expenseByDay }.orEmpty()
                RunningTotal(mode, byDay, before, start, end!!, previousStart!!, previousEnd!!, today, currency, flowKey)
            }
            when (mode) {
                Span.MONTH -> ChartCard(
                    stringResource(R.string.ledger_chart_calendar),
                    stringResource(if (income) R.string.ledger_days_with_income else R.string.ledger_days_with_expense, byDay.count { it.value > 0 }),
                ) { MonthHeatmap(start!!, byDay, currency) { listBills(null, it, null) } }
                Span.YEAR -> YearCalendar(anchor.year, byDay, income, currency, flowKey) { listBills(null, it, null) }
                Span.ALL -> Unit
            }
            if (rangeStart != null && elapsed > 0) Weekdays(byDay, rangeStart, end ?: rangeEnd, today, currency, flowKey)
            // The stretch's biggest categories take the hues in order, the ring, the list and the stacks alike.
            val ranked = byCategory.filter { it.second > 0 }.map { it.first }.take(SERIES_COUNT)
            val palette = seriesColors()
            val other = otherColor()
            val colorOf = { category: String? -> ranked.indexOf(category).let { if (it < 0) other else palette[it] } }
            val total = byCategory.sumOf { it.second.coerceAtLeast(0) }.coerceAtLeast(1)
            Categories(ledger, byCategory, ranked, colorOf, total, if (income) summary.income else summary.expense, income, currency, flowKey)
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
                                    Text(percent(share.toDouble()))
                                    LinearProgressIndicator(
                                        progress = { share },
                                        color = colorOf(category),
                                        trackColor = colorOf(category).copy(alpha = 0.2f),
                                        modifier = Modifier.fillMaxWidth(),
                                        drawStopIndicator = {},
                                    )
                                }
                            },
                            trailingContent = { Text(money(cents, currency), style = MaterialTheme.typography.titleSmall) },
                        ) { Text(name, maxLines = 1) }
                    }
                },
            )
            if (mode != Span.MONTH) {
                CategoriesOverTime(ledger, entries, mode == Span.YEAR, anchor.year, firstDay?.year ?: today.year, today.year, income, ranked, colorOf, currency, topOf, flowKey)
            }
        }
    }
}

/** What was spent, earned and left, side by side on a card, over a day's average and how it compares. */
@Composable
private fun Totals(summary: Summary, currency: String, average: Long?, compared: Long?, yearly: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = LocalSurfaceLevel.current.raised.fill(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s),
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                listOf(
                    R.string.ledger_kind_expense to summary.expense,
                    R.string.ledger_kind_income to summary.income,
                    R.string.ledger_balance to summary.balance,
                ).forEach { (label, cents) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // A six-figure sum shrinks to fit its third rather than losing its cents.
                        Text(
                            money(cents, currency),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize),
                        )
                    }
                }
            }
            val change = compared?.takeIf { it > 0 && summary.expense != it }?.let { before ->
                val share = percent(abs(summary.expense - before).toDouble() / before)
                stringResource(
                    when {
                        summary.expense > before && yearly -> R.string.ledger_vs_last_year_more
                        summary.expense > before -> R.string.ledger_vs_last_month_more
                        yearly -> R.string.ledger_vs_last_year_less
                        else -> R.string.ledger_vs_last_month_less
                    },
                    share,
                )
            }
            val average = average?.takeIf { summary.expense > 0 }?.let { stringResource(R.string.ledger_daily_average, money(it, currency)) }
            if (average != null || change != null) {
                Text(line(average, change), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A month of a year, or a year, as an axis or a readout names it. */
private fun slotLabel(yearly: Boolean, first: Int, index: Int): String = if (yearly) formatMonthOnly(index + 1) else "${first + index}"

/** Earning against spending by month of a year or by year, and what share of earning was left. */
@Composable
private fun IncomeAgainstExpense(summary: Summary, yearly: Boolean, year: Int, first: Int, last: Int, currency: String, onOpen: (String, String) -> Unit) {
    var picked by remember(yearly, year, summary) { mutableStateOf<Int?>(null) }
    val expense = if (yearly) byMonth(summary.expenseByDay, year) else byYear(summary.expenseByDay, first, last)
    val income = if (yearly) byMonth(summary.incomeByDay, year) else byYear(summary.incomeByDay, first, last)
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val expenseLabel = stringResource(R.string.ledger_kind_expense)
    val incomeLabel = stringResource(R.string.ledger_kind_income)
    val readout = picked?.let { i ->
        line(slotLabel(yearly, first, i), stringResource(R.string.ledger_expense_short, money(expense[i], currency)), stringResource(R.string.ledger_income_short, money(income[i], currency)))
    } ?: if (summary.income > 0) stringResource(R.string.ledger_savings_rate, percent(summary.balance.toDouble() / summary.income)) else stringResource(R.string.ledger_expense_short, money(summary.expense, currency))
    val description = stringResource(R.string.ledger_income_expense_description, money(summary.expense, currency), money(summary.income, currency))
    ChartCard(
        stringResource(R.string.ledger_chart_income_expense),
        readout,
        legend = listOf(expenseLabel to colors[0], incomeLabel to colors[1]),
        onOpen = picked?.let { i -> { onOpen(slotLabel(yearly, first, i), if (yearly) "%d-%02d".format(Locale.ROOT, year, i + 1) else "${first + i}") } },
    ) {
        BarChart(
            listOf(expense, income),
            colors,
            if (yearly) listOf(formatMonthOnly(1), formatMonthOnly(6), formatMonthOnly(12)) else listOf("$first", "$last").distinct(),
            picked,
            { picked = it },
            description,
        )
    }
}

/** Spending or earning by day of a month, by month of a year or by year, a tap reading one out. */
@Composable
private fun Trend(
    mode: Span,
    byDay: Map<LocalDate, Long>,
    start: LocalDate?,
    first: Int,
    last: Int,
    currency: String,
    key: Any,
    onOpen: (String?, LocalDate?, String?) -> Unit,
) {
    var picked by remember(key) { mutableStateOf<Int?>(null) }
    val days = if (mode == Span.MONTH) daysOf(start!!, start.plusMonths(1)) else emptyList()
    val values = when (mode) {
        Span.MONTH -> days.map { byDay[it] ?: 0 }
        Span.YEAR -> byMonth(byDay, start!!.year)
        Span.ALL -> byYear(byDay, first, last)
    }
    val label = { i: Int ->
        when (mode) {
            Span.MONTH -> formatDate(days[i].toString(), today = days[i])
            Span.YEAR -> formatMonthOnly(i + 1)
            Span.ALL -> "${first + i}"
        }
    }
    val peak = values.indices.maxBy { values[it] }
    val readout = picked?.let { line(label(it), money(values[it], currency)) }
        ?: stringResource(R.string.ledger_peak, label(peak), money(values[peak], currency))
    val axis = when (mode) {
        Span.MONTH -> listOf("1", "${values.size / 2 + 1}", "${values.size}")
        Span.YEAR -> listOf(formatMonthOnly(1), formatMonthOnly(6), formatMonthOnly(12))
        Span.ALL -> listOf("$first", "$last").distinct()
    }
    ChartCard(
        stringResource(
            when (mode) {
                Span.MONTH -> R.string.ledger_chart_by_day
                Span.YEAR -> R.string.ledger_chart_by_month
                Span.ALL -> R.string.ledger_chart_by_year
            },
        ),
        readout,
        onOpen = picked?.let { i ->
            {
                when (mode) {
                    Span.MONTH -> onOpen(null, days[i], null)
                    Span.YEAR -> onOpen(label(i), null, "%d-%02d".format(Locale.ROOT, start!!.year, i + 1))
                    Span.ALL -> onOpen(label(i), null, "${first + i}")
                }
            }
        },
        modifier = Modifier.testTag("chart_trend"),
    ) {
        BarChart(
            listOf(values),
            listOf(MaterialTheme.colorScheme.primary),
            axis,
            picked,
            { picked = it },
            stringResource(R.string.ledger_chart_description, money(values[peak], currency)),
        )
    }
}

/** The running total through the month or year, over the last one's as far as it went. */
@Composable
private fun RunningTotal(
    mode: Span,
    byDay: Map<LocalDate, Long>,
    before: Map<LocalDate, Long>,
    start: LocalDate,
    end: LocalDate,
    previousStart: LocalDate,
    previousEnd: LocalDate,
    today: LocalDate,
    currency: String,
    key: Any,
) {
    var picked by remember(key) { mutableStateOf<Int?>(null) }
    val current = runningTotals(byDay, start, end, until = today)
    val last = runningTotals(before, previousStart, previousEnd)
    val length = maxOf(ChronoUnit.DAYS.between(start, end), ChronoUnit.DAYS.between(previousStart, previousEnd)).toInt()
    val monthly = mode == Span.MONTH
    val name = { date: LocalDate -> if (monthly) formatMonth(date.toString()).first else "${date.year}" }
    val thisName = name(start)
    val lastName = name(previousStart)
    val at = picked ?: current.lastIndex
    val dayName = if (monthly) stringResource(R.string.ledger_day_of_month, at + 1) else formatDate(start.plusDays(at.toLong()).toString(), today = start)
    val readout = line(
        dayName,
        current.getOrNull(at)?.let { "$thisName ${money(it, currency)}" },
        last.getOrNull(at)?.let { "$lastName ${money(it, currency)}" },
    )
    val colors = listOf(MaterialTheme.colorScheme.primary, otherColor())
    ChartCard(
        stringResource(R.string.ledger_chart_running_total),
        readout,
        legend = listOf(thisName to colors[0], lastName to colors[1]),
    ) {
        LineChart(
            listOf(current, last),
            colors,
            length,
            if (monthly) listOf("1", "$length") else listOf(formatMonthOnly(1), formatMonthOnly(12)),
            picked,
            { picked = it },
            stringResource(R.string.ledger_running_total_description, thisName, money(current.lastOrNull() ?: 0, currency), lastName, money(last.lastOrNull() ?: 0, currency)),
        )
    }
}

/** A year's days as a grid, a tap reading one out and leading to its bills. */
@Composable
private fun YearCalendar(year: Int, byDay: Map<LocalDate, Long>, income: Boolean, currency: String, key: Any, onDay: (LocalDate) -> Unit) {
    var picked by remember(key) { mutableStateOf<LocalDate?>(null) }
    val count = byDay.count { it.key.year == year && it.value > 0 }
    val readout = picked?.let { line(formatDate(it.toString(), today = it), money(byDay[it] ?: 0, currency)) }
        ?: stringResource(if (income) R.string.ledger_days_with_income else R.string.ledger_days_with_expense, count)
    ChartCard(
        stringResource(R.string.ledger_chart_calendar),
        readout,
        onOpen = picked?.takeIf { (byDay[it] ?: 0) != 0L }?.let { day -> { onDay(day) } },
    ) {
        YearHeatmap(year, byDay, picked, { picked = it }, readout)
    }
}

/** What an average Monday through Sunday came to over the stretch. */
@Composable
private fun Weekdays(byDay: Map<LocalDate, Long>, start: LocalDate, end: LocalDate, today: LocalDate, currency: String, key: Any) {
    var picked by remember(key) { mutableStateOf<Int?>(null) }
    val values = remember(byDay, start, end, today) { weekdayAverages(byDay, start, end, today) }
    val locale = Locale.getDefault()
    val weekday = { i: Int -> java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, if (locale.language == "zh") Locale.CHINESE else Locale.ENGLISH) }
    val at = picked ?: values.indices.maxBy { values[it] }
    val readout = stringResource(R.string.ledger_weekday_average, weekday(at), money(values[at], currency))
    ChartCard(stringResource(R.string.ledger_chart_weekdays), readout) {
        BarChart(listOf(values), listOf(MaterialTheme.colorScheme.primary), weekdayLetters(), picked, { picked = it }, readout, height = 88.dp)
    }
}

/** The categories' shares as a ring, those without a colour of their own summed as 其他. */
@Composable
private fun Categories(
    ledger: Ledger,
    byCategory: List<Pair<String?, Long>>,
    ranked: List<String?>,
    colorOf: (String?) -> Color,
    total: Long,
    sum: Long,
    income: Boolean,
    currency: String,
    key: Any,
) {
    var picked by remember(key) { mutableStateOf<Int?>(null) }
    val own = byCategory.filter { it.first in ranked && it.second > 0 }
    val rest = byCategory.filter { it.first !in ranked }.sumOf { it.second.coerceAtLeast(0) }
    val otherName = stringResource(R.string.ledger_category_other)
    val names = own.map { categoryName(ledger, it.first) } + listOfNotNull(otherName.takeIf { rest > 0 })
    val slices = own.map { it.second to colorOf(it.first) } + listOfNotNull((rest to otherColor()).takeIf { rest > 0 })
    if (slices.isEmpty()) return
    val readout = picked?.let { i -> line(names[i], percent(slices[i].first.toDouble() / total)) }
        ?: stringResource(R.string.ledger_top_category, names[0], percent(slices[0].first.toDouble() / total))
    ChartCard(
        stringResource(if (income) R.string.ledger_income_by_category else R.string.ledger_expense_by_category),
        readout,
        legend = names.zip(slices.map { it.second }),
    ) {
        DonutChart(slices, picked, { picked = it }, readout) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    picked?.let { names[it] } ?: stringResource(if (income) R.string.ledger_kind_income else R.string.ledger_kind_expense),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(money(picked?.let { slices[it].first } ?: sum, currency), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

/** The categories by month of a year or by year, one on another. */
@Composable
private fun CategoriesOverTime(
    ledger: Ledger,
    entries: List<cc.hyoban.moments.ledger.Entry>,
    yearly: Boolean,
    year: Int,
    first: Int,
    last: Int,
    income: Boolean,
    ranked: List<String?>,
    colorOf: (String?) -> Color,
    currency: String,
    topOf: (String?) -> String?,
    key: Any,
) {
    var picked by remember(key) { mutableStateOf<Int?>(null) }
    val slots = remember(entries, yearly, year, first, last, currency) {
        val ranges = if (yearly) (1..12).map { LocalDate.of(year, it, 1).let { day -> day to day.plusMonths(1) } }
        else (first..last).map { LocalDate.of(it, 1, 1).let { day -> day to day.plusYears(1) } }
        ranges.map { (from, to) -> summarize(entries, ledger.rates, currency, from, to, topOf) }
    }
    val byCategory = slots.map { if (income) it.incomeByCategory else it.expenseByCategory }
    val keys = ranked + listOf<String?>("@other")
    val series = keys.map { category ->
        byCategory.map { categories ->
            if (category == "@other") categories.filter { it.first !in ranked }.sumOf { it.second.coerceAtLeast(0) }
            else categories.firstOrNull { it.first == category }?.second ?: 0
        }
    }
    val shown = series.indices.filter { s -> series[s].any { it > 0 } }
    if (shown.isEmpty()) return
    val otherName = stringResource(R.string.ledger_category_other)
    val names = keys.map { if (it == "@other") otherName else categoryName(ledger, it) }
    val colors = keys.map { if (it == "@other") otherColor() else colorOf(it) }
    val totals = byCategory.indices.map { i -> shown.sumOf { series[it][i] } }
    val at = picked ?: totals.indices.maxBy { totals[it] }
    val readout = picked?.let { i ->
        val top = shown.maxBy { series[it][i] }
        line(slotLabel(yearly, first, i), money(totals[i], currency), names[top].takeIf { totals[i] > 0 }?.let { "$it ${money(series[top][i], currency)}" })
    } ?: stringResource(R.string.ledger_peak, slotLabel(yearly, first, at), money(totals[at], currency))
    ChartCard(
        stringResource(R.string.ledger_chart_categories_over_time),
        readout,
        legend = shown.map { names[it] to colors[it] },
    ) {
        BarChart(
            shown.map { series[it] },
            shown.map { colors[it] },
            if (yearly) listOf(formatMonthOnly(1), formatMonthOnly(6), formatMonthOnly(12)) else listOf("$first", "$last").distinct(),
            picked,
            { picked = it },
            readout,
            stacked = true,
            height = 160.dp,
        )
    }
}
