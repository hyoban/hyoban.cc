package cc.hyoban.moments

import cc.hyoban.moments.data.occasions.Count
import cc.hyoban.moments.data.occasions.Lunar
import cc.hyoban.moments.data.occasions.LunarDay
import cc.hyoban.moments.data.occasions.OccasionEdit
import cc.hyoban.moments.data.occasions.Occasions
import cc.hyoban.moments.data.spec.Spec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The occasions, as the 纪念日 page reads and writes them. */
class OccasionsTest {
    private fun json(text: String): JsonObject = Spec.json.parseToJsonElement(text).jsonObject

    private val car = json(
        """{"uid":"01K2TN6V00AAAAAAAAAAAAAAAA","at":"2025-08-17","parts":{"occasion":{"v":1,"name":"提车","repeat":"year","count":"years","firstLabel":"提车日","later":true}},"published":{"at":"2026-11-01T00:30:00Z"},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )
    private val licence = json(
        """{"uid":"01K2TN6V00AAAAAAAAAAAAAAAB","at":"2028-12-16","parts":{"occasion":{"v":1,"name":"驾驶证有效期截止"}},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )
    private val moved = json(
        """{"uid":"01K2TN6V00AAAAAAAAAAAAAAAC","at":"2024-03-01","parts":{"occasion":{"v":1,"name":"搬家"}},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )

    /** A made-up lunar calendar: every Gregorian day is the same lunar day but the 20th of each month, which is lunar 八月十六. */
    private val lunar = Lunar { day -> if (day.dayOfMonth == 20) LunarDay(8, 16, false) else LunarDay(1, 1, false) }

    @Test
    fun `a day that repeats comes round each year, one that doesn't is its day alone`() {
        val today = LocalDate.of(2026, 11, 4)
        val all = Occasions.of(listOf(licence, car, moved), today, lunar)
        assertEquals(listOf("提车", "驾驶证有效期截止", "搬家"), all.map { it.name })
        assertEquals(LocalDate.of(2027, 8, 17), all[0].next(today, lunar))
        assertEquals(LocalDate.of(2025, 8, 17), all[0].next(LocalDate.of(2025, 1, 1), lunar))
        assertEquals(LocalDate.of(2026, 8, 17), all[0].next(LocalDate.of(2026, 8, 17), lunar))
        assertEquals(1, all[0].years(LocalDate.of(2026, 8, 17)))
        assertEquals(LocalDate.of(2028, 12, 16), all[1].next(today, lunar))
        assertNull(all[2].next(today, lunar))
    }

    @Test
    fun `a lunar day comes round on the next Gregorian day that falls on it`() {
        val birthday = Occasions.occasionEntry(
            null,
            OccasionEdit("生日", LocalDate.of(1999, 9, 20), repeat = true, lunar = true, count = Count.AGE, firstLabel = null, note = null, private = true),
            "2026-11-01T00:00:00Z",
        )
        val occasion = Occasions.occasion(birthday)!!
        assertEquals(LocalDate.of(2026, 11, 20), occasion.next(LocalDate.of(2026, 11, 4), lunar))
        assertEquals(27, occasion.years(LocalDate.of(2026, 11, 20)))
        assertEquals("\"private\"", birthday["visibility"].toString())
        assertEquals("\"lunar\"", birthday.getValue("parts").jsonObject.getValue("occasion").jsonObject["calendar"].toString())
    }

    @Test
    fun `an occasion written back keeps what this version doesn't know and drops what was taken off`() {
        val occasion = Occasions.occasion(car)!!
        val edited = Occasions.occasionEntry(
            car,
            OccasionEdit("提车", occasion.first, repeat = false, lunar = false, count = Count.NONE, firstLabel = "提车日", note = "", private = false),
            "2026-11-02T00:00:00Z",
        )
        val part = edited.getValue("parts").jsonObject.getValue("occasion").jsonObject
        assertEquals(car["uid"], edited["uid"])
        assertEquals("true", part["later"].toString())
        assertNull(part["repeat"])
        assertNull(part["count"])
        assertNull(part["firstLabel"])
        assertNull(edited["visibility"])
        assertEquals(car["published"], edited["published"])
        assertEquals("\"2026-11-02T00:00:00Z\"", edited["updatedAt"].toString())
    }
}
