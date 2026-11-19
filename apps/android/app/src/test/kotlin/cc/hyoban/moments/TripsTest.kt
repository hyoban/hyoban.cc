package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.Waypoint
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.daysOf
import cc.hyoban.moments.data.trips.stopsOf
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.tripOfTxn
import cc.hyoban.moments.ledger.txnsOfTrip
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A Moment belongs to the trip it names, else to the one whose days include it. */
class TripsTest {
    private val ferry = trip("ferry", "2026-10-01", "2026-10-03", waypoints = listOf(Waypoint("2026-10-01T08:00:00+08:00", "nanjing")))
    private val later = trip("later", "2026-11-01", "2026-11-02")
    private val trips = listOf(later, ferry)
    private val places = mapOf(
        "nanjing" to Place("南京", 32.06, 118.79),
        "yantai" to Place("烟台", 37.54, 121.39),
        "dalian" to Place("大连", 38.91, 121.60),
    )

    @Test
    fun `a Moment names its trip, or falls within one`() {
        assertEquals("ferry", tripOf(moment("A", "2026-10-02T09:00:00+08:00"), trips)?.id)
        assertEquals("later", tripOf(moment("B", "2026-10-02T09:00:00+08:00", trip = "later"), trips)?.id)
        assertNull(tripOf(moment("C", "2026-10-20"), trips))
    }

    @Test
    fun `its Moments come day by day, and its stops in the order they were reached`() {
        val moments = listOf(
            moment("D", "2026-10-02T20:00:00+08:00", "dalian"),
            moment("Y", "2026-10-01T17:40:00+08:00", "yantai"),
            moment("Y2", "2026-10-02", "yantai"),
            moment("X", "2026-12-01", "dalian"),
        )

        assertEquals(
            listOf("2026-10-01" to listOf("Y"), "2026-10-02" to listOf("Y2", "D")),
            daysOf(ferry, moments, trips).map { (day, list) -> day to list.map { it.uid } },
        )
        assertEquals(listOf("nanjing", "yantai", "dalian"), stopsOf(ferry, moments, trips, places).map { it.placeId })
    }

    @Test
    fun `its covers pass over the photos kept for the record`() {
        val receipt = MomentMedia("receipt", MediaType.IMAGE, utility = true)
        val sea = MomentMedia("sea", MediaType.IMAGE)
        val moments = listOf(
            moment("A", "2026-10-01T09:00:00+08:00").copy(media = listOf(receipt, sea)),
            moment("B", "2026-10-02T09:00:00+08:00").copy(media = listOf(receipt.copy(uid = "screenshot"))),
        )

        assertEquals(listOf("sea"), coversOf(ferry, moments, trips).map { it.uid })
    }

    private fun trip(id: String, start: String, end: String, waypoints: List<Waypoint> = emptyList()) =
        TripRecord(id, id, start, end, waypoints = waypoints, createdAt = "$start", updatedAt = "$start")

    @Test
    fun `a trip's public check-ins with a place are stops on its way, among its marks by time`() {
        val json = kotlinx.serialization.json.Json
        val collection = json.parseToJsonElement(
            """{"uid":"T","kind":"trip","title":"海边","span":{"start":"2026-10-29T08:00:00+08:00","end":"2026-10-30"},
               "include":[],"exclude":[],"covers":[],"createdAt":"2026-10-29","updatedAt":"2026-10-29"}""",
        ) as kotlinx.serialization.json.JsonObject
        fun entry(uid: String, at: String, place: String, parts: String, extra: String = "") = json.parseToJsonElement(
            """{"uid":"$uid","at":"$at","place":"$place","parts":$parts$extra,"createdAt":"$at","updatedAt":"$at"}""",
        ) as kotlinx.serialization.json.JsonObject
        val checkin = """{"checkin":{"v":1,"venue":"x","source":{"app":"phone"}}}"""
        val entries = listOf(
            entry("A", "2026-10-29T07:00:00+08:00", "home", checkin),
            entry("B", "2026-10-29T09:00:00+08:00", "station", checkin),
            entry("M", "2026-10-29T12:00:00+08:00", "mark", "{}"),
            entry("C", "2026-10-29T10:00:00+08:00", "secret", checkin, ""","visibility":"private""""),
            entry("D", "2026-10-30T15:00:00+08:00", "beach", checkin),
        )

        assertEquals(listOf("station", "mark", "beach"), cc.hyoban.moments.data.spec.Entries.toTrip(collection, entries).waypoints.map { it.place })
    }

    @Test
    fun `a trip known to the minute holds only what lies between its start and end`() {
        val trip = TripRecord(
            "t", "t", "2026-04-02", "2026-04-07",
            startAt = "2026-04-02T08:30:00+08:00", endAt = "2026-04-07T21:10:00+08:00",
            createdAt = "2026-04-02", updatedAt = "2026-04-02",
        )
        val trips = listOf(trip)

        assertNull(tripOf(moment("before", "2026-04-02T07:00:00+08:00"), trips))
        assertEquals("t", tripOf(moment("after start", "2026-04-02T09:00:00+08:00"), trips)?.id)
        assertEquals("t", tripOf(moment("a day", "2026-04-07"), trips)?.id)
        assertNull(tripOf(moment("after end", "2026-04-07T22:00:00+08:00"), trips))
    }

    @Test
    fun `a trip's bills are those paid in its span, as the site counts its public ones`() {
        val trip = TripRecord(
            "t", "t", "2026-04-02", "2026-04-07",
            startAt = "2026-04-02T08:30:00+08:00",
            createdAt = "2026-04-02", updatedAt = "2026-04-02",
        )
        fun bill(id: String, at: String, kind: TxnKind = TxnKind.EXPENSE) =
            Txn(id, "b", kind, OffsetDateTime.parse(at).toInstant().toEpochMilli(), 8 * 60, 100, "CNY")
        val before = bill("before", "2026-04-02T07:00:00+08:00")
        val during = bill("during", "2026-04-03T12:00:00+08:00").copy(visibility = "public")
        val hidden = bill("hidden", "2026-04-03T13:00:00+08:00")
        val moved = bill("moved", "2026-04-04T12:00:00+08:00", TxnKind.TRANSFER)
        val after = bill("after", "2026-04-08T09:00:00+08:00")

        assertEquals("2026-04-03T12:00:00+08:00", during.at)
        assertNull(tripOfTxn(before, listOf(trip)))
        assertEquals("t", tripOfTxn(during, listOf(trip)))
        assertEquals(listOf("during"), txnsOfTrip(Ledger(txns = listOf(before, during, hidden, moved, after)), trip).map { it.id })
    }

    @Test
    fun `a planned Moment and a plan stay planned through their entry and collection`() {
        val planned = moment("B", "2026-12-25T08:30:00+09:00").copy(planned = true)

        val entry = cc.hyoban.moments.data.spec.Entries.fromMoment(planned)
        assertEquals(true, cc.hyoban.moments.data.spec.Entries.toMoment(entry)?.planned)
        assertNull(cc.hyoban.moments.data.spec.Entries.toMoment(cc.hyoban.moments.data.spec.Entries.fromMoment(planned.copy(planned = null)))?.planned)

        val collection = cc.hyoban.moments.data.spec.Entries.collectionOf(trip("tokyo", "2026-12-24", "2026-12-29"))
        val plan = cc.hyoban.moments.data.spec.Entries.editCollection(
            collection, "东京", "2026-12-24", "2026-12-29", cc.hyoban.moments.data.spec.Visibility.PUBLIC, "2026-11-19T12:00:00+08:00", planned = true,
        )
        assertEquals(true, cc.hyoban.moments.data.spec.Entries.isPlanned(plan))
        assertEquals(true, cc.hyoban.moments.data.spec.Entries.toTrip(plan, emptyList()).planned)
        val taken = cc.hyoban.moments.data.spec.Entries.editCollection(plan, "东京", "2026-12-24", "2026-12-29", cc.hyoban.moments.data.spec.Visibility.PUBLIC, "2026-12-30T12:00:00+08:00", planned = false)
        assertNull(cc.hyoban.moments.data.spec.Entries.toTrip(taken, emptyList()).planned)
    }

    private fun moment(uid: String, occurredAt: String, place: String? = null, trip: String? = null) =
        MomentRecord(uid, occurredAt, listOfNotNull(place), "", trip = trip, createdAt = occurredAt, updatedAt = occurredAt)
}
