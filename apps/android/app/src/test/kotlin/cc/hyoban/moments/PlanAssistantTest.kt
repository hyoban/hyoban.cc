package cc.hyoban.moments

import cc.hyoban.moments.plugins.keptStop
import cc.hyoban.moments.data.ai.PlanAnswerException
import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.PlanAssistant
import cc.hyoban.moments.data.ai.PlanContext
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanLookups
import cc.hyoban.moments.data.trips.Routers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import cc.hyoban.moments.data.ai.PlanProgress
import cc.hyoban.moments.data.ai.PlanStream
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ui.trips.planRecords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What a model's answer suggests, and what of the owner's places it is told. */
class PlanAssistantTest {
    init { cc.hyoban.moments.plugins.Plugins.install(cc.hyoban.moments.plugins.PhonePlugins.all) }

    private fun message(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun readsSuggestionsFromTheToolCall() {
        val arguments = """{"reply":"先排了第一天","items":[
            {"date":"2026-12-24","time":"8:30","text":"早点出门去寺里","place":{"name":"某寺","lat":35.71,"lng":139.79}},
            {"date":"2026-12-24","text":"去吃拉面","place":{"name":"拉面店"}},
            {"date":"tomorrow","text":"没有日子的一条"},
            {"date":"2026-12-25","text":"   "}
        ]}"""
        val (answer, kept) = PlanAssistant.read(
            message(
                """{"role":"assistant","content":null,"reasoning_content":"…","tool_calls":[{"id":"call-1","type":"function",
                "function":{"name":"suggest_plan","arguments":${JsonPrimitive(arguments)}}}]}""",
            ),
        )
        assertEquals("先排了第一天", answer.text)
        assertEquals(2, answer.items.size)
        val first = answer.items[0]
        assertEquals("2026-12-24", first.day)
        assertEquals("08:30", first.time)
        assertEquals(Fix(35.71, 139.79), first.place?.near)
        assertNull(answer.items[1].time)
        // The message as sent back, without what the service added, then the tool's answer.
        assertEquals(listOf("assistant", "tool"), kept.map { (it["role"] as JsonPrimitive).content })
        assertNull(kept[0]["reasoning_content"])
        assertEquals("call-1", (kept[1]["tool_call_id"] as JsonPrimitive).content)
    }

    @Test
    fun readsChangesAndDeletions() {
        val arguments = """{"items":[
            {"edit":"m-1","date":"2026-12-25","time":"9:30","text":"晚点再去"},
            {"edit":"m-2","delete":true},
            {"edit":"m-3","delete":false},
            {"delete":true},
            {"edit":"c-1","date":"2026-12-21","time":"08:00","place":{"name":"老家","lat":35.6812,"lng":139.7671}},
            {"date":"2026-12-21","time":"08:00"}
        ]}"""
        val (answer) = PlanAssistant.read(
            message("""{"role":"assistant","content":"","tool_calls":[{"id":"c","type":"function","function":{"name":"suggest_plan","arguments":${JsonPrimitive(arguments)}}}]}"""),
        )
        // A change is the whole entry with the id it changes, its place left out to keep it; a deletion is the id alone.
        assertEquals(3, answer.items.size)
        assertEquals("m-1", answer.items[0].edits)
        assertEquals("09:30", answer.items[0].time)
        assertNull(answer.items[0].place)
        assertEquals(false, answer.items[0].delete)
        assertEquals("m-2", answer.items[1].edits)
        assertEquals(true, answer.items[1].delete)
        // A change may leave its words out (a check-in moved, its words its place's); an addition may not.
        assertEquals("c-1", answer.items[2].edits)
        assertEquals("08:00", answer.items[2].time)
        assertEquals("", answer.items[2].text)
    }

    @Test
    fun namesTheLanguageToThinkIn() {
        assertEquals(true, PlanAssistant.languageRule("东京塔怎么样").contains("in Chinese"))
        assertEquals(true, PlanAssistant.languageRule("東京タワーはどう？").contains("in Japanese"))
        assertEquals(true, PlanAssistant.languageRule("How about Tokyo Tower?").contains("the language the owner writes in"))
    }

    @Test
    fun readsWordsAlone() {
        val (answer, kept) = PlanAssistant.read(message("""{"role":"assistant","content":"想去几天？"}"""))
        assertEquals("想去几天？", answer.text)
        assertEquals(emptyList(), answer.items)
        assertEquals(1, kept.size)
    }

    @Test
    fun refusesAnEmptyAnswer() {
        assertFailsWith<PlanAnswerException> { PlanAssistant.read(message("""{"role":"assistant","content":""}""")) }
    }

    @Test
    fun putsAStreamTogether() {
        val stream = PlanStream()
        val arguments = """{"reply":"排好了","items":[{"date":"2026-12-24","time":"18:00","text":"看灯，带\"相机\"}","place":{"name":"表参道"}},{"date":"2026-12-25","text":"去寺里"}]}"""
        // The tool call's arguments arrive a few characters at a time.
        val pieces = arguments.chunked(7)
        val events = buildList {
            add("""{"choices":[{"delta":{"role":"assistant","content":"好"}}]}""")
            add("""{"choices":[{"delta":{"content":"的。"}}]}""")
            add("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-9","type":"function","function":{"name":"suggest_plan","arguments":""}}]}}]}""")
            pieces.forEach { add("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":${JsonPrimitive(it)}}}]}}]}""") }
            add("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
        }
        val progress = events.flatMap { stream.take(it) }
        assertEquals(listOf(PlanProgress.Said("好"), PlanProgress.Said("好的。")), progress.filterIsInstance<PlanProgress.Said>())
        val suggested = progress.filterIsInstance<PlanProgress.Suggested>().map { it.item.text }
        assertEquals(listOf("看灯，带\"相机\"}", "去寺里"), suggested)
        // The message it makes reads as a whole one would.
        val (answer, kept) = PlanAssistant.read(stream.message())
        // What the call says besides goes unshown when it said it already.
        assertEquals("好的。", answer.text)
        assertEquals(2, answer.items.size)
        assertEquals("call-9", (kept[1]["tool_call_id"] as JsonPrimitive).content)
    }

    @Test
    fun readsTheThinking() {
        val stream = PlanStream()
        val progress = listOf(
            """{"choices":[{"delta":{"reasoning_content":"从车站"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"出发。"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
        ).flatMap { stream.take(it) }
        assertEquals(listOf(PlanProgress.Thought("从车站"), PlanProgress.Thought("从车站出发。"), PlanProgress.Said("好")), progress)
        val (answer, kept) = PlanAssistant.read(stream.message())
        assertEquals("从车站出发。", answer.thinking)
        // Shown, never sent back.
        assertNull(kept[0]["reasoning_content"])
        // OpenRouter's name for it, in a whole answer.
        assertEquals("想想", PlanAssistant.read(message("""{"role":"assistant","content":"嗯","reasoning":"想想"}""")).answer.thinking)
    }

    @Test
    fun readsTheLookupsAskedFor() {
        val place = JsonPrimitive("""{"name":"东京塔","lat":35.66,"lng":139.75}""")
        val way = JsonPrimitive("""{"from":{"name":"台场","lat":35.63,"lng":139.77},"to":{"name":"东京塔","lat":35.66,"lng":139.75},"by":"walk"}""")
        val read = PlanAssistant.read(
            message(
                """{"role":"assistant","content":"","tool_calls":[
                {"id":"a","type":"function","function":{"name":"find_place","arguments":$place}},
                {"id":"b","type":"function","function":{"name":"route_time","arguments":$way}}]}""",
            ),
        )
        assertEquals(listOf(PlanLookup.Place("a", "东京塔", Fix(35.66, 139.75))), read.lookups.take(1))
        assertEquals(Routers.By.WALK, (read.lookups[1] as PlanLookup.Way).by)
        assertEquals(listOf("assistant", "tool", "tool"), read.kept.map { (it["role"] as JsonPrimitive).content })
    }

    @Test
    fun remembersAQuestionStoppedUnanswered() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val memory = mutableListOf<List<JsonObject>>(PlanAssistant.unanswered("会花多少钱"))
        PlanAssistant(
            "https://example.test/v1", "key", "model",
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = okio.Buffer()
                chain.request().body!!.writeTo(buffer)
                sent += buffer.readUtf8()
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"message":{"role":"assistant","content":"大约三百。"}}]}""".toResponseBody("application/json".toMediaType())).build()
            }.build(),
            turns = memory,
        ).ask("我说景德镇", PlanContext("东京", "2026-12-24", "2026-12-29"), counting)
        val messages = (Json.parseToJsonElement(sent.single()).jsonObject["messages"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
        // The stopped question, then what it was told of it, then the new one.
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { (it["role"] as JsonPrimitive).content })
        assertEquals("会花多少钱", (messages[1]["content"] as JsonPrimitive).content)
        assertEquals(PlanAssistant.STOPPED, (messages[2]["content"] as JsonPrimitive).content)
    }

    @Test
    fun readsANotesSearch() {
        val search = JsonPrimitive("""{"query":"东京 拉面 推荐"}""")
        val read = PlanAssistant.read(
            message("""{"role":"assistant","content":"","tool_calls":[{"id":"n","type":"function","function":{"name":"search_notes","arguments":$search}}]}"""),
        )
        assertEquals(listOf(PlanLookup.Notes("n", "东京 拉面 推荐")), read.lookups)
        // Read only where a plugin brings the tool: every device's alone don't.
        val shared = cc.hyoban.moments.plugins.Registry(cc.hyoban.moments.plugins.Plugins.shared).all(cc.hyoban.moments.plugins.Points.planTools)
        assertEquals(emptyList(), PlanAssistant.read(
            message("""{"role":"assistant","content":"x","tool_calls":[{"id":"n","type":"function","function":{"name":"search_notes","arguments":$search}}]}"""),
            shared,
        ).lookups)
    }

    @Test
    fun searchesNotesOnlyWhereTheDeviceCan() = kotlinx.coroutines.runBlocking {
        val answer = """{"choices":[{"message":{"role":"assistant","content":"好。"}}]}"""
        val without = mutableListOf<String>()
        scripted(without, notes(signedIn = false)) { answer }.ask("规划", PlanContext("东京", "2026-12-24", "2026-12-29"), counting)
        assertEquals(false, without.single().contains("search_notes"))
        val with = mutableListOf<String>()
        scripted(with, notes()) { answer }.ask("规划", PlanContext("东京", "2026-12-24", "2026-12-29"), counting)
        assertEquals(true, with.single().contains("\"search_notes\",\"description"))
        // Told how to tell an ad from a note, only where it reads them.
        assertEquals(true, with.single().contains("广告"))
        assertEquals(false, without.single().contains("广告"))
        // An answer that only talks, suggesting nothing, is an answer, and is allowed one.
        assertEquals(false, without.single().contains("End every answer with exactly one suggest_plan"))
    }

    @Test
    fun tellsTheModelTheNotesRead() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val search = JsonPrimitive("""{"query":"东京 拉面"}""")
        val reading = notes {
            listOf(
                cc.hyoban.moments.data.ai.FoundNote("一兰真的好吃", author = "某人", likes = "1.2万", text = "排了半小时".repeat(400), posted = "2026-10-01"),
                cc.hyoban.moments.data.ai.FoundNote("别去这家"),
            )
        }
        val progress = mutableListOf<cc.hyoban.moments.data.ai.PlanProgress>()
        scripted(sent, reading) { index ->
            if (index == 0) """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"n","type":"function","function":{"name":"search_notes","arguments":$search}}]}}]}"""
            else """{"choices":[{"message":{"role":"assistant","content":"去一兰。"}}]}"""
        }.ask("想吃拉面", PlanContext("东京", "2026-12-24", "2026-12-29"), counting) { progress += it }
        val told = Json.parseToJsonElement(sent[1]).jsonObject["messages"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .last().jsonObject["content"]!!.let { (it as JsonPrimitive).content }
        val notes = Json.parseToJsonElement(told) as kotlinx.serialization.json.JsonArray
        assertEquals("一兰真的好吃", (notes[0].jsonObject["title"] as JsonPrimitive).content)
        assertEquals("1.2万", (notes[0].jsonObject["likes"] as JsonPrimitive).content)
        // A long note is cut, not sent whole.
        assertEquals(true, (notes[0].jsonObject["text"] as JsonPrimitive).content.length < 1300)
        assertEquals(2, ((progress.last { it is cc.hyoban.moments.data.ai.PlanProgress.Looked } as cc.hyoban.moments.data.ai.PlanProgress.Looked).lookup as PlanLookup.Notes).found?.size)
    }

    @Test
    fun aSearchSignedOutSaysSo() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val search = JsonPrimitive("""{"query":"东京 拉面"}""")
        val signedOut = notes { throw cc.hyoban.moments.data.ai.NoteSignInNeeded() }
        val progress = mutableListOf<cc.hyoban.moments.data.ai.PlanProgress>()
        scripted(sent, signedOut) { index ->
            if (index == 0) """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"n","type":"function","function":{"name":"search_notes","arguments":$search}}]}}]}"""
            else """{"choices":[{"message":{"role":"assistant","content":"先这样。"}}]}"""
        }.ask("想吃拉面", PlanContext("东京", "2026-12-24", "2026-12-29"), counting) { progress += it }
        val done = (progress.last { it is cc.hyoban.moments.data.ai.PlanProgress.Looked } as cc.hyoban.moments.data.ai.PlanProgress.Looked).lookup as PlanLookup.Notes
        assertEquals(true, done.signedOut)
        assertEquals(true, sent[1].contains("sign in again"))
    }

    /** Xiaohongshu as the phone reads it, [signedIn] or not, its searches finding what [found] gives. */
    private fun notes(signedIn: Boolean = true, found: suspend (String) -> List<cc.hyoban.moments.data.ai.FoundNote> = { emptyList() }) =
        object : cc.hyoban.moments.data.ai.NoteSearch {
            override val signedIn = androidx.compose.runtime.mutableStateOf(signedIn)
            override suspend fun search(query: String) = found(query)
        }

    /** The phone's planning tools, its note search [search] where given. */
    private fun phoneTools(search: cc.hyoban.moments.data.ai.NoteSearch?): () -> List<cc.hyoban.moments.data.ai.PlanTool> = {
        cc.hyoban.moments.plugins.Plugins.registry.all(cc.hyoban.moments.plugins.Points.planTools)
            .map { if (it.name == "search_notes") cc.hyoban.moments.plugins.NoteSearchTool { search } else it }
    }

    /** An assistant whose model answers [reply] to the request with each index, the requests' bodies kept in [sent], Xiaohongshu read through [search]. */
    private fun scripted(sent: MutableList<String>, search: cc.hyoban.moments.data.ai.NoteSearch? = null, reply: (Int) -> String) = PlanAssistant(
        "https://example.test/v1",
        "key",
        "model",
        okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            sent += buffer.readUtf8()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(sent.size - 1).toResponseBody("application/json".toMediaType())).build()
        }.build(),
        tools = phoneTools(search),
    )

    private val stillLooking = JsonPrimitive("""{"name":"充电站","lat":35.66,"lng":139.75}""").let { place ->
        """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"f","type":"function","function":{"name":"find_place","arguments":$place}}]}}]}"""
    }

    private val counting = object : PlanLookups {
        var places = 0
        override suspend fun places(name: String, near: Fix): List<FoundPlace> = emptyList<FoundPlace>().also { places++ }
        override suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel? = null
    }

    @Test
    fun answersOnceItMayLookNoMore() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val items = JsonPrimitive("""{"items":[{"date":"2026-12-25","time":"10:00","text":"充电","place":{"name":"充电站","lat":35.66,"lng":139.75}}]}""")
        // It looks things up past the last round, then answers once told it may not.
        val answer = scripted(sent) { index ->
            if (index <= PlanAssistant.MAX_LOOKUP_ROUNDS) stillLooking
            else """{"choices":[{"message":{"role":"assistant","content":"排好了。","tool_calls":[{"id":"s","type":"function","function":{"name":"suggest_plan","arguments":$items}}]}}]}"""
        }.ask("规划下充电", PlanContext("东京", "2026-12-24", "2026-12-29"), counting)
        assertEquals("排好了。", answer.text)
        assertEquals(1, answer.items.size)
        // Each round's lookups done, but the one asked for when no more may be: that one is told so.
        assertEquals(PlanAssistant.MAX_LOOKUP_ROUNDS, counting.places)
        assertEquals(PlanAssistant.MAX_LOOKUP_ROUNDS + 2, sent.size)
        assertEquals(true, sent.last().contains("No more lookups"))
        assertEquals(false, sent.last().contains("\"find_place\",\"description"))
    }

    @Test
    fun failsWhenItNeverAnswers() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val failed = runCatching {
            scripted(sent) { stillLooking }.ask("规划下充电", PlanContext("东京", "2026-12-24", "2026-12-29"), counting)
        }.exceptionOrNull()
        assertEquals(true, failed is cc.hyoban.moments.data.ai.PlanAnswerException)
        // Told once it may look no more, and asked once more after that, then given up on.
        assertEquals(PlanAssistant.MAX_LOOKUP_ROUNDS + 2, sent.size)
    }

    @Test
    fun looksUpThenAnswers() = kotlinx.coroutines.runBlocking {
        val place = JsonPrimitive("""{"name":"东京塔","lat":35.66,"lng":139.75}""")
        val items = JsonPrimitive("""{"items":[{"date":"2026-12-25","time":"17:00","text":"去东京塔","place":{"name":"东京塔","lat":35.6586,"lng":139.7454}}]}""")
        val answers = ArrayDeque(
            listOf(
                """{"choices":[{"message":{"role":"assistant","content":"先查一下。","reasoning_content":"东京塔在哪？","tool_calls":[{"id":"a","type":"function","function":{"name":"find_place","arguments":$place}}]}}]}""",
                """{"choices":[{"message":{"role":"assistant","content":"排好了。","reasoning_content":"离得近。","tool_calls":[{"id":"b","type":"function","function":{"name":"suggest_plan","arguments":$items}}]}}]}""",
            ),
        )
        val sent = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            sent += buffer.readUtf8()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(answers.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val looked = mutableListOf<String>()
        val lookups = object : PlanLookups {
            override suspend fun places(name: String, near: Fix): List<FoundPlace> {
                looked += name
                return listOf(FoundPlace("东京塔", "芝公园", Fix(35.6586, 139.7454)))
            }

            override suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel? = null
        }
        val progress = mutableListOf<PlanProgress>()
        val answer = PlanAssistant("https://example.test/v1", "key", "model", client)
            .ask("东京塔怎么样", PlanContext("东京", "2026-12-24", "2026-12-29", now = "2026-11-19T22:00+08:00 Asia/Shanghai", notes = "自驾，不吃辣", driving = mapOf("2026-12-24" to Routers.Travel(27_600, 612_000))), lookups) { progress += it }
        assertEquals(listOf("东京塔"), looked)
        // Words before a lookup are that round's working; the answer is what it said last.
        assertEquals("排好了。", answer.text)
        // Each day's driving as the plan stands goes with it.
        assertEquals(true, sent[0].contains("""2026-12-24\t7 h 40 min\t612 km"""))
        assertEquals(listOf("去东京塔"), answer.items.map { it.text })
        // The second round carries the lookup's result, and the date it was told.
        assertEquals(true, sent[1].contains("芝公园"))
        assertEquals(true, sent[0].contains("Asia/Shanghai"))
        // The owner's notes go after the app's own instructions.
        assertEquals(true, sent[0].contains("自驾，不吃辣"))
        // Told to think as well as answer in the language the owner wrote in.
        assertEquals(true, sent[0].contains("Think and answer in Chinese"))
        assertEquals(2, progress.filterIsInstance<PlanProgress.Looked>().size)
        // Each round's thinking in its own round, the lookups between them, as they went.
        val steps = progress.filter { it is PlanProgress.Thought || it is PlanProgress.Looked }
        assertEquals(listOf("Thought 0", "Looked", "Looked", "Thought 1"), steps.map { if (it is PlanProgress.Thought) "Thought ${it.round}" else "Looked" })
        assertEquals("东京塔在哪？\n\n先查一下。\n\n离得近。", answer.thinking)
    }

    @Test
    fun keepsOnlyTheLastList() = kotlinx.coroutines.runBlocking {
        val place = JsonPrimitive("""{"name":"东京塔","lat":35.66,"lng":139.75}""")
        val first = JsonPrimitive("""{"items":[{"date":"2026-12-25","text":"先这样"}]}""")
        val last = JsonPrimitive("""{"items":[{"date":"2026-12-25","text":"改成这样"},{"edit":"m-1","delete":true}]}""")
        val answers = ArrayDeque(
            listOf(
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"a","type":"function","function":{"name":"suggest_plan","arguments":$first}},{"id":"b","type":"function","function":{"name":"find_place","arguments":$place}}]}}]}""",
                """{"choices":[{"message":{"role":"assistant","content":"好了。","tool_calls":[{"id":"c","type":"function","function":{"name":"suggest_plan","arguments":$last}}]}}]}""",
            ),
        )
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(answers.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val lookups = object : PlanLookups {
            override suspend fun places(name: String, near: Fix): List<FoundPlace> = emptyList()
            override suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel? = null
        }
        val progress = mutableListOf<PlanProgress>()
        val answer = PlanAssistant("https://example.test/v1", "key", "model", client)
            .ask("改一下", PlanContext("东京", "2026-12-24", "2026-12-29"), lookups) { progress += it }
        // The answer's changes are its last list, whole; the first is said to go.
        assertEquals(listOf("改成这样", ""), answer.items.map { it.text })
        assertEquals(true, answer.items[1].delete)
        assertEquals(1, progress.count { it == PlanProgress.Replanned })
    }

    @Test
    fun forgetsWhatCameAfter() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            sent += buffer.readUtf8()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"好"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val lookups = object : PlanLookups {
            override suspend fun places(name: String, near: Fix): List<FoundPlace> = emptyList()
            override suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel? = null
        }
        val memory = mutableListOf<List<JsonObject>>()
        val assistant = PlanAssistant("https://example.test/v1", "key", "model", client, memory)
        val context = PlanContext("东京", "2026-12-24", "2026-12-29")
        assistant.ask("第一句", context, lookups)
        assistant.ask("第二句", context, lookups)
        assertEquals(2, memory.size)
        // Back to before the second, as PlanChat.rewind does: the model is asked again without it.
        memory.removeAt(1)
        assistant.ask("重写的第二句", context, lookups)
        assertEquals(true, sent[2].contains("第一句"))
        assertEquals(false, sent[2].contains("\"第二句\""))
    }

    @Test
    fun takesOnlyFinishedItemsSoFar() {
        val partial = """{"items":[{"date":"2026-12-24","text":"一"},{"date":"2026-12-25","text":"还没写完"""
        assertEquals(listOf("一"), PlanAssistant.itemsSoFar(partial).map { it.text })
    }

    @Test
    fun failsOnAStreamedError() {
        assertFailsWith<PlanAnswerException> { PlanStream().take("""{"error":{"message":"quota"}}""") }
    }

    @Test
    fun readsTheModelsOffered() {
        val answer = """{"object":"list","data":[{"id":"model-b","object":"model"},{"id":"model-a"},{"id":"model-b"},{"object":"model"}]}"""
        assertEquals(listOf("model-a", "model-b"), PlanAssistant.modelsOf(answer))
        assertFailsWith<PlanAnswerException> { PlanAssistant.modelsOf("""{"error":"no"}""") }
    }

    @Test
    fun holdsEverythingInThePlan() {
        val places = mapOf("station" to Place("车站", 30.0, 120.0), "temple" to Place("寺", 35.7, 139.8))
        val plan = TripRecord("t-plan", "东京", "2026-12-24", "2026-12-29", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")
        val moments = listOf(
            moment("1", "2026-12-25T08:30:00+09:00", "temple").copy(text = "去寺里", planned = true),
            moment("2", "2026-12-24T07:00:00+08:00", "station", hidden = true).copy(text = "出发"),
            moment("3", "2026-11-01T10:00:00+08:00", "station"),
        )
        val checkins = listOf(Checkin("c1", "2026-12-24T06:30:00+08:00", "车站", place = "station", private = true))
        val records = planRecords(plan, moments, checkins.map { it.keptStop() }, listOf(plan), places, now = java.time.Instant.parse("2026-11-20T00:00:00Z"))
        // Private ones too, oldest first; nothing from outside the plan.
        assertEquals(listOf("车站", "车站", "寺"), records.map { it.place })
        assertEquals(listOf(true, false, false), records.map { it.stop != null })
        assertEquals(listOf(false, false, true), records.map { it.planned })
        assertEquals("出发", records[1].text)
        // A planned one has an id, to be changed or deleted by, and so has the check-in still to come.
        assertEquals(listOf("c1", null, "1"), records.map { it.id })
    }

    @Test
    fun aLockedPlanGoesAsSettled() {
        val places = mapOf("temple" to Place("寺", 35.7, 139.8))
        val plan = TripRecord("t-plan", "东京", "2026-12-24", "2026-12-29", planned = true, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z")
        val moments = listOf(moment("1", "2026-12-25T08:30:00+09:00", "temple").copy(text = "去寺里", planned = true, locked = true))
        val records = planRecords(plan, moments, emptyList(), listOf(plan), places, now = java.time.Instant.parse("2026-11-20T00:00:00Z"))
        // No id to change it by, and said to be settled.
        assertEquals(listOf<String?>(null), records.map { it.id })
        assertEquals(listOf(true), records.map { it.locked })
    }

    @Test
    fun remembersOlderTurnsBriefly() {
        fun turn(n: Int): List<JsonObject> {
            val list = JsonPrimitive("""{"items":[{"date":"2026-12-25","text":"第$n 条"}]}""")
            val look = JsonPrimitive("""{"name":"东京塔","lat":35.66,"lng":139.75}""")
            return listOf(
                message("""{"role":"user","content":"问$n"}"""),
                message("""{"role":"assistant","content":"先查。","tool_calls":[{"id":"f$n","type":"function","function":{"name":"find_place","arguments":$look}}]}"""),
                message("""{"role":"tool","tool_call_id":"f$n","content":"[]"}"""),
                message("""{"role":"assistant","content":"答$n","tool_calls":[{"id":"s$n","type":"function","function":{"name":"suggest_plan","arguments":$list}}]}"""),
                message("""{"role":"tool","tool_call_id":"s$n","content":"${PlanAssistant.SHOWN}"}"""),
            )
        }
        val first = PlanAssistant.withOutcome(turn(1), "The owner applied none of it.")
        assertEquals("${PlanAssistant.SHOWN} The owner applied none of it.", PlanAssistant.outcomeOf(first))
        val sent = PlanAssistant.recalled(listOf(first, turn(2), turn(3)))
        // The oldest as its question and its last words, with what came of its list; the latest two whole.
        assertEquals(2 + 5 + 5, sent.size)
        assertEquals("问1", (sent[0]["content"] as JsonPrimitive).content)
        assertEquals(
            "答1\n\n[Listed changes in the plan sheet. ${PlanAssistant.SHOWN} The owner applied none of it.]",
            (sent[1]["content"] as JsonPrimitive).content,
        )
        assertEquals(null, sent[1]["tool_calls"])
        assertEquals(turn(3), sent.takeLast(5))
    }

    private fun moment(uid: String, at: String, place: String, hidden: Boolean = false) =
        MomentRecord(uid = uid, occurredAt = at, places = listOf(place), text = "", hidden = hidden, createdAt = at, updatedAt = at)
}
