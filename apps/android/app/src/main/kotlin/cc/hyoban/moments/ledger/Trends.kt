package cc.hyoban.moments.ledger

import java.time.LocalDate
import java.time.YearMonth

// What the statistics' charts plot beyond a summary's sums: running totals, the weekdays'
// averages and the net assets at the end of each month.

/** The days from [start] up to but not including [end]. */
fun daysOf(start: LocalDate, end: LocalDate): List<LocalDate> =
    generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.toList()

/**
 * What [byDay] adds up to by the end of each day from [start] up to [end], stopping after
 * [until] (today, for a stretch still under way) so the line ends where the days do.
 */
fun runningTotals(byDay: Map<LocalDate, Long>, start: LocalDate, end: LocalDate, until: LocalDate? = null): List<Long> {
    var sum = 0L
    return daysOf(start, end).takeWhile { until == null || it <= until }.map { day -> (sum + (byDay[day] ?: 0)).also { sum = it } }
}

/**
 * What [byDay] came to on an average Monday, Tuesday and so on through Sunday from [start]
 * up to [end], counting each weekday's days whether anything was spent on them or not, and
 * none after [until].
 */
fun weekdayAverages(byDay: Map<LocalDate, Long>, start: LocalDate, end: LocalDate, until: LocalDate? = null): List<Long> {
    val sums = LongArray(7)
    val counts = IntArray(7)
    for (day in daysOf(start, end)) {
        if (until != null && day > until) break
        val index = day.dayOfWeek.value - 1
        sums[index] += byDay[day] ?: 0
        counts[index]++
    }
    return (0 until 7).map { if (counts[it] == 0) 0 else sums[it] / counts[it] }
}

/** Whole totals of [byDay] by month of [year], January first. */
fun byMonth(byDay: Map<LocalDate, Long>, year: Int): List<Long> {
    val out = LongArray(12)
    byDay.forEach { (day, cents) -> if (day.year == year) out[day.monthValue - 1] += cents }
    return out.toList()
}

/** Whole totals of [byDay] by year, from [first] through [last]. */
fun byYear(byDay: Map<LocalDate, Long>, first: Int, last: Int): List<Long> {
    val out = LongArray(last - first + 1)
    byDay.forEach { (day, cents) -> if (day.year in first..last) out[day.year - first] += cents }
    return out.toList()
}

/**
 * The net assets, as [assetsOf] counts them, at the end of each of the [months] months up to
 * this one, oldest first, the last as they are [today]: each account's balance taken back
 * through the bills after that day, converted at that day's rates.
 */
fun netAssetsByMonth(ledger: Ledger, currency: String, months: Int, today: LocalDate = LocalDate.now()): List<Pair<YearMonth, Long>> {
    val counted = ledger.accounts.filter { it.counted && !it.archived }
    val balances = counted.associate { it.id to (ledger.balances[it.id] ?: 0) }.toMutableMap()
    val current = YearMonth.from(today)
    // Newest first, as the ledger keeps them; a bill written for a day still to come is undone too.
    val txns = ledger.txns.iterator()
    var pending = if (txns.hasNext()) txns.next() else null
    val out = ArrayList<Pair<YearMonth, Long>>(months)
    for (back in 0 until months) {
        val month = current.minusMonths(back.toLong())
        val day = if (back == 0) today else month.atEndOfMonth()
        while (pending != null && pending.date > day) {
            effectsOf(pending, ledger.accountsById, ledger.rates).forEach { (account, cents) ->
                balances.computeIfPresent(account) { _, balance -> balance - cents }
            }
            pending = if (txns.hasNext()) txns.next() else null
        }
        var net = 0L
        for (account in counted) {
            val balance = balances[account.id] ?: 0
            net += ledger.rates.convert(balance, account.currency, currency, day) ?: balance
        }
        out += month to net
    }
    return out.asReversed()
}
