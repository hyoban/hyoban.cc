package cc.hyoban.moments

import cc.hyoban.moments.ui.compose.InferredTime
import cc.hyoban.moments.ui.compose.TimeSource
import cc.hyoban.moments.ui.compose.inferredTime
import cc.hyoban.moments.ui.compose.sameMinute
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The time the Moment editor works out by itself, the one 用照片的时间 goes back to. */
class InferredTimeTest {
    private fun at(stamp: String) = OffsetDateTime.parse(stamp)
    private val now = at("2026-10-30T18:00:00+08:00")
    private val stay = at("2026-10-30T09:15:00+08:00")

    @Test
    fun `the photos' times run from the earliest to the latest`() {
        val taken = listOf(at("2026-10-30T13:30:27+08:00"), at("2026-10-30T04:00:00Z"), at("2026-10-30T12:10:00+08:00"))
        val span = InferredTime(at("2026-10-30T04:00:00Z"), at("2026-10-30T13:30:27+08:00"), TimeSource.PHOTO)
        assertEquals(span, inferredTime(taken, emptyList(), stay, now, editing = false))
        assertEquals(span, inferredTime(taken, emptyList(), null, now, editing = true))
    }

    @Test
    fun `photos taken in one minute have no end`() {
        val taken = listOf(at("2026-10-30T13:30:27+08:00"), at("2026-10-30T13:30:05+08:00"))
        assertEquals(InferredTime(at("2026-10-30T13:30:05+08:00"), null, TimeSource.PHOTO), inferredTime(taken, emptyList(), null, now, editing = false))
    }

    @Test
    fun `a file's time stands in only when no photo says when it was taken`() {
        val saved = listOf(at("2026-10-29T20:00:00+08:00"))
        assertEquals(InferredTime(saved.first(), null, TimeSource.PHOTO), inferredTime(emptyList(), saved, stay, now, editing = false))
        assertEquals(at("2026-10-30T13:30:00+08:00"), inferredTime(listOf(at("2026-10-30T13:30:00+08:00")), saved, null, now, editing = false)?.start)
    }

    @Test
    fun `a new one without photos falls back to the stay, then now`() {
        val stayEnd = at("2026-10-30T11:40:00+08:00")
        assertEquals(InferredTime(stay, stayEnd, TimeSource.STAY), inferredTime(emptyList(), emptyList(), stay, now, editing = false, stayEnd = stayEnd))
        assertEquals(InferredTime(stay, null, TimeSource.STAY), inferredTime(emptyList(), emptyList(), stay, now, editing = false))
        assertEquals(InferredTime(now, null, TimeSource.NOW), inferredTime(emptyList(), emptyList(), null, now, editing = false))
    }

    @Test
    fun `a saved one without a photo time has none to go back to`() {
        assertNull(inferredTime(emptyList(), emptyList(), null, now, editing = true))
    }

    @Test
    fun `times the dialog can't tell apart are the same`() {
        assertTrue(sameMinute(at("2026-10-30T13:30:27+08:00"), at("2026-10-30T13:30:00+08:00")))
        assertTrue(sameMinute(at("2026-10-30T13:30:00+08:00"), at("2026-10-30T05:30:00Z")))
        assertFalse(sameMinute(at("2026-10-30T13:30:00+08:00"), at("2026-10-30T13:31:00+08:00")))
    }
}
