package cc.hyoban.moments.data.occasions

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.data.spec.entryUid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// The occasions (packages/data-spec's `occasion` part), each on an entry of the day it started: a
// birthday on the day of birth, an anniversary on the day the thing happened, a deadline on its
// own day. One that repeats comes round each year on that day of the calendar it follows, a lunar
// one on the lunar day its first day fell on; the rest are the one day. Read as far as the 纪念日
// page needs, and written back over the entry as it came, so what this version doesn't know stays.

/** How the years since the first day are said: as an age (26 岁), in years (1 周年), or not at all. */
enum class Count(val value: String) {
    NONE(""),
    AGE("age"),
    YEARS("years");

    companion object {
        fun of(value: String?): Count = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** A day of the lunar calendar: its month, the day of it, and whether the month is the leap one. */
data class LunarDay(val month: Int, val day: Int, val leap: Boolean)

/** The lunar calendar, as the phone's ICU has it; the tests give a made-up one. */
fun interface Lunar {
    fun of(day: LocalDate): LunarDay
}

data class Occasion(
    val entry: JsonObject,
    val name: String,
    /** The day it started: of birth, of the thing, or the one day. */
    val first: LocalDate,
    val repeat: Boolean,
    val lunar: Boolean,
    val count: Count,
    /** What the first day is called, like 提车日; the name when not given. */
    val firstLabel: String?,
    val note: String?,
    val private: Boolean,
) {
    val uid: String get() = entry.entryUid

    /** The lunar day the first day fell on, for one that follows the lunar calendar. */
    fun lunarDay(lunar: Lunar): LunarDay = lunar.of(first)

    /**
     * The day it next comes round, [today] or after: its first day while that is still to come,
     * the next Gregorian or lunar return of it, or null for a day that doesn't repeat and has passed.
     */
    fun next(today: LocalDate, lunar: Lunar): LocalDate? {
        if (!today.isAfter(first)) return first
        if (!repeat) return null
        if (!this.lunar) {
            val thisYear = sameDay(today.year)
            return if (!thisYear.isBefore(today)) thisYear else sameDay(today.year + 1)
        }
        // A lunar day comes round within a lunar year and its leap month, so by then one is found.
        val wanted = lunar.of(first)
        var day = today
        repeat(400) {
            if (lunar.of(day) == wanted) return day
            day = day.plusDays(1)
        }
        return null
    }

    /** The years from the first day to [on], as the calendar counts them: by the year alone. */
    fun years(on: LocalDate): Int = on.year - first.year

    /** The first day's own day in [year], the 29th of February falling back to the 28th. */
    private fun sameDay(year: Int): LocalDate = LocalDate.of(year, first.month, minOf(first.dayOfMonth, LocalDate.of(year, first.month, 1).lengthOfMonth()))
}

object Occasions {
    /** The occasions among [entries], the ones coming soonest first, then those that have passed. */
    fun of(entries: Collection<JsonObject>, today: LocalDate, lunar: Lunar): List<Occasion> =
        entries.mapNotNull(::occasion).sortedWith(
            compareBy<Occasion> { it.next(today, lunar) ?: LocalDate.MAX }.thenByDescending { it.first }.thenBy { it.name },
        )

    fun occasion(entry: JsonObject): Occasion? {
        val part = entry.part("occasion") ?: return null
        return Occasion(
            entry = entry,
            name = part.text("name") ?: return null,
            first = LocalDate.parse(entry.text("at")?.take(10) ?: return null),
            repeat = part.text("repeat") == "year",
            lunar = part.text("calendar") == "lunar",
            count = Count.of(part.text("count")),
            firstLabel = part.text("firstLabel"),
            note = part.text("note"),
            private = Entries.isPrivate(entry),
        )
    }

    /** Days from [today] to [day]: 0 today, negative once passed. */
    fun daysUntil(today: LocalDate, day: LocalDate): Long = ChronoUnit.DAYS.between(today, day)

    /** An occasion as an entry: [base]'s, with what the page edits written over it; a new one gets its uid from its first day and name. */
    fun occasionEntry(base: JsonObject?, edit: OccasionEdit, now: String): JsonObject {
        val part = buildJsonObject {
            (base?.part("occasion") ?: JsonObject(emptyMap())).forEach { (key, value) -> put(key, value) }
            put("v", 1)
            put("name", edit.name)
            if (edit.repeat) put("repeat", "year")
            if (edit.repeat && edit.lunar) put("calendar", "lunar")
            if (edit.repeat && edit.count != Count.NONE) put("count", edit.count.value)
            edit.firstLabel?.takeIf { edit.repeat && it.isNotBlank() }?.let { put("firstLabel", it) }
            edit.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }.withoutAbsent(
            "repeat".takeIf { !edit.repeat },
            "calendar".takeIf { !(edit.repeat && edit.lunar) },
            "count".takeIf { !edit.repeat || edit.count == Count.NONE },
            "firstLabel".takeIf { !edit.repeat || edit.firstLabel.isNullOrBlank() },
            "note".takeIf { edit.note.isNullOrBlank() },
        )
        val at = edit.first.toString()
        val uid = base?.entryUid ?: Entries.stableUlid(at, "occasion:${edit.name}:${System.nanoTime()}")
        return buildJsonObject {
            base?.forEach { (key, value) -> put(key, value) }
            put("uid", uid)
            put("at", at)
            put("parts", buildJsonObject {
                (base?.get("parts") as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                put("occasion", part)
            })
            if (edit.private) put("visibility", Visibility.PRIVATE.value)
            if (base == null) put("createdAt", now)
            put("updatedAt", now)
        }.withoutAbsent("visibility".takeIf { !edit.private })
    }

    private fun JsonObject.withoutAbsent(vararg keys: String?): JsonObject = JsonObject(this - keys.filterNotNull().toSet())

    private fun JsonObject.part(plugin: String): JsonObject? = (this["parts"] as? JsonObject)?.get(plugin) as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

data class OccasionEdit(
    val name: String,
    val first: LocalDate,
    val repeat: Boolean,
    val lunar: Boolean,
    val count: Count,
    val firstLabel: String?,
    val note: String?,
    val private: Boolean,
)

/** The lunar calendar the phone carries (ICU's Chinese calendar), the days taken in China's time. */
object IcuLunar : Lunar {
    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    override fun of(day: LocalDate): LunarDay {
        val calendar = android.icu.util.ChineseCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Shanghai"), android.icu.util.ULocale.CHINA)
        calendar.time = java.util.Date.from(day.atTime(12, 0).atZone(zone).toInstant())
        return LunarDay(
            month = calendar.get(android.icu.util.Calendar.MONTH) + 1,
            day = calendar.get(android.icu.util.Calendar.DAY_OF_MONTH),
            leap = calendar.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 1,
        )
    }
}
