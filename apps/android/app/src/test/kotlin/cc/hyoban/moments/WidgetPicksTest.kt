package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Cover
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.widget.TripPhase
import cc.hyoban.moments.widget.memoryFor
import cc.hyoban.moments.widget.publicMoments
import cc.hyoban.moments.widget.tripCover
import cc.hyoban.moments.widget.tripFor
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The widgets show a Moment from this day in an earlier year, or any earlier one, and the trip
 * that fits the day, never what is private.
 */
class WidgetPicksTest {
    private val today = LocalDate.parse("2026-10-20")
    private val hasStill = { _: MomentMedia -> true }

    @Test
    fun `a Moment from this day in an earlier year comes first`() {
        val moments = listOf(
            moment("TODAY", "2026-10-20T09:00:00+08:00"),
            moment("LAST", "2025-10-20T09:00:00+08:00"),
            moment("OTHER", "2025-03-02"),
            moment("BARE", "2024-10-20", photo = false),
        )
        val memory = memoryFor(moments, today, hasStill)!!
        assertEquals("LAST", memory.moment.uid)
        assertEquals(1, memory.yearsAgo)
    }

    @Test
    fun `without one, any earlier Moment with a photo, the same all day`() {
        val moments = (1..20).map { moment("M$it", "2025-03-%02d".format(it)) }
        val memory = memoryFor(moments, today, hasStill)!!
        assertNull(memory.yearsAgo)
        assertEquals(memory.moment.uid, memoryFor(moments.shuffled(), today, hasStill)!!.moment.uid)
        val week = (0L..6L).map { memoryFor(moments, today.plusDays(it), hasStill)!!.moment.uid }
        assertTrue(week.toSet().size > 1)
    }

    @Test
    fun `nothing without a photo`() {
        assertNull(memoryFor(listOf(moment("BARE", "2025-10-20", photo = false)), today, hasStill))
    }

    @Test
    fun `the trip under way, else the last one over, else the next`() {
        val past = trip("past", "2026-09-01", "2026-09-05")
        val older = trip("older", "2025-09-01", "2025-09-05")
        val now = trip("now", "2026-10-18", "2026-10-22")
        val next = trip("next", "2026-12-01", "2026-12-03")
        assertEquals("now" to TripPhase.ONGOING, tripFor(listOf(next, now, past), today)?.let { it.first.id to it.second })
        assertEquals("past" to TripPhase.PAST, tripFor(listOf(next, past, older), today)?.let { it.first.id to it.second })
        assertEquals("next" to TripPhase.UPCOMING, tripFor(listOf(next), today)?.let { it.first.id to it.second })
        assertNull(tripFor(emptyList(), today))
    }

    @Test
    fun `private Moments, Moments of private trips and private photos are never picked`() {
        val secret = trip("secret", "2025-10-18", "2025-10-22")
        val moments = listOf(
            moment("HIDDEN", "2025-10-20", hidden = true),
            moment("IN_SECRET", "2025-10-21", trip = "secret"),
            moment("ON_SECRET_DAYS", "2025-10-20T10:00:00+08:00"),
            moment("PHOTO_PRIVATE", "2024-10-20", private = listOf("media")),
            moment("SHOWN", "2023-03-02"),
        )
        val shown = publicMoments(moments, listOf(secret)) { it == "secret" }
        assertEquals(listOf("PHOTO_PRIVATE", "SHOWN"), shown.map { it.uid })
        // Only the public one with a public photo is left, so not one from this day.
        val memory = memoryFor(shown, today, hasStill)!!
        assertEquals("SHOWN", memory.moment.uid)
        assertNull(memory.yearsAgo)
        assertNull(memoryFor(shown.take(1), today, hasStill))
        assertTrue(Entries.isPartPrivate(moments[0], "text"))
        assertTrue(Entries.isPartPrivate(moments[3], "media"))
        assertTrue(!Entries.isPartPrivate(moments[3], "text"))
    }

    @Test
    fun `a trip's cover passes over private Moments and photos`() {
        val trip = trip("away", "2026-09-01", "2026-09-05").copy(covers = listOf(Cover("HIDDEN", "HIDDEN-1")))
        val moments = listOf(
            moment("HIDDEN", "2026-09-01", hidden = true),
            moment("PHOTO_PRIVATE", "2026-09-02", private = listOf("media")),
            moment("SHOWN", "2026-09-03"),
        )
        val shown = publicMoments(moments, listOf(trip)) { false }
        assertEquals("SHOWN-1", tripCover(trip, shown, listOf(trip))?.uid)
        assertNull(tripCover(trip, shown.take(1), listOf(trip)))
    }

    private fun trip(id: String, start: String, end: String) = TripRecord(id, id, start, end, createdAt = start, updatedAt = start)

    private fun moment(
        uid: String,
        occurredAt: String,
        photo: Boolean = true,
        hidden: Boolean = false,
        trip: String? = null,
        private: List<String> = emptyList(),
    ) = MomentRecord(
        uid,
        occurredAt,
        text = "",
        hidden = hidden,
        media = if (photo) listOf(MomentMedia("$uid-1", MediaType.IMAGE)) else emptyList(),
        trip = trip,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        extra = if (private.isEmpty()) JsonObject(emptyMap())
        else JsonObject(mapOf(Entries.PART_VISIBILITY to JsonObject(private.associateWith { JsonPrimitive("private") }))),
    )
}
