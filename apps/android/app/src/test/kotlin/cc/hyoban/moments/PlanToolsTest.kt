package cc.hyoban.moments

import cc.hyoban.moments.data.ai.FoundNote
import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.NoteSearch
import cc.hyoban.moments.data.ai.PlanAssistant
import cc.hyoban.moments.data.ai.PlanContext
import cc.hyoban.moments.data.ai.PlanLookups
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.plugins.NoteSearchTool
import cc.hyoban.moments.plugins.PhonePlugins
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tools 和 AI 规划 offers the model come from plugins, and are offered exactly as they were before they did. */
class PlanToolsTest {
    init { Plugins.install(PhonePlugins.all) }

    @Test
    fun theToolsComeFromThePlugins() {
        assertEquals(listOf("find_place", "route_time", "search_notes"), Plugins.registry.all(Points.planTools).map { it.name })
        // Every device's alone bring the lookups, not Xiaohongshu's notes.
        assertEquals(listOf("find_place", "route_time"), cc.hyoban.moments.plugins.Registry(Plugins.shared).all(Points.planTools).map { it.name })
    }

    @Test
    fun offersTheSameToolsWithAndWithoutTheNoteSearch() = kotlinx.coroutines.runBlocking {
        val with = firstRequest(signedIn = true)
        val without = firstRequest(signedIn = false)
        assertEquals(TOOLS, with.first)
        // Without it, the same tools but search_notes, byte for byte.
        val all = Json.parseToJsonElement(TOOLS) as JsonArray
        assertEquals(JsonArray(all.dropLast(1)).toString(), without.first)
        // The lookups' paragraph of the instructions, exactly as it was, the notes' part only with them.
        assertEquals(true, with.second.contains("\n\n" + LOOKUPS + NOTES + "Ask for all the lookups"))
        assertEquals(true, without.second.contains("\n\n" + LOOKUPS + "Ask for all the lookups"))
        assertEquals(with.second.replace(NOTES, ""), without.second)
    }

    @Test
    fun aPhoneSignedOutOrWithoutXiaohongshuIsNotOfferedIt() = kotlinx.coroutines.runBlocking {
        // The phone's own tool, read from a container with no note search (as asked without one).
        val sent = mutableListOf<String>()
        PlanAssistant("https://example.test/v1", "key", "model", client(sent)).ask("规划", PlanContext("东京", "2026-12-24", "2026-12-29"), lookups)
        val tools = Json.parseToJsonElement(sent.single()).jsonObject["tools"].toString()
        assertEquals(JsonArray((Json.parseToJsonElement(TOOLS) as JsonArray).dropLast(1)).toString(), tools)
    }

    /** The first request's tools and system prompt, with Xiaohongshu [signedIn] or not. */
    private suspend fun firstRequest(signedIn: Boolean): Pair<String, String> {
        val sent = mutableListOf<String>()
        val search = object : NoteSearch {
            override val signedIn = androidx.compose.runtime.mutableStateOf(signedIn)
            override suspend fun search(query: String) = emptyList<FoundNote>()
        }
        val tools = { Plugins.registry.all(Points.planTools).map { if (it.name == "search_notes") NoteSearchTool { search } else it } }
        PlanAssistant("https://example.test/v1", "key", "model", client(sent), tools = tools)
            .ask("东京塔怎么样", PlanContext("东京", "2026-12-24", "2026-12-29", now = "2026-11-19T22:00+08:00 Asia/Shanghai", notes = "自驾"), lookups)
        val body = Json.parseToJsonElement(sent.first()).jsonObject
        val system = ((body["messages"] as JsonArray)[0].jsonObject["content"] as JsonPrimitive).content
        return body["tools"].toString() to system
    }

    private fun client(sent: MutableList<String>) = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = okio.Buffer()
        chain.request().body!!.writeTo(buffer)
        sent += buffer.readUtf8()
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"choices":[{"message":{"role":"assistant","content":"好。"}}]}""".toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val lookups = object : PlanLookups {
        override suspend fun places(name: String, near: Fix) = emptyList<FoundPlace>()
        override suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel? = null
    }

    private companion object {
        /** The tools as the planner offered them before they came from plugins, with the note search. */
        const val TOOLS = """[{"type":"function","function":{"name":"suggest_plan","description":"Show the owner things to add to the plan, or planned entries to change or remove; each takes effect only if they tap it.","parameters":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"edit":{"type":"string","description":"The id of a planned entry this changes or removes; leave out to add a new one."},"delete":{"type":"boolean","description":"True to remove the planned entry named by edit; nothing else is needed then."},"date":{"type":"string","description":"The day, YYYY-MM-DD, within the plan. Needed unless deleting."},"time":{"type":"string","description":"The local time, HH:mm."},"text":{"type":"string","description":"A short note in the owner's voice. Needed when adding; when changing, leave out to keep the entry's own."},"by":{"type":"string","description":"How the owner gets here from the place before it that day.","enum":["drive","walk","bike","train"]},"place":{"type":"object","properties":{"name":{"type":"string"},"lat":{"type":"number"},"lng":{"type":"number"}},"required":["name"]}}}}},"required":["items"]}}},{"type":"function","function":{"name":"find_place","description":"Look a place up by name near where it should be: what map services call it there, with addresses and coordinates.","parameters":{"type":"object","properties":{"name":{"type":"string"},"lat":{"type":"number","description":"About where it should be."},"lng":{"type":"number"}},"required":["name","lat","lng"]}}},{"type":"function","function":{"name":"route_time","description":"How long and how far it is from one place to another by road, on foot, by bike or by train, without timetables.","parameters":{"type":"object","properties":{"from":{"type":"object","description":"Where the way starts.","properties":{"name":{"type":"string"},"lat":{"type":"number"},"lng":{"type":"number"}},"required":["name","lat","lng"]},"to":{"type":"object","description":"Where it ends.","properties":{"name":{"type":"string"},"lat":{"type":"number"},"lng":{"type":"number"}},"required":["name","lat","lng"]},"by":{"type":"string","enum":["drive","walk","bike","train"]}},"required":["from","to","by"]}}},{"type":"function","function":{"name":"search_notes","description":"Search Xiaohongshu for people's notes: the top results' titles, authors and likes, and the text of the first few.","parameters":{"type":"object","properties":{"query":{"type":"string","description":"What to search, as people search Xiaohongshu, such as 东京 拉面 推荐."}},"required":["query"]}}}]"""

        /** What the instructions said of find_place and route_time. */
        const val LOOKUPS = "Before suggesting, check what you are unsure of: find_place tells whether a place is there and where, and route_time how long a way takes by road, on foot, by bike or by train, so each day fits. route_time knows no timetables: by train it gives the line's running time, and within a city public transport takes about as long as driving, with waiting. Use the coordinates find_place gives for the places you suggest. "

        /** And of search_notes, while it was offered. */
        const val NOTES = "search_notes searches Xiaohongshu, where people write up places they went, and reads the top notes: use it, in Chinese words as people there search, for what a place is like, what to eat, when to go or what to skip, then check the places it names with find_place. Many notes are ads, paid or a shop's own, without saying so: treat a note as likely one when it reads like copy, praises without a flaw, gives a discount, a group-buy, a code or a way to book through the author, tells readers to message or comment for details, is tagged 合作, 赞助 or 广告, is written by the shop or brand itself, or matches other notes word for word. Trust notes with a person's own details, prices paid, waits and complaints, and what several unrelated authors agree on; never lean on one note alone, leave out what only likely ads praise, and when a place is mostly promoted, say so. "
    }
}
