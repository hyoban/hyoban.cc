package cc.hyoban.moments

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
    fun `the earliest photo's time comes first`() {
        val taken = listOf(at("2026-10-30T13:30:27+08:00"), at("2026-10-30T04:00:00Z"))
        assertEquals(at("2026-10-30T04:00:00Z") to TimeSource.PHOTO, inferredTime(taken, emptyList(), stay, now, editing = false))
        assertEquals(at("2026-10-30T04:00:00Z") to TimeSource.PHOTO, inferredTime(taken, emptyList(), null, now, editing = true))
    }

    @Test
    fun `a file's time stands in only when no photo says when it was taken`() {
        val saved = listOf(at("2026-10-29T20:00:00+08:00"))
        assertEquals(saved.first() to TimeSource.PHOTO, inferredTime(emptyList(), saved, stay, now, editing = false))
        assertEquals(at("2026-10-30T13:30:00+08:00"), inferredTime(listOf(at("2026-10-30T13:30:00+08:00")), saved, null, now, editing = false)?.first)
    }

    @Test
    fun `a new one without photos falls back to the stay, then now`() {
        assertEquals(stay to TimeSource.STAY, inferredTime(emptyList(), emptyList(), stay, now, editing = false))
        assertEquals(now to TimeSource.NOW, inferredTime(emptyList(), emptyList(), null, now, editing = false))
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
