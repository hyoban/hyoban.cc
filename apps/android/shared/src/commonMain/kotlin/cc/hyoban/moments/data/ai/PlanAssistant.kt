package cc.hyoban.moments.data.ai

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.trips.Routers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

// Planning a trip by talking it over with a model the owner chose: any service that speaks
// OpenAI's chat completions, at the address, with the key and the model set in 同步与来源,
// asked straight from the device. What it is sent is said in [PlanContext]; what it suggests
// comes back as [PlanItem]s, which the owner adds to the plan one by one.

/**
 * A record the plan already holds, a Moment or a check-in, at [at] (a timestamp, or a day alone),
 * with its words and its place's name; [planned] when written into the plan rather than lived,
 * and then its [id], by which the model may ask to change or delete it.
 */
data class PlanRecord(val at: String, val checkin: Boolean, val text: String, val place: String?, val planned: Boolean, val fix: Fix? = null, val id: String? = null)

/**
 * What the model is told besides the conversation: the plan's name and days, and every record it
 * already holds. Nothing else of the owner's places goes with it.
 */
data class PlanContext(
    val title: String,
    val start: String,
    val end: String,
    val records: List<PlanRecord> = emptyList(),
    /** Now, where the device is: its time with its offset, and its zone's name. */
    val now: String = "",
    /** What the owner set in 同步与来源 to tell the model besides, sent after the app's own instructions. */
    val notes: String = "",
    /** How long and how far each day's places are apart by road, in order, by day (`YYYY-MM-DD`): the days worked out in full. */
    val driving: Map<String, Routers.Travel> = emptyMap(),
)

/** A place a map service knows by a name, for the model to check one. */
data class FoundPlace(val name: String, val address: String?, val fix: Fix)

/** One end of a way the model asks about: what it calls it, and where. */
data class WayEnd(val name: String, val fix: Fix)

/**
 * What the model asked to look up while planning, by its call's [id]: a place by [name] about
 * [near], or the way [from] [to] [by] a mode; with its [result] once it came, or [failed].
 */
sealed interface PlanLookup {
    val id: String

    data class Place(override val id: String, val name: String, val near: Fix, val found: List<FoundPlace>? = null, val failed: Boolean = false) : PlanLookup
    data class Way(
        override val id: String,
        val from: WayEnd,
        val to: WayEnd,
        val by: Routers.By,
        val travel: Routers.Travel? = null,
        val done: Boolean = false,
        val failed: Boolean = false,
    ) : PlanLookup
}

/** What the device looks up for the model: places by name, and how long a way takes. */
interface PlanLookups {
    suspend fun places(name: String, near: Fix): List<FoundPlace>
    suspend fun travel(from: Fix, to: Fix, by: Routers.By): Routers.Travel?
}

/** Where a suggestion is, as the model names it: [name], about [near]. */
data class PlanPlace(val name: String, val near: Fix?)

/**
 * One thing the model suggests: on [day] (`YYYY-MM-DD`) at [time] (`HH:mm`, when it says), [text], at
 * [place]. Added, unless it [edits] a planned record by its id: then it is that record as it would
 * become, its place left as it was when it names none; or, [delete]d, that record goes, and the item
 * says nothing else.
 */
data class PlanItem(
    val day: String,
    val time: String?,
    val text: String,
    val place: PlanPlace?,
    val edits: String? = null,
    val delete: Boolean = false,
    /** How it is reached from the place before it that day, when the model says. */
    val by: Routers.By? = null,
)

/** What the model answered: what it said, what it suggests adding, and what it thought first, when it says. */
data class PlanAnswer(val text: String, val items: List<PlanItem>, val thinking: String = "")

/**
 * A model's message as read: its [answer], what the conversation keeps of it (the message and
 * an answer to each tool call, the lookups' to be filled in), and the [lookups] it asks for.
 */
data class PlanRead(val answer: PlanAnswer, val kept: List<JsonObject>, val lookups: List<PlanLookup>)

/**
 * What a model thought before answering, as the services that show it put it beside the words:
 * `reasoning_content` (DeepSeek, Qwen and most) or `reasoning` (OpenRouter).
 */
private fun thoughtOf(message: JsonObject): String? =
    listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { (message[it] as? JsonPrimitive)?.contentOrNull?.takeIf { text -> text.isNotEmpty() } }

/** A model's answer that couldn't be read. */
class PlanAnswerException(message: String) : IOException(message)

/** A step of an answer as it streams in: what it has said so far, or a suggestion written in full. */
sealed interface PlanProgress {
    data class Said(val text: String) : PlanProgress
    data class Suggested(val item: PlanItem) : PlanProgress

    /** What it has thought so far in this [round] of asking, before answering or looking up. */
    data class Thought(val text: String, val round: Int = 0) : PlanProgress

    /** A lookup it asked for, as it starts, and again with its result. */
    data class Looked(val lookup: PlanLookup) : PlanProgress

    /** It lists its changes again, in full, in a later round: what it suggested earlier in this answer goes. */
    data object Replanned : PlanProgress
}

/**
 * An answer streaming in as OpenAI's chat completions stream one: each event's `data` taken in
 * order, its words and tool calls' pieces put together, and the message they make at the end.
 */
class PlanStream {
    private val said = StringBuilder()
    private val thought = StringBuilder()
    private val calls = sortedMapOf<Int, Call>()
    private var suggested = 0

    private class Call(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    /** What [data], one event's, adds: the words so far when it adds words, and each suggestion it completes. */
    fun take(data: String): List<PlanProgress> {
        val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        (event["error"] as? JsonObject)?.let { error ->
            throw PlanAnswerException((error["message"] as? JsonPrimitive)?.contentOrNull ?: "The model's stream failed")
        }
        val delta = ((event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return emptyList()
        val progress = mutableListOf<PlanProgress>()
        thoughtOf(delta)?.let {
            thought.append(it)
            progress += PlanProgress.Thought(thought.toString().trim())
        }
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            said.append(it)
            progress += PlanProgress.Said(said.toString().trim())
        }
        (delta["tool_calls"] as? JsonArray).orEmpty().forEach { piece ->
            val part = piece as? JsonObject ?: return@forEach
            val index = (part["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val call = calls.getOrPut(index) { Call() }
            (part["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id = it }
            val function = part["function"] as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name += it }
            (function?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.arguments.append(it) }
        }
        // Suggestions completed by this event, in order across the calls.
        val items = calls.values.filter { it.name == "suggest_plan" }.flatMap { PlanAssistant.itemsSoFar(it.arguments.toString()) }
        items.drop(suggested).forEach { progress += PlanProgress.Suggested(it) }
        suggested = maxOf(suggested, items.size)
        return progress
    }

    /** The message the stream made, in the shape a whole answer's message has. */
    fun message(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", said.toString())
        if (thought.isNotEmpty()) put("reasoning_content", thought.toString())
        if (calls.isNotEmpty()) {
            put(
                "tool_calls",
                JsonArray(
                    calls.values.map { call ->
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            }
                        }
                    },
                ),
            )
        }
    }
}

/**
 * Talks with the model at [url] (an OpenAI-compatible API's base, its `/chat/completions`
 * beside it), as [model], with [key]. Keeps the conversation as it was sent, so each turn
 * carries what came before.
 */
class PlanAssistant(
    private val url: String,
    private val key: String,
    private val model: String,
    client: OkHttpClient = Http.client,
    /** What the model remembers: each answered turn's messages, the question and all that answered it; kept by whoever keeps the conversation. */
    private val turns: MutableList<List<JsonObject>> = mutableListOf(),
) {
    // A model may think for a while before it answers.
    private val client = client.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()

    /**
     * Asks [text] of the model, with [context], and gives what it answered, telling [onProgress]
     * on the caller's thread of its thoughts, words, lookups and suggestions as they come. Before
     * it answers the model may look places and ways up through [lookups], a round each, at most
     * [MAX_LOOKUP_ROUNDS] rounds. A service that won't stream is asked for whole answers. On
     * failure or when cancelled the conversation is as it was.
     */
    suspend fun ask(text: String, context: PlanContext, lookups: PlanLookups, onProgress: (PlanProgress) -> Unit = {}): PlanAnswer {
        val caller = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Default
        val tell: suspend (PlanProgress) -> Unit = { progress -> withContext(caller) { onProgress(progress) } }
        val asked = buildJsonObject {
            put("role", "user")
            put("content", text)
        }
        val turn = mutableListOf<JsonObject>()
        // The last round's words are the answer; a round's words before it looks things up are part of its working.
        var said = ""
        var thought = ""
        val items = mutableListOf<PlanItem>()
        for (round in 0..MAX_LOOKUP_ROUNDS) {
            val conversation = JsonArray(listOf(system(context, text)) + synchronized(turns) { recalled(turns) } + asked + turn)
            // The last round may only answer.
            val tools = if (round == MAX_LOOKUP_ROUNDS) listOf(SUGGEST_TOOL) else listOf(SUGGEST_TOOL, PLACE_TOOL, WAY_TOOL)
            // A list in a later round stands for the whole answer's: the earlier one goes.
            var listed = false
            val message = withContext(Dispatchers.IO) {
                try {
                    stream(conversation, tools) { progress ->
                        if (progress is PlanProgress.Suggested && !listed) {
                            listed = true
                            if (items.isNotEmpty()) tell(PlanProgress.Replanned)
                        }
                        tell(
                            when (progress) {
                                is PlanProgress.Thought -> PlanProgress.Thought(progress.text, round)
                                else -> progress
                            },
                        )
                    }
                } catch (refused: HttpException) {
                    // Streaming refused as asked for: the whole answer at once instead.
                    if (refused.code !in STREAM_REFUSED) throw refused
                    whole(conversation, tools)
                }
            }
            val read = read(message)
            // Words said on the way to a lookup are this round's working, beside its thinking, not the answer.
            val working = if (read.lookups.isEmpty()) read.answer.thinking else joined(read.answer.thinking, read.answer.text)
            thought = joined(thought, working)
            if (read.lookups.isEmpty()) {
                said = read.answer.text.ifEmpty { said }
            } else if (read.answer.text.isNotEmpty()) {
                said = read.answer.text
                tell(PlanProgress.Said(""))
            }
            // A whole answer's thinking, which no stream told as it came, and words moved into it.
            if (working.isNotEmpty()) tell(PlanProgress.Thought(working, round))
            if (read.answer.items.isNotEmpty()) {
                if (!listed && items.isNotEmpty()) tell(PlanProgress.Replanned)
                items.clear()
                items += read.answer.items
            }
            if (read.lookups.isEmpty()) {
                turn += read.kept
                break
            }
            // Each lookup asked for, its result in place of the tool call's answer.
            val results = read.lookups.associate { lookup ->
                tell(PlanProgress.Looked(lookup))
                val (done, result) = look(lookup, lookups)
                tell(PlanProgress.Looked(done))
                lookup.id to result
            }
            turn += read.kept.map { kept ->
                val id = (kept["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                val result = results[id] ?: return@map kept
                JsonObject(kept + ("content" to JsonPrimitive(result)))
            }
        }
        if (said.isEmpty() && items.isEmpty()) throw PlanAnswerException("The model answered nothing")
        synchronized(turns) { turns += listOf(asked) + turn }
        return PlanAnswer(said, items, thought)
    }

    /** [lookup] done through [lookups], and what the model is told of it. */
    private suspend fun look(lookup: PlanLookup, lookups: PlanLookups): Pair<PlanLookup, String> = try {
        when (lookup) {
            is PlanLookup.Place -> {
                val found = lookups.places(lookup.name, lookup.near).take(FOUND_PLACES)
                lookup.copy(found = found) to if (found.isEmpty()) {
                    "No place called that was found near there."
                } else {
                    JsonArray(
                        found.map { place ->
                            buildJsonObject {
                                put("name", place.name)
                                place.address?.let { put("address", it) }
                                put("lat", place.fix.latitude)
                                put("lng", place.fix.longitude)
                            }
                        },
                    ).toString()
                }
            }
            is PlanLookup.Way -> {
                val travel = lookups.travel(lookup.from.fix, lookup.to.fix, lookup.by)
                lookup.copy(travel = travel, done = true) to if (travel == null) {
                    "There is no way there by that mode."
                } else {
                    buildJsonObject {
                        put("minutes", (travel.seconds + 30) / 60)
                        put("km", Math.round(travel.metres / 100.0) / 10.0)
                    }.toString()
                }
            }
        }
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        when (lookup) {
            is PlanLookup.Place -> lookup.copy(failed = true)
            is PlanLookup.Way -> lookup.copy(done = true, failed = true)
        } to "The lookup failed; go on without it."
    }

    private fun request(conversation: JsonArray, tools: List<JsonObject>, stream: Boolean): Request {
        val body = buildJsonObject {
            put("model", model)
            put("messages", conversation)
            put("tools", JsonArray(tools))
            if (stream) put("stream", true)
        }
        return Request.Builder()
            .url("$url/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** The answer's message, asked for whole. */
    private suspend fun whole(conversation: JsonArray, tools: List<JsonObject>): JsonObject {
        val reply = execute(request(conversation, tools, stream = false)) { response -> response.body?.string().orEmpty() }
        return messageOf(reply)
    }

    /**
     * The answer's message, read from its server-sent events as they come, each step told to
     * [progress]; a service that answers with JSON instead is read whole.
     */
    private suspend fun stream(conversation: JsonArray, tools: List<JsonObject>, progress: suspend (PlanProgress) -> Unit): JsonObject {
        val reader = PlanStream()
        var whole: JsonObject? = null
        execute(request(conversation, tools, stream = true)) { response ->
            val source = response.body?.source() ?: throw PlanAnswerException("The model answered without a body")
            val type = response.body?.contentType()?.toString() ?: response.header("Content-Type").orEmpty()
            if (type.contains("json")) {
                whole = messageOf(source.readUtf8())
                return@execute
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                reader.take(data).forEach { progress(it) }
            }
        }
        return whole ?: reader.message()
    }

    /** Runs [request], cancelled with the coroutine, and gives what [read] makes of a 2xx answer. */
    private suspend fun <T> execute(request: Request, read: suspend (okhttp3.Response) -> T): T {
        val call = client.newCall(request)
        val cancelling = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) {
                    val said = response.body?.string().orEmpty()
                    throw HttpException(response.code, "POST chat/completions answered ${response.code}: ${said.take(300)}")
                }
                read(response)
            }
        } finally {
            cancelling?.dispose()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** How many rounds of lookups a turn may take before the model must answer. */
        const val MAX_LOOKUP_ROUNDS = 6

        /** What the model is told of a list it showed, until the owner did something with it. */
        const val SHOWN = "Shown to the owner in the plan sheet, where they apply the ones they want."

        /** How many of the latest turns the model is sent whole, lookups and lists and all; those before, only what was said. */
        const val WHOLE_TURNS = 2

        /**
         * [turns] as the model is sent them: the latest [WHOLE_TURNS] as they were, and each before
         * them as its question and what was answered, with what came of its list, the lookups and
         * the list itself left out, as the plan they led to is sent anyway.
         */
        fun recalled(turns: List<List<JsonObject>>): List<JsonObject> = turns.flatMapIndexed { index, turn ->
            if (index >= turns.size - WHOLE_TURNS) return@flatMapIndexed turn
            val asked = turn.firstOrNull { text(it["role"]) == "user" } ?: return@flatMapIndexed emptyList()
            // What it said last; words before a lookup were its working.
            val answered = turn.lastOrNull { text(it["role"]) == "assistant" && text(it["content"]) != null }?.let { text(it["content"]) }.orEmpty()
            val outcome = outcomeOf(turn)?.takeIf { it != SHOWN }
            val content = listOfNotNull(answered.takeIf { it.isNotEmpty() }, outcome?.let { "[Listed changes in the plan sheet. $it]" }).joinToString("\n\n")
            if (content.isEmpty()) listOf(asked) else listOf(asked, buildJsonObject {
                put("role", "assistant")
                put("content", content)
            })
        }

        /** The ids of [turn]'s suggest_plan calls, the last one last. */
        private fun listCalls(turn: List<JsonObject>): List<String> = turn.flatMap { message ->
            (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { call ->
                val made = call as? JsonObject
                val function = made?.get("function") as? JsonObject
                if (text(function?.get("name")) == "suggest_plan") text(made?.get("id")) else null
            }
        }

        /** What [turn]'s last list came to, as the model was told; null when it listed nothing. */
        fun outcomeOf(turn: List<JsonObject>): String? {
            val id = listCalls(turn).lastOrNull() ?: return null
            return turn.lastOrNull { text(it["tool_call_id"]) == id }?.let { text(it["content"]) }
        }

        /** [turn] with what came of its last list told as [outcome], in the answer to that call. */
        fun withOutcome(turn: List<JsonObject>, outcome: String): List<JsonObject> {
            val id = listCalls(turn).lastOrNull() ?: return turn
            val at = turn.indexOfLast { text(it["tool_call_id"]) == id }
            if (at < 0) return turn
            return turn.toMutableList().also { it[at] = JsonObject(it[at] + ("content" to JsonPrimitive("$SHOWN $outcome"))) }
        }

        /** How many places a place's lookup tells of. */
        private const val FOUND_PLACES = 5

        /** Two pieces of an answer, one after the other. */
        private fun joined(before: String, after: String) = listOf(before, after).filter { it.isNotBlank() }.joinToString("\n\n")

        /** What a service answers when it won't stream as asked. */
        private val STREAM_REFUSED = setOf(400, 404, 405, 415, 422, 501)

        /** The message of a whole answer, `{"choices":[{"message":…}]}`. */
        fun messageOf(reply: String): JsonObject = runCatching {
            (json.parseToJsonElement(reply).jsonObject["choices"] as JsonArray).first().jsonObject["message"]!!.jsonObject
        }.getOrElse { throw PlanAnswerException("The model's answer had no message") }

        /**
         * The suggestions written in full so far in a tool call's [arguments], still arriving:
         * each item of its `items` array whose object has closed, read as [read] reads it.
         */
        fun itemsSoFar(arguments: String): List<PlanItem> {
            val opening = Regex(""""items"\s*:\s*\[""").find(arguments) ?: return emptyList()
            val found = mutableListOf<PlanItem>()
            var depth = 0
            var inString = false
            var escaped = false
            var start = -1
            for (i in opening.range.last + 1 until arguments.length) {
                val c = arguments[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            runCatching { json.parseToJsonElement(arguments.substring(start, i + 1)) }.getOrNull()?.let(::item)?.let(found::add)
                            start = -1
                        }
                    }
                    ']' -> if (depth == 0) break
                }
            }
            return found
        }

        /** The models the service at [url] offers with [key] (`GET /models`), by their ids, in order. */
        suspend fun models(url: String, key: String, client: OkHttpClient = Http.client): List<String> = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$url/models")
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
            val answer = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpException(response.code, "GET models answered ${response.code}: ${body.take(300)}")
                body
            }
            modelsOf(answer)
        }

        /** The ids in a `/models` answer, `{"data":[{"id":…}]}`, without repeats, sorted. */
        fun modelsOf(answer: String): List<String> {
            val data = runCatching { json.parseToJsonElement(answer).jsonObject["data"] as? JsonArray }.getOrNull()
                ?: throw PlanAnswerException("The service's model list had no data")
            return data.mapNotNull { (it as? JsonObject)?.let { model -> text(model["id"]) } }.distinct().sorted()
        }

        /** What the model is told it is doing, and of the plan; in English, the model answering in the owner's words. */
        private fun system(context: PlanContext, asked: String): JsonObject {
            val prompt = buildString {
                append("You help the owner of a travel journal plan a trip they have not taken yet. ")
                append("The plan is called \"${context.title}\" and runs from ${context.start} to ${context.end}. ")
                append("Talk it over with them briefly. When you have concrete things to suggest, call suggest_plan with them: ")
                append("each is one thing to do at one place, on one day within the plan, at a local time. ")
                append("Write each item's text as a short note in the owner's own voice and language, as they might jot it in their journal, without emoji. ")
                append("Name each place as a map would, and give its approximate coordinates so it can be looked up. ")
                append("Say for each how the owner gets there from the place before it that day (drive, walk, bike or train); the app shows each way's time and distance by it. ")
                append("Answer in the language the owner writes in, and keep what you say short; the items carry the plan.")
                if (context.now.isNotEmpty()) append("\n\nNow, where the owner is: ${context.now}. ")
                append("\n\nBefore suggesting, check what you are unsure of: find_place tells whether a place is there and where, ")
                append("and route_time how long a way takes by road, on foot, by bike or by train, so each day fits. ")
                append("route_time knows no timetables: by train it gives the line's running time, and within a city public transport takes about as long as driving, with waiting. ")
                append("Use the coordinates find_place gives for the places you suggest.")
                append("\n\nThe entries below with an id are part of the plan, and you may change or remove them through suggest_plan too: ")
                append("to change one, give its id as edit with the whole entry as it should become (date, time, text, and place unless it stays); ")
                append("to remove one, give only its id as edit and delete true. Those with an id are the planned entries and the check-ins still to come, ")
                append("which the owner wrote down ahead, such as where they set out from; of a check-in change only its time and place, its name being its place's. ")
                append("Entries without an id happened and stay as they are. Prefer changing an entry to removing it and adding another. ")
                append("Never list again an entry the plan already holds as it is, and never add one again to move or reword it: give its id as edit. ")
                append("When you say an entry should go, list it with delete true; saying so is not enough.")
                append("\n\nEnd every answer with exactly one suggest_plan call, after any lookups, holding the complete set of changes you propose ")
                append("against the plan as it stands now (the entries below): repeat what you proposed before that the owner has not applied and that still stands, ")
                append("and leave out what no longer does, since only your latest list can be applied. Don't spread the changes over several calls or answers. ")
                append("The app shows the owner how to apply them; don't explain it. What came of your earlier lists is told in their tool results: those applied are entries below now. ")
                append("Say what you have to say once, after any lookups; words written before a lookup are not shown as your answer.")
                if (context.records.isNotEmpty()) {
                    append("\n\nWhat the plan already holds, in order (id, time, kind, place, latitude,longitude, note). ")
                    append("Take where the owner sets out from, where they stay and what is fixed from these; ")
                    append("build on them and don't suggest again what is already there.\n")
                    append(
                        context.records.joinToString("\n") { record ->
                            val kind = when {
                                record.checkin && record.id != null -> "check-in still to come"
                                record.checkin -> "check-in"
                                record.planned -> "planned"
                                else -> "moment"
                            }
                            val at = record.fix?.let { "%.4f,%.4f".format(java.util.Locale.ROOT, it.latitude, it.longitude) }.orEmpty()
                            listOf(record.id.orEmpty(), record.at, kind, record.place.orEmpty(), at, record.text.replace('\n', ' ')).joinToString("\t")
                        },
                    )
                }
                if (context.driving.isNotEmpty()) {
                    append("\n\nOn the road each day as the plan stands, between its places in order, as if driven (keep a day within what the owner can do; spread a long one out):\n")
                    append(
                        context.driving.toSortedMap().entries.joinToString("\n") { (day, travel) ->
                            val minutes = (travel.seconds + 30) / 60
                            "$day\t${minutes / 60} h ${minutes % 60} min\t${Math.round(travel.metres / 1000.0)} km"
                        },
                    )
                }
            }
            // The owner's own words last, as they wrote them, adding to what comes before.
            val notes = context.notes.trim()
            val noted = if (notes.isEmpty()) prompt else "$prompt\n\nWhat the owner adds, about themselves and how they like to travel:\n$notes"
            // Last, nearest the conversation, so a model thinking in English by habit thinks in theirs.
            val told = "$noted\n\n${languageRule(asked)}"
            return buildJsonObject {
                put("role", "system")
                put("content", told)
            }
        }

        /** A way's end, as the model names it. */
        private fun endSchema(what: String) = buildJsonObject {
            put("type", "object")
            put("description", what)
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("lat") { put("type", "number") }
                putJsonObject("lng") { put("type", "number") }
            }
            putJsonArray("required") {
                add("name")
                add("lat")
                add("lng")
            }
        }

        /** Looking a place up by its name about where it should be. */
        private val PLACE_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "find_place")
                put("description", "Look a place up by name near where it should be: what map services call it there, with addresses and coordinates.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("lat") {
                            put("type", "number")
                            put("description", "About where it should be.")
                        }
                        putJsonObject("lng") { put("type", "number") }
                    }
                    putJsonArray("required") {
                        add("name")
                        add("lat")
                        add("lng")
                    }
                }
            }
        }

        /** How long a way takes, by a mode. */
        private val WAY_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "route_time")
                put("description", "How long and how far it is from one place to another by road, on foot, by bike or by train, without timetables.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("from", endSchema("Where the way starts."))
                        put("to", endSchema("Where it ends."))
                        putJsonObject("by") {
                            put("type", "string")
                            putJsonArray("enum") {
                                Routers.By.entries.forEach { add(it.name.lowercase()) }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("from")
                        add("to")
                        add("by")
                    }
                }
            }
        }

        /** The one tool: the plan's items, shown to the owner to add. */
        private val SUGGEST_TOOL: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "suggest_plan")
                put("description", "Show the owner things to add to the plan, or planned entries to change or remove; each takes effect only if they tap it.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("items") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("edit") {
                                        put("type", "string")
                                        put("description", "The id of a planned entry this changes or removes; leave out to add a new one.")
                                    }
                                    putJsonObject("delete") {
                                        put("type", "boolean")
                                        put("description", "True to remove the planned entry named by edit; nothing else is needed then.")
                                    }
                                    putJsonObject("date") {
                                        put("type", "string")
                                        put("description", "The day, YYYY-MM-DD, within the plan. Needed unless deleting.")
                                    }
                                    putJsonObject("time") {
                                        put("type", "string")
                                        put("description", "The local time, HH:mm.")
                                    }
                                    putJsonObject("text") {
                                        put("type", "string")
                                        put("description", "A short note in the owner's voice. Needed when adding; when changing, leave out to keep the entry's own.")
                                    }
                                    putJsonObject("by") {
                                        put("type", "string")
                                        put("description", "How the owner gets here from the place before it that day.")
                                        putJsonArray("enum") {
                                            Routers.By.entries.forEach { add(it.name.lowercase()) }
                                        }
                                    }
                                    putJsonObject("place") {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            putJsonObject("name") { put("type", "string") }
                                            putJsonObject("lat") { put("type", "number") }
                                            putJsonObject("lng") { put("type", "number") }
                                        }
                                        putJsonArray("required") { add("name") }
                                    }
                                }
                            }
                        }
                    }
                    putJsonArray("required") { add("items") }
                }
            }
        }

        /**
         * What [message] says and suggests, and what of it the conversation keeps: the
         * message as sent back (its words and tool calls, nothing a service adds of its own),
         * and an answer to each tool call so the next turn may follow it.
         */
        fun read(message: JsonObject): PlanRead {
            val said = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val items = mutableListOf<PlanItem>()
            val replies = mutableListOf<String>()
            val lookups = mutableListOf<PlanLookup>()
            for (call in calls) {
                val function = call["function"] as? JsonObject ?: continue
                val arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull ?: continue
                val parsed = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: continue
                val id = (call["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when ((function["name"] as? JsonPrimitive)?.contentOrNull) {
                    "suggest_plan" -> {
                        text(parsed["reply"])?.let(replies::add)
                        (parsed["items"] as? JsonArray).orEmpty().mapNotNullTo(items) { item(it) }
                    }
                    "find_place" -> {
                        val name = text(parsed["name"]) ?: continue
                        val near = fixOf(parsed) ?: continue
                        lookups += PlanLookup.Place(id, name, near)
                    }
                    "route_time" -> {
                        val from = endOf(parsed["from"]) ?: continue
                        val to = endOf(parsed["to"]) ?: continue
                        val by = byOf(text(parsed["by"])) ?: Routers.By.DRIVE
                        lookups += PlanLookup.Way(id, from, to, by)
                    }
                }
            }
            val kept = buildList {
                add(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", said)
                        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                    },
                )
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", (call["id"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                            put("content", SHOWN)
                        },
                    )
                }
            }
            // Words in the call only when it said none besides: the same said twice otherwise.
            val text = said.ifEmpty { replies.filter { it.isNotBlank() }.distinct().joinToString("\n\n") }
            if (text.isEmpty() && items.isEmpty() && lookups.isEmpty()) throw PlanAnswerException("The model answered nothing")
            return PlanRead(PlanAnswer(text, items, thoughtOf(message).orEmpty().trim()), kept, lookups)
        }

        /** A way's mode as the model names it, `drive`, `walk`, `bike` or `train`; null for anything else. */
        fun byOf(name: String?): Routers.By? = Routers.By.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun fixOf(place: JsonObject): Fix? {
            val lat = (place["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val lng = (place["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null
            return if (lat in -90.0..90.0 && lng in -180.0..180.0) Fix(lat, lng) else null
        }

        private fun endOf(element: JsonElement?): WayEnd? {
            val end = element as? JsonObject ?: return null
            return WayEnd(text(end["name"]) ?: return null, fixOf(end) ?: return null)
        }

        private fun item(element: JsonElement): PlanItem? {
            val item = element as? JsonObject ?: return null
            val edits = text(item["edit"])
            if (edits != null && (item["delete"] as? JsonPrimitive)?.contentOrNull == "true") return PlanItem("", null, "", null, edits, delete = true)
            val day = text(item["date"])?.takeIf { DAY.matches(it) } ?: return null
            // A change may leave its words out, keeping them: a check-in's are its place's anyway.
            val words = text(item["text"]) ?: if (edits != null) "" else return null
            val time = text(item["time"])?.let { TIME.find(it)?.value }?.padStart(5, '0')
            val place = (item["place"] as? JsonObject)?.let { place -> text(place["name"])?.let { PlanPlace(it, fixOf(place)) } }
            return PlanItem(day, time, words, place, edits, by = byOf(text(item["by"])))
        }

        /**
         * How the model is to speak: in the language of what the owner just wrote, its thinking
         * too, named when it is Chinese or Japanese, which a model is likeliest to think past.
         */
        fun languageRule(asked: String): String {
            val named = when {
                asked.any { it in '\u3040'..'\u30ff' } -> "Japanese"
                asked.any { it in '\u4e00'..'\u9fff' } -> "Chinese"
                else -> null
            }
            val language = named ?: "the language the owner writes in"
            return "Think and answer in $language, as the owner's latest message is written: your reasoning too, not only your reply, and the items' text."
        }

        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val TIME = Regex("""\d{1,2}:\d{2}""")
    }
}
