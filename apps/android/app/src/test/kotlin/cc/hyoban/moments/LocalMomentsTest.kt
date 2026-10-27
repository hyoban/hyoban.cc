package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.exifTime
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.parseStamp
import cc.hyoban.moments.data.local.nearestPlaces
import cc.hyoban.moments.data.local.stamp
import cc.hyoban.moments.data.local.videoFix
import cc.hyoban.moments.data.local.videoTime
import cc.hyoban.moments.data.spec.Place
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When and where a Moment written on the phone happened, and the ids it is given. */
class LocalMomentsTest {
    @Test
    fun `a ULID is the time then randomness, in Crockford's base 32, sorting by time`() {
        // The site's Moment of 2026-08-14 21:05:22 +08:00 is 01M00612JG….
        assertEquals("01M00612JG" + "0".repeat(16), Ulid.of(1786712722000, ByteArray(10)))
        assertEquals("Z".repeat(16), Ulid.of(0, ByteArray(10) { -1 }).drop(10))
        val earlier = Ulid.next(1_000)
        val later = Ulid.next(2_000)
        assertTrue(earlier < later)
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{26}").matches(Ulid.next()))
        assertTrue(newPlaceId().matches(Regex("p-[0-9a-z]{26}")))
    }

    @Test
    fun `times are written to the second with their offset, as the site's records are`() {
        assertEquals("2026-08-14T21:05:22+08:00", stamp(OffsetDateTime.of(2026, 8, 14, 21, 5, 22, 900, ZoneOffset.ofHours(8))))
        assertEquals("2026-08-14T13:05:22+00:00", stamp(OffsetDateTime.of(2026, 8, 14, 13, 5, 22, 0, ZoneOffset.UTC)))
        assertEquals(OffsetDateTime.parse("2026-08-14T21:05:22+08:00"), parseStamp("2026-08-14T21:05:22+08:00"))
        assertEquals(OffsetDateTime.parse("2026-08-14T00:00+08:00"), parseStamp("2026-08-14", ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `a photo's time is its own offset's, else the phone's zone's then`() {
        assertEquals(OffsetDateTime.parse("2026-08-14T21:05:22+09:00"), exifTime("2026:08:14 21:05:22", "+09:00"))
        assertEquals(OffsetDateTime.parse("2026-01-14T21:05:22+01:00"), exifTime("2026:01:14 21:05:22", null, ZoneId.of("Europe/Paris")))
        assertEquals(OffsetDateTime.parse("2026-07-14T21:05:22+02:00"), exifTime("2026:07:14 21:05:22", "  ", ZoneId.of("Europe/Paris")))
        assertNull(exifTime("0000:00:00 00:00:00", null))
        assertNull(exifTime(null, "+08:00"))
    }

    @Test
    fun `a video's time is its UTC creation time in the phone's zone then`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(OffsetDateTime.parse("2026-10-27T12:28:01+08:00"), videoTime("20261027T042801.000Z", shanghai))
        assertEquals(OffsetDateTime.parse("2026-10-27T12:28:01+08:00"), videoTime("20261027T042801", shanghai))
        assertNull(videoTime("19040101T000000.000Z"))
        assertNull(videoTime("19700101T000000.000Z"))
        assertNull(videoTime(""))
        assertNull(videoTime(null))
    }

    @Test
    fun `a video's place is its ISO 6709 location`() {
        assertEquals(Fix(31.2304, 121.4737), videoFix("+31.2304+121.4737/"))
        assertEquals(Fix(-33.8688, -151.2093), videoFix("-33.8688-151.2093+012.345/"))
        assertNull(videoFix("+00.0000+000.0000/"))
        assertNull(videoFix("unknown"))
        assertNull(videoFix(null))
    }

    @Test
    fun `the known places near a fix come nearest first, with how far each is`() {
        val places = mapOf(
            "nanjing" to Place("南京", 32.0603, 118.7969),
            "xuanwu-lake" to Place("玄武湖", 32.0756, 118.7934),
            "shanghai" to Place("上海", 31.2304, 121.4737),
        )
        // About 1.1 km from the lake, 1.9 km from the city's centre.
        val near = nearestPlaces(places, Fix(32.0757, 118.8051), limit = 2)
        assertEquals(listOf("xuanwu-lake", "nanjing"), near.map { it.first })
        assertTrue(near[0].second < 1_200 && near[1].second < 2_000)
        assertEquals(268_000.0, distance(Fix(32.0603, 118.7969), Fix(31.2304, 121.4737)), 3_000.0)
        assertEquals("32.0757, 118.8051", coordinatesName(Fix(32.07571, 118.80509)))
    }
}
