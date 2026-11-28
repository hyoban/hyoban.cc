package cc.hyoban.moments

import cc.hyoban.moments.data.local.checkinFix
import cc.hyoban.moments.data.local.momentFix
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackStay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 对齐时间: Moments by their photos, check-ins by the footprints' stays. */
class TimeAlignmentTest {
    private val zone = ZoneOffset.ofHours(8)
    private fun millis(stamp: String) = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()
    private fun moment(at: String, end: String? = null, vararg taken: String?, planned: Boolean? = null) = MomentRecord(
        uid = "m", occurredAt = at, text = "午饭\n很好吃", end = end, planned = planned,
        media = taken.mapIndexed { i, it -> MomentMedia("p$i", MediaType.IMAGE, takenAt = it) },
        createdAt = at, updatedAt = at,
    )
    private fun stay(start: String, end: String, lat: Double = 31.0, lng: Double = 121.0) =
        TrackStay(millis(start), millis(end), listOf(TrackPoint(millis(start), lat, lng, 10.0)))

    @Test
    fun `a Moment takes its photos' earliest to latest`() {
        val fix = momentFix(moment("2026-10-30T18:00:00+08:00", null, "2026-10-30T13:30:27+08:00", "2026-10-30T12:10:00+08:00", null))!!
        assertEquals("2026-10-30T12:10:00+08:00", fix.newAt)
        assertEquals("2026-10-30T13:30:27+08:00", fix.newEnd)
        assertEquals("午饭", fix.name)
    }

    @Test
    fun `a Moment already at its photos' time, without photo times or planned is left alone`() {
        assertNull(momentFix(moment("2026-10-30T12:10:40+08:00", null, "2026-10-30T12:10:00+08:00")))
        assertNull(momentFix(moment("2026-10-30T12:10:00+08:00", null, null)))
        assertNull(momentFix(moment("2026-10-30T09:00:00+08:00", null, "2026-10-30T12:10:00+08:00", planned = true)))
    }

    @Test
    fun `photos in one minute leave a Moment no end`() {
        val fix = momentFix(moment("2026-10-30T09:00:00+08:00", "2026-10-30T10:00:00+08:00", "2026-10-30T12:10:00+08:00", "2026-10-30T12:10:30+08:00"))!!
        assertNull(fix.newEnd)
    }

    @Test
    fun `a check-in takes the stay it was at, nearby and meeting it the most`() {
        val checkin = Checkin("c", "2026-10-30T12:20:00+08:00", "咖啡店")
        val stays = listOf(
            stay("2026-10-30T10:00:00+08:00", "2026-10-30T12:10:00+08:00"),
            stay("2026-10-30T12:12:00+08:00", "2026-10-30T13:40:00+08:00"),
            stay("2026-10-30T12:15:00+08:00", "2026-10-30T12:50:00+08:00", lat = 31.1),
        )
        val fix = checkinFix(checkin, 31.0005 to 121.0, stays, zone)!!
        assertEquals("2026-10-30T12:12:00+08:00", fix.newAt)
        assertEquals("2026-10-30T13:40:00+08:00", fix.newEnd)
    }

    @Test
    fun `a check-in with no stay near, or already at it, is left alone`() {
        val stays = listOf(stay("2026-10-30T12:12:00+08:00", "2026-10-30T13:40:00+08:00"))
        assertNull(checkinFix(Checkin("c", "2026-10-30T12:20:00+08:00", "店"), 31.1 to 121.0, stays, zone))
        assertNull(checkinFix(Checkin("c", "2026-10-30T15:00:00+08:00", "店"), 31.0 to 121.0, stays, zone))
        assertNull(checkinFix(Checkin("c", "2026-10-30T12:20:00+08:00", "店"), null, stays, zone))
        assertNull(checkinFix(Checkin("c", "2026-10-30T12:12:00+08:00", "店", end = "2026-10-30T13:40:00+08:00"), 31.0 to 121.0, stays, zone))
    }

    @Test
    fun `a photo without a time of its own takes the NAS's, at the Moment's offset`() {
        val nas = mapOf("p0" to java.time.LocalDateTime.parse("2026-10-30T12:10:00"))
        val fix = momentFix(moment("2026-10-31T09:00:00+09:00", null, null, "2026-10-30T13:30:00+08:00"), nas)!!
        assertEquals("2026-10-30T12:10:00+09:00", fix.newAt)
        assertEquals("2026-10-30T13:30:00+08:00", fix.newEnd)
        assertNull(momentFix(moment("2026-10-31T09:00:00+08:00", null, null), emptyMap()))
    }
}
