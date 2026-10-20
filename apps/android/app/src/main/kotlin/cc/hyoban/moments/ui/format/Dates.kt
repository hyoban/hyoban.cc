package cc.hyoban.moments.ui.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Dates as a reader of the app's language says them, Chinese or English; the year only
// when it isn't this one.

private fun Locale.chinese() = language == "zh"

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** The weekday's short name: `周二`, `Tue`. */
private fun weekday(date: LocalDate, locale: Locale) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, if (locale.chinese()) Locale.CHINESE else Locale.ENGLISH)

/** `10月6日` or `Oct 6`, with the year in another year: `2025年10月6日`, `Oct 6, 2025`. */
fun formatDate(dateKey: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.parse(dateKey.take(10))
    val thisYear = date.year == today.year
    if (!locale.chinese()) return date.format(if (thisYear) MONTH_DAY else MONTH_DAY_YEAR)
    val day = "${date.monthValue}月${date.dayOfMonth}日"
    return if (thisYear) day else "${date.year}年$day"
}

/** `10月6日 周二`, or `Tue, Oct 6`. */
fun formatDay(dateKey: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.parse(dateKey.take(10))
    return if (locale.chinese()) "${formatDate(dateKey, today, locale)} ${weekday(date, locale)}" else "${weekday(date, locale)}, ${formatDate(dateKey, today, locale)}"
}

/** `17:40`, or nothing for a Moment known only by its day. */
fun formatTime(occurredAt: String): String? = if (occurredAt.length > 10) occurredAt.substring(11, 16) else null

/** `9月30日 – 10月6日` or `Sep 30 – Oct 6`, with the year once when it isn't this one. */
fun formatRange(start: String, end: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
    val from = LocalDate.parse(start)
    val to = LocalDate.parse(end)
    val sameYear = from.year == to.year
    if (!locale.chinese()) {
        // English puts the shared year once, at the end.
        val first = if (sameYear) from.format(MONTH_DAY) else formatDate(start, today, locale)
        val last = if (sameYear && to.year != today.year) to.format(MONTH_DAY_YEAR) else formatDate(end, if (sameYear) to else today, locale)
        return "$first – $last"
    }
    val first = if (sameYear && from.year != today.year) "${from.year}年${from.monthValue}月${from.dayOfMonth}日" else formatDate(start, today, locale)
    val last = if (sameYear) "${to.monthValue}月${to.dayOfMonth}日" else formatDate(end, today, locale)
    return "$first – $last"
}

/** How many days a trip covers, both ends counted. */
fun daysBetween(start: String, end: String): Int =
    ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).toInt() + 1

/** `6日 周二` or `Tue 6`, for a day under its month's header. */
fun formatDayOfMonth(dateKey: String, locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.parse(dateKey.take(10))
    return if (locale.chinese()) "${date.dayOfMonth}日 ${weekday(date, locale)}" else "${weekday(date, locale)} ${date.dayOfMonth}"
}

/** `10月` or `October`, and `2026`, for a month's header. */
fun formatMonth(dateKey: String, locale: Locale = Locale.getDefault()): Pair<String, String> {
    val date = LocalDate.parse(dateKey.take(10))
    val name = if (locale.chinese()) "${date.monthValue}月" else date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return name to "${date.year}"
}

/** A month of the year by its number, for a filter's choice: `10 月`, `Oct`. */
fun formatMonthOnly(month: Int, locale: Locale = Locale.getDefault()): String =
    if (locale.chinese()) "$month 月" else java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** The weekdays' one-letter names from Monday, for a calendar's header: `一`…`日`, `M`…`S`. */
fun weekdayLetters(locale: Locale = Locale.getDefault()): List<String> =
    java.time.DayOfWeek.entries.map { it.getDisplayName(TextStyle.NARROW, if (locale.chinese()) Locale.CHINESE else Locale.ENGLISH) }
