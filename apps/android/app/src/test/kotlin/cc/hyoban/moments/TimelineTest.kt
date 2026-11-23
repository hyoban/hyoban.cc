package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.plugins.Charge
import cc.hyoban.moments.plugins.BillRecord
import cc.hyoban.moments.plugins.ChargeRecord
import cc.hyoban.moments.plugins.CheckinAnchor
import cc.hyoban.moments.plugins.DriveRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.DayTrack
import cc.hyoban.moments.ui.feed.dayItems
import cc.hyoban.moments.plugins.DriveDay
import cc.hyoban.moments.ui.feed.chatItems
import cc.hyoban.moments.ui.feed.ChatItem
import cc.hyoban.moments.ui.feed.RecordLine
import cc.hyoban.moments.plugins.drivesOf
import cc.hyoban.moments.plugins.placeCharges
import kotlin.test.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TimelineTest {
    @Test
    fun `a trip reads its Moments from morning to evening regardless of input order or other records`() {
        val morning = moment("morning", "08:00:00")
        val afternoon = moment("afternoon", "14:00:00")
        val evening = moment("evening", "20:00:00")
        val charge = Charge("charge", "2026-01-02T12:00:00+08:00", true, 20.0, 15)
        val expected = listOf("morning", "afternoon", "evening")

        for (moments in listOf(listOf(morning, afternoon, evening), listOf(evening, afternoon, morning), listOf(afternoon, morning, evening))) {
            for (charges in listOf(emptyList(), listOf(charge))) {
                val chronological = dayItems(moments, charges.map { ChargeRecord(it, null) }).reversed()
                assertEquals(expected, chronological.filterIsInstance<DayItem.Moment>().map { it.moment.uid })
                if (charges.isNotEmpty()) assertEquals(charge, ((chronological[1] as DayItem.Record).record as ChargeRecord).charge)
            }
        }
    }

    @Test
    fun `a day read from its morning reads the bills next to each other from the morning too`() {
        val at = { time: String -> java.time.OffsetDateTime.parse("2026-01-02T$time+08:00").toInstant().toEpochMilli() }
        val bill = { id: String, time: String -> Txn(id, "book", TxnKind.EXPENSE, at(time), 480, 100, "CNY") }
        val checkin = Checkin("checkin", "2026-01-02T19:43:00+08:00", "古茗")
        val items = dayItems(
            emptyList(),
            listOf(bill("red-packet", "16:52:00"), bill("battery", "20:09:00"), bill("tea", "19:46:00")).map { BillRecord(it, Ledger()) },
            anchors = listOf(CheckinAnchor(checkin)),
            oldestFirst = true,
        )
        val order = items.flatMap { item ->
            when (item) {
                is DayItem.Record -> listOf((item.record as BillRecord).txn.id)
                is DayItem.Anchor -> listOf(item.anchor.uid)
                else -> emptyList()
            }
        }
        assertEquals(listOf("red-packet", "checkin", "tea", "battery"), order)
    }

    @Test
    fun `a day's footprints are cut at each record with a time, a piece between the two it moved between`() {
        val at = { time: String -> java.time.OffsetDateTime.parse("2026-01-02T$time+08:00").toInstant().toEpochMilli() }
        // Walking north 10 m every 5 s from 09:00 to 11:00, past a Moment at 10:00 and a check-in at 10:30.
        val start = at("09:00:00")
        val points = (0..1440).map { i -> TrackPoint(start + i * 5_000L, 31.0 + i * 0.00009, 121.0, 5.0) }
        val track = assertNotNull(DayTrack.of(points))
        val items = dayItems(
            listOf(moment("walk", "10:00:00")),
            emptyList(),
            anchors = listOf(CheckinAnchor(Checkin("cafe", "2026-01-02T10:30:00+08:00", "Cafe"))),
            oldestFirst = true,
            day = "2026-01-02",
            track = track,
        )
        assertEquals(listOf("track", "walk", "track", "cafe", "track"), items.map {
            when (it) {
                is DayItem.Track -> "track"
                is DayItem.Moment -> it.moment.uid
                is DayItem.Anchor -> it.anchor.uid
                else -> "?"
            }
        })
        val pieces = items.filterIsInstance<DayItem.Track>().map { it.piece }
        assertEquals(listOf(start, at("10:00:00"), at("10:30:00")), pieces.map { it.start })
        assertEquals(track.metres, pieces.sumOf { it.metres }, track.metres * 0.01)
        assertEquals(2, (pieces[0].metres / pieces[1].metres).roundToInt())
        // Newest first, the pieces read the other way round, still between the same two.
        val newest = dayItems(listOf(moment("walk", "10:00:00")), emptyList(), day = "2026-01-02", track = track)
        assertEquals(listOf("track", "walk", "track"), newest.map { if (it is DayItem.Track) "track" else (it as DayItem.Moment).moment.uid })
        assertEquals(at("10:00:00"), (newest.first() as DayItem.Track).piece.start)
    }

    @Test
    fun `a piece of the footprints that barely moved lists nothing`() {
        val start = java.time.OffsetDateTime.parse("2026-01-02T09:00:00+08:00").toInstant().toEpochMilli()
        // 1 km walked by 09:10, then lying still until a Moment at 10:00 and after it.
        val walk = (0..120).map { i -> TrackPoint(start + i * 5_000L, 31.0 + i * 0.00009, 121.0, 5.0) }
        val still = (1..1000).map { i -> TrackPoint(walk.last().time + i * 5_000L, walk.last().latitude, 121.0, 5.0) }
        val items = dayItems(listOf(moment("rest", "10:00:00")), emptyList(), oldestFirst = true, day = "2026-01-02", track = DayTrack.of(walk + still))
        assertEquals(2, items.size)
        assertIs<DayItem.Track>(items.first())
    }

    private fun moment(uid: String, time: String): MomentRecord {
        val at = "2026-01-02T$time+08:00"
        return MomentRecord(uid, at, emptyList(), "", createdAt = at, updatedAt = at)
    }

    @Test
    fun `a charge is one line with the bill paid at its time, where that bill goes`() {
        val at = { time: String -> java.time.OffsetDateTime.parse("2026-11-14T$time+08:00").toInstant().toEpochMilli() }
        val fast = Charge("fast", "2026-11-14T10:48+08:00", true, 42.5, 17)
        val slow = Charge("slow", "2026-11-14T22:00+08:00", false, 69.6, 600)
        val alone = Charge("alone", "2026-11-14T13:03+08:00", true, 7.5, 15)
        val paidFast = Txn("bill", "book", TxnKind.EXPENSE, at("10:48:00"), 480, 5310, "CNY")
        val paidAlone = Txn("bill2", "book", TxnKind.EXPENSE, at("13:03:00"), 480, 560, "CNY")
        val coffee = Txn("coffee", "book", TxnKind.EXPENSE, at("15:00:00"), 480, 3000, "CNY")
        // The check-in at the charger is at the same minute; the overnight charge at home has nothing.
        val (loose, linked, paid) = placeCharges(listOf(slow, alone, fast), listOf(paidFast, paidAlone, coffee), { null }, mapOf(at("10:48:00") to "station"))
        assertEquals(setOf("bill", "bill2"), paid)
        assertEquals(listOf(fast.copy(bill = paidFast)), linked["station"])
        assertEquals(listOf(alone.copy(bill = paidAlone), slow), loose["2026-11-14"])
        // A bill linked to a Moment by hand takes its charge along.
        val (_, byLink, _) = placeCharges(listOf(alone), listOf(paidAlone), { if (it.id == "bill2") "moment" else null }, emptyMap())
        assertEquals("moment", byLink.keys.single())
    }

    @Test
    fun `a day the car moved ends with what it drove, among the day's records`() {
        val month = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"month":"2026-11","days":[{"date":"2026-11-10","distanceMeters":0,"durationSeconds":0,"energyWh":2000},{"date":"2026-11-14","distanceMeters":381000,"durationSeconds":19500,"energyWh":74500}]}""",
        ) as kotlinx.serialization.json.JsonObject
        val drives = drivesOf(listOf(month)) { true }
        assertEquals(listOf("2026-11-14"), drives.keys.toList(), "a day only parked has no line")
        val drive = drives.getValue("2026-11-14")
        assertEquals(DriveDay("2026-11-14", 381.0, 325, 74.5), drive)
        val items = dayItems(listOf(moment("lunch", "12:00:00")), listOf(DriveRecord(drive)), oldestFirst = true, day = "2026-11-14")
        assertIs<DriveRecord>((items.last() as DayItem.Record).record)
        val chats = chatItems(items)
        assertIs<ChatItem.Moment>(chats.first())
        assertEquals("drive-2026-11-14", (chats.last() as ChatItem.Records).lines.single().key)
    }
}
