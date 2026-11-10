package cc.hyoban.moments.data.occasions

import java.time.LocalDate

/** The lunar calendar the phone carries (ICU's Chinese calendar), the days taken in China's time. */
actual object IcuLunar : Lunar {
    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    actual override fun of(day: LocalDate): LunarDay {
        val calendar = android.icu.util.ChineseCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Shanghai"), android.icu.util.ULocale.CHINA)
        calendar.time = java.util.Date.from(day.atTime(12, 0).atZone(zone).toInstant())
        return LunarDay(
            month = calendar.get(android.icu.util.Calendar.MONTH) + 1,
            day = calendar.get(android.icu.util.Calendar.DAY_OF_MONTH),
            leap = calendar.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 1,
        )
    }
}
