package cc.hyoban.moments

import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.PlanItem
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanPlace
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.compose.PlaceChoice
import cc.hyoban.moments.ui.trips.PlanChange
import cc.hyoban.moments.ui.trips.PlanChat
import cc.hyoban.moments.ui.trips.PlanChats
import cc.hyoban.moments.ui.trips.PlanSpot
import cc.hyoban.moments.ui.trips.PlanStep
import cc.hyoban.moments.ui.trips.PlanSuggestion
import cc.hyoban.moments.ui.trips.PlanTurn
import cc.hyoban.moments.ui.trips.DayEntry
import cc.hyoban.moments.ui.trips.planDays
import cc.hyoban.moments.ui.trips.findSpot
import cc.hyoban.moments.ui.trips.changesNothing
import cc.hyoban.moments.ui.trips.PlanMatch
import cc.hyoban.moments.ui.trips.PlanTarget
import cc.hyoban.moments.ui.trips.matchPlan
import cc.hyoban.moments.ui.trips.outcomeOf
import cc.hyoban.moments.ui.trips.dayWays
import cc.hyoban.moments.ui.trips.totalOf
import cc.hyoban.moments.ui.trips.lookedUpWay
import cc.hyoban.moments.ui.trips.isAhead
import cc.hyoban.moments.ui.trips.planRecords
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.data.things.checkinAsMoment
import cc.hyoban.moments.data.things.checkinOf
import cc.hyoban.moments.data.things.editedEntry
import cc.hyoban.moments.data.ai.WayEnd
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A plan's conversation kept on the device and read back as it was. */
class PlanChatsTest {
    private val record = MomentRecord(uid = "m-1", occurredAt = "2026-12-25T08:30:00+09:00", places = listOf("p-1"), text = "去寺里", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")

    private fun chat() = PlanChat("t-1").apply {
        draft = "还没发的"
        memory += listOf(JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("圣诞去东京"))))
        turns += PlanTurn.Asked("圣诞去东京")
        turns += PlanTurn.Answered().apply {
            text = "排好了"
            thoughtFor = 4
            thought(0, "先找寺")
            looked(PlanLookup.Place("a", "浅草寺", Fix(35.71, 139.79), found = listOf(FoundPlace("浅草寺", null, Fix(35.71, 139.79)))))
            thought(1, "再排晚上")
            suggestions += PlanSuggestion(PlanItem("2026-12-24", "18:00", "看灯", PlanPlace("表参道", Fix(35.66, 139.71)))).also {
                it.spot = PlanSpot.At(PlaceChoice.New("表参道", Fix(35.66, 139.71)), "表参道")
                it.added = "m-9"
            }
            suggestions += PlanSuggestion(PlanItem("2026-12-25", "09:30", "晚点去寺里", null, "m-1"), record, "浅草寺").also { it.spot = PlanSpot.At(PlaceChoice.Known("p-1"), "浅草寺") }
            suggestions += PlanSuggestion(PlanItem("", null, "", null, "m-1", delete = true), record, "浅草寺").also { it.spot = PlanSpot.Finding }
        }
    }

    @Test
    fun readsBackWhatWasKept() {
        val saved = Json.parseToJsonElement(PlanChats.chatJson(chat()).toString()) as JsonObject
        val back = PlanChats.chatOf("t-1", saved)
        assertEquals("还没发的", back.draft)
        assertEquals(1, back.memory.size)
        assertEquals("圣诞去东京", (back.turns[0] as PlanTurn.Asked).text)
        val answer = back.turns[1] as PlanTurn.Answered
        assertEquals("排好了", answer.text)
        assertEquals(false, answer.stopped)
        assertEquals(4, answer.thoughtFor)
        // Its thinking and lookups in the order they came.
        assertEquals(listOf("Thought", "Look", "Thought"), answer.steps.map { if (it is PlanStep.Thought) "Thought" else "Look" })
        assertEquals(1, (answer.lookups.single() as PlanLookup.Place).found?.size)
        assertEquals(listOf(PlanChange.Add, PlanChange.Change, PlanChange.Delete), answer.suggestions.map { it.kind })
        assertEquals("m-9", answer.suggestions[0].added)
        assertEquals("表参道", (answer.suggestions[0].spot as PlanSpot.At).name)
        assertEquals("去寺里", answer.suggestions[1].target?.text)
        assertEquals(PlaceChoice.Known("p-1"), (answer.suggestions[1].spot as PlanSpot.At).choice)
        // A place still being looked for is looked for again.
        assertEquals(PlanSpot.Finding, answer.suggestions[2].spot)
        assertNull(answer.suggestions[2].added)
    }

    @Test
    fun laysOutEachDayAsItWouldBe() {
        val trip = TripRecord("t-1", "东京", "2026-12-24", "2026-12-26", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")
        val places = mapOf("p-1" to Place("浅草寺", 35.71, 139.79), "p-2" to Place("酒店", 35.69, 139.70))
        fun moment(uid: String, at: String, text: String, place: String?) =
            MomentRecord(uid = uid, occurredAt = at, places = listOfNotNull(place), text = text, planned = true, createdAt = at, updatedAt = at)
        val hotel = moment("m-0", "2026-12-25T07:30:00+09:00", "出发", "p-2")
        val temple = moment("m-1", "2026-12-25T08:30:00+09:00", "去寺里", "p-1")
        val sunset = moment("m-2", "2026-12-25T16:20:00+09:00", "看日落", null)
        val other = moment("m-3", "2026-12-26T10:00:00+09:00", "别的日子", null)
        val suggestions = listOf(
            PlanSuggestion(PlanItem("2026-12-25", "09:30", "晚点去寺里", null, "m-1"), temple, "浅草寺").also { it.spot = PlanSpot.At(PlaceChoice.Known("p-1"), "浅草寺") },
            PlanSuggestion(PlanItem("", null, "", null, "m-2", delete = true), sunset),
            PlanSuggestion(PlanItem("2026-12-25", "19:00", "东京塔", PlanPlace("东京塔", Fix(35.66, 139.75)))).also { it.spot = PlanSpot.At(PlaceChoice.New("东京塔", Fix(35.66, 139.75)), "东京塔") },
        )
        val days = planDays(suggestions, trip, listOf(hotel, temple, sunset, other), emptyList(), listOf(trip), places)
        // Every day of the plan, those it leaves alone too, each record it doesn't touch kept, the rest at their times.
        assertEquals(listOf("2026-12-24", "2026-12-25", "2026-12-26"), days.map { it.first })
        assertEquals(emptyList(), days[0].second)
        assertEquals(listOf("别的日子"), days[2].second.map { (it as DayEntry.Kept).text })
        val entries = days[1].second
        assertEquals(listOf("07:30", "09:30", "16:20", "19:00"), entries.map { it.time })
        assertEquals("出发", (entries[0] as DayEntry.Kept).text)
        assertEquals(Fix(35.71, 139.79), entries[1].fix)
        assertEquals(PlanChange.Delete, (entries[2] as DayEntry.Suggested).suggestion.kind)
    }

    @Test
    fun takesAWayTheModelLookedUp() {
        val hotel = Fix(35.6900, 139.7000)
        val temple = Fix(35.7148, 139.7967)
        // The model's ends a little off the places' own, by the same mode.
        val looked = PlanLookup.Way("w", WayEnd("酒店", Fix(35.6920, 139.7010)), WayEnd("浅草寺", Fix(35.7140, 139.7960)), Routers.By.DRIVE, Routers.Travel(1440, 11200), done = true)
        assertEquals(looked, lookedUpWay(listOf(looked), hotel, temple, Routers.By.DRIVE))
        // Another mode, the other way round, or ends too far: not that way.
        assertNull(lookedUpWay(listOf(looked), hotel, temple, Routers.By.WALK))
        assertNull(lookedUpWay(listOf(looked), temple, hotel, Routers.By.DRIVE))
        assertNull(lookedUpWay(listOf(looked), hotel, Fix(35.66, 139.75), Routers.By.DRIVE))
        // One that failed is asked again.
        assertNull(lookedUpWay(listOf(looked.copy(failed = true)), hotel, temple, Routers.By.DRIVE))
    }

    @Test
    fun aCheckinStillToComeIsPartOfThePlan() {
        val now = java.time.Instant.parse("2026-11-20T00:00:00Z")
        assertEquals(true, isAhead("2026-12-21T08:00:00+08:00", now))
        assertEquals(false, isAhead("2026-11-01T08:00:00+08:00", now))
        val trip = TripRecord("t-1", "香港", "2026-12-21", "2026-12-26", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")
        val checkins = listOf(Checkin("c-ahead", "2026-12-21T08:00:00+08:00", "老家", place = "p-1"))
        val records = planRecords(trip, emptyList(), checkins, listOf(trip), mapOf("p-1" to Place("老家", 35.68, 139.77)), now)
        // One still to come has an id, to be changed by.
        assertEquals("c-ahead", records.single().id)
    }

    @Test
    fun aCheckinMovedToAnotherTimeStaysTheSameCheckin() {
        val entry = cc.hyoban.moments.data.spec.Spec.json.parseToJsonElement(
            """{"uid":"c-1","at":"2026-12-21T08:00:00+08:00","place":"p-1","parts":{"checkin":{"v":1,"venue":"老家","source":{"app":"phone"}}},"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}""",
        ) as JsonObject
        val opened = checkinAsMoment(entry)!!
        // As the plan's change makes it: its time moved, its words none, its place the same.
        val saved = editedEntry(opened.copy(occurredAt = "2026-12-21T07:00:00+08:00", text = ""), "老家", placeChanged = false)
        val checkin = checkinOf(saved)!!
        assertEquals("c-1", checkin.uid)
        assertEquals("2026-12-21T07:00:00+08:00", checkin.at)
        assertEquals("老家", checkin.venue)
        assertEquals("p-1", checkin.place)
    }

    @Test
    fun anAnswerCutOffComesBackStopped() {
        val chat = chat().apply { thinking = true }
        val back = PlanChats.chatOf("t-1", Json.parseToJsonElement(PlanChats.chatJson(chat).toString()) as JsonObject)
        assertEquals(true, (back.turns[1] as PlanTurn.Answered).stopped)
    }

    @Test
    fun takesWhatThePlanHoldsForWhatItIs() {
        val hotel = Fix(35.6900, 139.7000)
        val plan = listOf(
            PlanTarget("m-1", "2026-12-24", "09:00", "退房出门", "新宿的酒店", hotel),
            PlanTarget("m-2", "2026-12-24", "22:50", "回酒店拿行李", "新宿的酒店", hotel),
            PlanTarget("c-1", "2026-12-21", "08:00", "", "老家", Fix(35.68, 139.77), checkin = true),
        )
        // Listed again as it is: nothing to do.
        assertEquals(PlanMatch.Same("m-2"), matchPlan(PlanItem("2026-12-24", "22:50", "回酒店拿行李", PlanPlace("新宿的酒店", hotel)), plan))
        // Added again where a record is: a change of the one nearest its time, a place named otherwise but just there too.
        assertEquals(PlanMatch.Edits("m-2"), matchPlan(PlanItem("2026-12-24", "23:30", "回酒店拿行李", PlanPlace("新宿的酒店本馆", Fix(35.6902, 139.7002))), plan))
        assertEquals(PlanMatch.Edits("m-1"), matchPlan(PlanItem("2026-12-24", "09:00", "睡到九点再退房", PlanPlace("新宿的酒店", hotel)), plan))
        // One already taken by another item of the list is passed over.
        assertEquals(PlanMatch.Edits("m-1"), matchPlan(PlanItem("2026-12-24", "23:30", "回酒店", PlanPlace("新宿的酒店", hotel)), plan, claimed = setOf("m-2")))
        // A check-in still to come, moved: its words are its place's, so only the time counts.
        assertEquals(PlanMatch.Edits("c-1"), matchPlan(PlanItem("2026-12-21", "07:00", "七点出发", PlanPlace("老家", null)), plan))
        assertEquals(PlanMatch.Same("c-1"), matchPlan(PlanItem("2026-12-21", "08:00", "出发", null, edits = "c-1"), plan))
        // Somewhere else, another day, or a deletion: as written.
        assertEquals(PlanMatch.AsWritten, matchPlan(PlanItem("2026-12-24", "12:00", "太平山顶", PlanPlace("太平山顶", Fix(22.2759, 114.1455))), plan))
        assertEquals(PlanMatch.AsWritten, matchPlan(PlanItem("2026-12-23", "21:30", "入住", PlanPlace("新宿的酒店", hotel)), plan))
        assertEquals(PlanMatch.AsWritten, matchPlan(PlanItem("", null, "", null, "m-1", delete = true), plan))
        assertEquals(PlanMatch.AsWritten, matchPlan(PlanItem("2026-12-24", "10:00", "晚点退房", null, edits = "m-1"), plan))
    }

    @Test
    fun tellsWhatCameOfAList() {
        val answered = chat().turns[1] as PlanTurn.Answered
        assertEquals(
            "The owner applied 1 of 3: added 2026-12-24 18:00 表参道 as entry m-9. The rest were not applied.",
            outcomeOf(answered.suggestions),
        )
        answered.suggestions.forEach { it.added = it.added ?: "m-1" }
        assertEquals(
            "The owner applied 3 of 3: added 2026-12-24 18:00 表参道 as entry m-9; changed entry m-1; removed entry m-1.",
            outcomeOf(answered.suggestions),
        )
        assertEquals(null, outcomeOf(emptyList()))
    }

    @Test
    fun addsUpADaysDriving() {
        val a = Fix(35.0, 139.0)
        val b = Fix(35.3, 139.0)
        val c = Fix(35.9, 139.5)
        val gone = PlanSuggestion(PlanItem("", null, "", null, "m-1", delete = true), record, null)
        val walked = PlanSuggestion(PlanItem("2026-12-21", "14:00", "走一走", PlanPlace("某寺", c), by = Routers.By.WALK))
        val ways = dayWays(
            listOf(
                DayEntry.Kept("08:00", "出发", null, a),
                DayEntry.Suggested(gone, Fix(35.1, 139.0)),
                DayEntry.Kept("12:00", "吃鱼", null, b),
                DayEntry.Kept("13:00", "没有地点", null, null),
                DayEntry.Suggested(walked, c),
            ),
        )
        // A deletion and a record without a place are passed over; each way by how its end is reached.
        assertEquals(listOf(Triple(a, b, Routers.By.DRIVE), Triple(b, c, Routers.By.WALK)), ways)
        assertEquals(Routers.Travel(5_400, 120_000), totalOf(listOf(Routers.Travel(3_600, 100_000), Routers.Travel(1_800, 20_000))))
        assertNull(totalOf(listOf(Routers.Travel(3_600, 100_000), null)))
        assertNull(totalOf(emptyList()))
    }

    @Test
    fun takesAPlaceKeptByThatNameBeforeAMap() = kotlinx.coroutines.runBlocking {
        val places = mapOf("home" to Place("老家", 35.6812, 139.7671), "station" to Place("东站", 35.6900, 139.7700))
        // Nothing asked of the map: the search would fail if it were.
        val search = cc.hyoban.moments.data.local.PlaceSearch({ "https://photon.invalid" })
        val spot = findSpot(PlanItem("2026-12-26", "16:20", "到家", PlanPlace("老家", Fix(35.6812, 139.7671))), places, search)
        assertEquals(PlanSpot.At(PlaceChoice.Known("home"), "老家"), spot)
    }

    @Test
    fun aChangeToWhatItIsIsNothing() {
        val back = MomentRecord(uid = "m-5", occurredAt = "2026-12-26T16:20:00+08:00", places = listOf("station"), text = "到家", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")
        fun change(place: String) = PlanSuggestion(PlanItem("2026-12-26", "16:20", "到家", PlanPlace("老家", null), edits = "m-5"), back, "东站")
            .also { it.spot = PlanSpot.At(PlaceChoice.Known(place), place) }
        assertEquals(true, changesNothing(change("station")))
        assertEquals(false, changesNothing(change("home")))
    }
}
