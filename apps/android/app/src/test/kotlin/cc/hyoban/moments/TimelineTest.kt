package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.feed.Charge
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.DayTrack
import cc.hyoban.moments.ui.feed.dayItems
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
                val chronological = dayItems(moments, emptyList(), charges).reversed()
                assertEquals(expected, chronological.filterIsInstance<DayItem.Moment>().map { it.moment.uid })
                if (charges.isNotEmpty()) assertEquals(charge, (chronological[1] as DayItem.ChargeItem).charge)
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
            listOf(bill("red-packet", "16:52:00"), bill("battery", "20:09:00"), bill("tea", "19:46:00")),
            checkins = listOf(checkin),
            oldestFirst = true,
        )
        val order = items.flatMap { item ->
            when (item) {
                is DayItem.Bills -> item.txns.map { it.id }
                is DayItem.CheckinItem -> listOf(item.checkin.uid)
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
            checkins = listOf(Checkin("cafe", "2026-01-02T10:30:00+08:00", "Cafe")),
            oldestFirst = true,
            day = "2026-01-02",
            track = track,
        )
        assertEquals(listOf("track", "walk", "track", "cafe", "track"), items.map {
            when (it) {
                is DayItem.Track -> "track"
                is DayItem.Moment -> it.moment.uid
                is DayItem.CheckinItem -> it.checkin.uid
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
}
