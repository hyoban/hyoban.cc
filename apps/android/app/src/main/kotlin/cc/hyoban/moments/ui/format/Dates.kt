package cc.hyoban.moments.ui.format

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Dates as a Chinese reader says them; the year only when it isn't this one.

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** `10月6日`, or `2025年10月6日` in another year. */
fun formatDate(dateKey: String, today: LocalDate = LocalDate.now()): String {
    val date = LocalDate.parse(dateKey.take(10))
    val day = "${date.monthValue}月${date.dayOfMonth}日"
    return if (date.year == today.year) day else "${date.year}年$day"
}

/** `10月6日 周二`. */
fun formatDay(dateKey: String, today: LocalDate = LocalDate.now()): String =
    "${formatDate(dateKey, today)} ${WEEKDAYS[LocalDate.parse(dateKey.take(10)).dayOfWeek.value - 1]}"

/** `17:40`, or nothing for a Moment known only by its day. */
fun formatTime(occurredAt: String): String? = if (occurredAt.length > 10) occurredAt.substring(11, 16) else null

/** `9月30日 – 10月6日`, with the year once when it isn't this one. */
fun formatRange(start: String, end: String, today: LocalDate = LocalDate.now()): String {
    val from = LocalDate.parse(start)
    val to = LocalDate.parse(end)
    val first = if (from.year == to.year && from.year != today.year) "${from.year}年${from.monthValue}月${from.dayOfMonth}日" else formatDate(start, today)
    val last = if (from.year == to.year) "${to.monthValue}月${to.dayOfMonth}日" else formatDate(end, today)
    return "$first – $last"
}

/** How many days a trip covers, both ends counted. */
fun daysBetween(start: String, end: String): Int =
    ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).toInt() + 1

/** `6日 周二`, for a day under its month's header. */
fun formatDayOfMonth(dateKey: String): String {
    val date = LocalDate.parse(dateKey.take(10))
    return "${date.dayOfMonth}日 ${WEEKDAYS[date.dayOfWeek.value - 1]}"
}

/** `10月` and `2026` for a month's header. */
fun formatMonth(dateKey: String): Pair<String, String> {
    val date = LocalDate.parse(dateKey.take(10))
    return "${date.monthValue}月" to "${date.year}"
}
