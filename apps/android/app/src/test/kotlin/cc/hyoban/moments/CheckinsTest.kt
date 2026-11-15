package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkins
import cc.hyoban.moments.data.things.checkinAsMoment
import cc.hyoban.moments.data.things.checkinOf
import cc.hyoban.moments.data.things.editedEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which check-ins a stay or a bill's time meets, as the footprints and the ledger ask. */
class CheckinsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun entry(uid: String, at: String, end: String?, place: String?): JsonObject = Spec.json.parseToJsonElement(
        """{"uid":"$uid","at":"$at"${end?.let { ",\"end\":\"$it\"" } ?: ""}${place?.let { ",\"place\":\"$it\"" } ?: ""},
        "parts":{"checkin":{"v":1,"venue":"$uid","source":{"app":"phone"}}},"createdAt":"$at","updatedAt":"$at"}""",
    ).jsonObject

    private fun ms(stamp: String) = OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

    private val checkins = Checkins.of(
        listOf(
            // A noodle shop over lunch, kept from a stay.
            entry("NOODLES", "2026-03-02T12:10:00+08:00", "2026-03-02T13:20:00+08:00", "p-noodles"),
            // A mall around it, the whole afternoon.
            entry("MALL", "2026-03-02T11:00:00+08:00", "2026-03-02T17:00:00+08:00", "p-mall"),
            // Swarm's, a moment with no end.
            entry("CAFE", "2026-03-03T09:00:00+08:00", null, "p-cafe"),
            // A night at a hotel, past midnight.
            entry("HOTEL", "2026-03-04T21:00:00+08:00", "2026-03-05T08:00:00+08:00", "p-hotel"),
        ),
    )

    @Test
    fun aBillTakesTheShortestCheckinOverIt() {
        assertEquals("NOODLES", checkins.covering(ms("2026-03-02T12:42:00+08:00"), zone)?.uid)
        assertEquals("MALL", checkins.covering(ms("2026-03-02T15:00:00+08:00"), zone)?.uid)
    }

    @Test
    fun aCheckinWithNoEndCoversFifteenMinutesEitherSide() {
        assertEquals("CAFE", checkins.covering(ms("2026-03-03T09:14:00+08:00"), zone)?.uid)
        assertNull(checkins.covering(ms("2026-03-03T09:16:00+08:00"), zone))
    }

    @Test
    fun aStayMeetsACheckinThatStartedTheDayBefore() {
        val stay = checkins.overlapping(ms("2026-03-05T06:00:00+08:00"), ms("2026-03-05T07:30:00+08:00"), zone)
        assertEquals(listOf("HOTEL"), stay.map { it.uid })
    }

    @Test
    fun nothingCoversABillOutsideEveryCheckin() {
        assertNull(checkins.covering(ms("2026-03-02T18:00:00+08:00"), zone))
    }

    private fun parts(entry: JsonObject) = entry.getValue("parts").jsonObject

    @Test
    fun aMomentWithNothingButItsPlaceIsACheckinThereNamedAfterIt() {
        val moment = MomentRecord("NEW", "2026-03-06T10:00:00+08:00", listOf("p-park"), "", createdAt = "2026-03-06T10:00:00+08:00", updatedAt = "2026-03-06T10:00:00+08:00")
        val entry = editedEntry(moment, "公园", placeChanged = true)
        assertEquals(setOf("checkin"), parts(entry).keys)
        assertEquals("公园", checkinOf(entry)?.venue)
        assertEquals("p-park", checkinOf(entry)?.place)
        assertNull(editedEntry(moment.copy(text = "风很大"), "公园", placeChanged = true).let { parts(it)["checkin"] }, "a Moment written there isn't one")
    }

    @Test
    fun aCheckinGivenWordsIsAMomentThatKeepsItsCheckinAndClearedIsOneAgain() {
        val opened = checkinAsMoment(entry("CAFE", "2026-03-03T09:00:00+08:00", null, "p-cafe"))!!
        assertEquals("", opened.text)
        val written = editedEntry(opened.copy(text = "拿铁"), "Cafe", placeChanged = false)
        assertEquals(setOf("text", "checkin"), parts(written).keys)
        assertEquals("CAFE", parts(written).getValue("checkin").jsonObject["venue"]?.toString()?.trim('"'), "its venue kept while its place is the same")
        assertNull(checkinOf(written), "a Moment, not a check-in alone")

        val cleared = editedEntry(cc.hyoban.moments.data.spec.Entries.toMoment(written)!!.copy(text = ""), "咖啡馆", placeChanged = true)
        assertEquals(setOf("checkin"), parts(cleared).keys)
        assertEquals("咖啡馆", checkinOf(cleared)?.venue, "renamed after the place it moved to")

        val nowhere = editedEntry(cc.hyoban.moments.data.spec.Entries.toMoment(written)!!.copy(places = emptyList()), null, placeChanged = true)
        assertEquals(setOf("text"), parts(nowhere).keys, "a check-in goes with its place")
    }
}
