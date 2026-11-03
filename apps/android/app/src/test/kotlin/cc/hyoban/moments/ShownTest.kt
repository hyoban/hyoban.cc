package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.store.shown
import kotlin.test.Test
import kotlin.test.assertEquals

/** Demo mode shows only what the public sees: no private Moment, no Moment of a private trip, no private trip. */
class ShownTest {
    private val secret = TripRecord("secret", "secret", "2025-10-18", "2025-10-22", createdAt = "2025-10-18", updatedAt = "2025-10-18")
    private val open = TripRecord("open", "open", "2025-09-01", "2025-09-03", createdAt = "2025-09-01", updatedAt = "2025-09-01")
    private val moments = listOf(
        moment("HIDDEN", "2025-08-20", hidden = true),
        moment("IN_SECRET", "2025-10-20T10:00:00+08:00"),
        moment("IN_OPEN", "2025-09-02T10:00:00+08:00"),
        moment("SHOWN", "2023-03-02"),
    )
    private val isTripPrivate = { id: String -> id == "secret" }

    @Test
    fun `everything outside demo mode`() {
        val all = shown(moments, listOf(secret, open), demo = false, isTripPrivate)
        assertEquals(moments, all.moments)
        assertEquals(listOf(secret, open), all.trips)
    }

    @Test
    fun `only the public's in demo mode`() {
        val demo = shown(moments, listOf(secret, open), demo = true, isTripPrivate)
        assertEquals(listOf("IN_OPEN", "SHOWN"), demo.moments.map { it.uid })
        assertEquals(listOf("open"), demo.trips.map { it.id })
    }

    private fun moment(uid: String, occurredAt: String, hidden: Boolean = false) =
        MomentRecord(uid, occurredAt, text = "", hidden = hidden, createdAt = occurredAt, updatedAt = occurredAt)
}
