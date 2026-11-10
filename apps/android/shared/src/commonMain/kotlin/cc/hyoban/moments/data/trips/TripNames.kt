package cc.hyoban.moments.data.trips

import java.time.LocalDate

/** Formats qualified destinations; it does not decide which observations establish a visit. */
object TripNames {
    /**
     * A trip's name, as one would write it: where it went, the [cities] with the most of its records
     * in the order they were reached. [cities] have already been qualified as destinations,
     * even with one record; [where] is the fallback when none have a city name. Also says
     * when, the holiday it spans before it (国庆香港), else 一日 or 周末 after it (南京一日, 合肥周末).
     */
    fun nameOf(start: LocalDate, end: LocalDate, cities: List<String>, where: String): String {
        val counts = cities.groupingBy { it }.eachCount()
        val reached = cities.distinct()
        val most = reached.sortedByDescending { counts.getValue(it) }
        val chosen = if (most.size <= 3 && most.sumOf { it.length } <= 9) most else most.take(2)
        val place = when {
            reached.isEmpty() -> where
            else -> reached.filter { it in chosen }.joinToString("、") +
                if (most.size > chosen.size) "等" else ""
        }
        holidayOf(start, end)?.let { return it + place }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toList()
        return when {
            days.size == 1 -> place + "一日"
            days.size <= 3 && days.all { it.dayOfWeek.value >= 5 } && days.any { it.dayOfWeek.value >= 6 } -> place + "周末"
            else -> place
        }
    }

    /** The day of the Spring Festival, by year, for the years the records reach. */
    private val SPRING_FESTIVAL = mapOf(
        2016 to "02-08", 2017 to "01-28", 2018 to "02-16", 2019 to "02-05", 2020 to "01-25", 2021 to "02-12",
        2022 to "02-01", 2023 to "01-22", 2024 to "02-10", 2025 to "01-29", 2026 to "02-17", 2027 to "02-06",
        2028 to "01-26", 2029 to "02-13", 2030 to "02-03",
    )

    /** The holiday a trip from [start] to [end] spans any of, with a day either side for the travel there and back. */
    private fun holidayOf(start: LocalDate, end: LocalDate): String? {
        val spans = buildList {
            for (year in start.year..end.year) {
                SPRING_FESTIVAL[year]?.let { LocalDate.parse("$year-$it") }?.let { add("春节" to (it.minusDays(8)..it.plusDays(8))) }
                add("元旦" to (LocalDate.of(year - 1, 12, 30)..LocalDate.of(year, 1, 2)))
                add("清明" to (LocalDate.of(year, 4, 3)..LocalDate.of(year, 4, 7)))
                add("五一" to (LocalDate.of(year, 4, 30)..LocalDate.of(year, 5, 6)))
                add("国庆" to (LocalDate.of(year, 9, 30)..LocalDate.of(year, 10, 8)))
            }
        }
        return spans.firstOrNull { (_, days) -> start <= days.endInclusive && end >= days.start }?.first
    }

    /** A city's name as said in passing: 杭州 for 杭州市, 香港 for 香港特别行政区. */
    fun shortCity(name: String): String = when {
        name.endsWith("特别行政区") -> name.removeSuffix("特别行政区")
        name.endsWith("市") && name.length > 2 -> name.removeSuffix("市")
        else -> name
    }

}
