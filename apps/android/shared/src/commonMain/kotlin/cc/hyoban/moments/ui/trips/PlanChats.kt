package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.data.ai.FoundPlace
import cc.hyoban.moments.data.ai.PlanItem
import cc.hyoban.moments.data.ai.PlanLookup
import cc.hyoban.moments.data.ai.PlanPlace
import cc.hyoban.moments.data.ai.WayEnd
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PlaceSource
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.data.ai.PlanAssistant
import cc.hyoban.moments.data.trips.Routers
import cc.hyoban.moments.ui.compose.PlaceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

// The plans' conversations, kept on the device in one file beside the ledger and the footprints
// (and so in their WebDAV backup): each plan's turns as shown, and what the model remembers,
// saved shortly after anything in them changes, so closing the app loses nothing but the word
// being streamed. An answer cut off by that comes back stopped.

/** Every plan's conversation, kept in [file]; changes written a moment after they settle, on [scope]. */
class PlanChats(private val file: File, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val open = HashMap<String, PlanChat>()

    /** What the file held for the plans not opened since, written back as it was. */
    private var kept: MutableMap<String, JsonElement>? = null
    private var writing: Job? = null

    private fun kept(): MutableMap<String, JsonElement> = kept ?: runCatching {
        (json.parseToJsonElement(file.readText()) as JsonObject).toMutableMap()
    }.getOrElse { mutableMapOf() }.also { kept = it }

    /** [trip]'s conversation, as it was left. */
    fun of(trip: String): PlanChat = synchronized(open) {
        open.getOrPut(trip) {
            val chat = (kept()[trip] as? JsonObject)?.let { runCatching { chatOf(trip, it) }.getOrNull() } ?: PlanChat(trip)
            chat.also { it.changed = ::save }
        }
    }

    /** Forgets the conversations of plans that are gone, given every plan there is. */
    fun prune(trips: Set<String>) {
        if (trips.isEmpty()) return
        val gone = synchronized(open) {
            (kept().keys + open.keys).filter { it !in trips }.onEach {
                open.remove(it)
                kept().remove(it)
            }
        }
        if (gone.isNotEmpty()) save()
    }

    /** Writes every conversation a moment from now, once what changes now has settled. */
    fun save() {
        writing?.cancel()
        writing = scope.launch {
            delay(SETTLE_MS)
            val all = synchronized(open) { kept().toMutableMap().apply { open.forEach { (trip, chat) -> put(trip, chatJson(chat)) } } }
            val text = JsonObject(all).toString()
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(file)) {
                    file.writeText(text)
                    temporary.delete()
                }
            }
        }
    }

    companion object {
        private const val SETTLE_MS = 400L

        fun chatJson(chat: PlanChat): JsonObject = buildJsonObject {
            put("draft", chat.draft)
            put("memory", JsonArray(synchronized(chat.memory) { chat.memory.map { JsonArray(it) } }))
            put(
                "turns",
                JsonArray(
                    chat.turns.map { turn ->
                        when (turn) {
                            is PlanTurn.Asked -> buildJsonObject { put("asked", turn.text) }
                            is PlanTurn.Answered -> buildJsonObject { put("answer", answerJson(turn, growing = chat.thinking && turn === chat.turns.lastOrNull())) }
                        }
                    },
                ),
            )
        }

        fun chatOf(trip: String, saved: JsonObject): PlanChat = PlanChat(trip).apply {
            draft = text(saved["draft"]).orEmpty()
            (saved["memory"] as? JsonArray)?.forEach { turn -> (turn as? JsonArray)?.let { memory += it.mapNotNull { message -> message as? JsonObject } } }
            (saved["turns"] as? JsonArray)?.forEach { element ->
                val turn = element as? JsonObject ?: return@forEach
                text(turn["asked"])?.let { turns += PlanTurn.Asked(it) }
                (turn["answer"] as? JsonObject)?.let { turns += answerOf(it) }
            }
        }

        private fun answerJson(turn: PlanTurn.Answered, growing: Boolean) = buildJsonObject {
            put("text", turn.text)
            put("stopped", turn.stopped)
            put("retired", turn.retired)
            // An answer still coming when last written: the app went before it ended.
            put("done", !growing)
            turn.thoughtFor?.let { put("thoughtFor", it) }
            put(
                "steps",
                JsonArray(
                    turn.steps.map { step ->
                        when (step) {
                            is PlanStep.Thought -> buildJsonObject {
                                put("round", step.round)
                                put("thought", step.text)
                            }
                            is PlanStep.Look -> buildJsonObject { put("look", lookupJson(step.lookup)) }
                        }
                    },
                ),
            )
            put("suggestions", JsonArray(turn.suggestions.map(::suggestionJson)))
        }

        private fun answerOf(saved: JsonObject) = PlanTurn.Answered().apply {
            text = text(saved["text"]).orEmpty()
            retired = bool(saved["retired"])
            stopped = bool(saved["stopped"]) || saved["done"]?.let { !bool(it) } == true
            thoughtFor = (saved["thoughtFor"] as? JsonPrimitive)?.intOrNull
            (saved["steps"] as? JsonArray)?.forEach { element ->
                val step = element as? JsonObject ?: return@forEach
                text(step["thought"])?.let { thought((step["round"] as? JsonPrimitive)?.intOrNull ?: 0, it) }
                (step["look"] as? JsonObject)?.let { lookupOf(it) }?.let { steps += PlanStep.Look(it) }
            }
            // Stopped before it said how long it thought: it thought until then.
            if (stopped && thoughtFor == null && worked) thoughtFor = 0
            // A change that came to nothing once its place was found is not kept.
            (saved["suggestions"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let { suggestionOf(it) }?.takeUnless { it.added == null && changesNothing(it) }?.let { suggestions += it }
            }
        }

        private fun lookupJson(lookup: PlanLookup) = when (lookup) {
            is PlanLookup.Place -> buildJsonObject {
                put("kind", "place")
                put("id", lookup.id)
                put("name", lookup.name)
                put("near", fixJson(lookup.near))
                lookup.found?.let { found ->
                    put(
                        "found",
                        JsonArray(
                            found.map { place ->
                                buildJsonObject {
                                    put("name", place.name)
                                    place.address?.let { put("address", it) }
                                    put("at", fixJson(place.fix))
                                }
                            },
                        ),
                    )
                }
                put("failed", lookup.failed)
            }
            is PlanLookup.Way -> buildJsonObject {
                put("kind", "way")
                put("id", lookup.id)
                put("from", endJson(lookup.from))
                put("to", endJson(lookup.to))
                put("by", lookup.by.name)
                lookup.travel?.let {
                    put("seconds", it.seconds)
                    put("metres", it.metres)
                }
                put("done", lookup.done)
                put("failed", lookup.failed)
            }
        }

        private fun lookupOf(saved: JsonObject): PlanLookup? {
            val id = text(saved["id"]) ?: return null
            // A lookup cut off when the app went: it came to nothing.
            return when (text(saved["kind"])) {
                "place" -> {
                    val found = (saved["found"] as? JsonArray)?.mapNotNull { element ->
                        val place = element as? JsonObject ?: return@mapNotNull null
                        FoundPlace(text(place["name"]) ?: return@mapNotNull null, text(place["address"]), fixOf(place["at"]) ?: return@mapNotNull null)
                    }
                    PlanLookup.Place(id, text(saved["name"]) ?: return null, fixOf(saved["near"]) ?: return null, found, bool(saved["failed"]) || found == null)
                }
                "way" -> {
                    val seconds = (saved["seconds"] as? JsonPrimitive)?.longOrNull
                    val metres = (saved["metres"] as? JsonPrimitive)?.longOrNull
                    val done = bool(saved["done"])
                    PlanLookup.Way(
                        id,
                        endOf(saved["from"]) ?: return null,
                        endOf(saved["to"]) ?: return null,
                        PlanAssistant.byOf(text(saved["by"])) ?: Routers.By.DRIVE,
                        if (seconds != null && metres != null) Routers.Travel(seconds, metres) else null,
                        done = true,
                        failed = bool(saved["failed"]) || !done,
                    )
                }
                else -> null
            }
        }

        private fun suggestionJson(suggestion: PlanSuggestion) = buildJsonObject {
            val item = suggestion.item
            put(
                "item",
                buildJsonObject {
                    put("day", item.day)
                    item.time?.let { put("time", it) }
                    put("text", item.text)
                    item.place?.let { place ->
                        put(
                            "place",
                            buildJsonObject {
                                put("name", place.name)
                                place.near?.let { put("near", fixJson(it)) }
                            },
                        )
                    }
                    item.edits?.let { put("edits", it) }
                    item.by?.let { put("by", it.name) }
                    put("delete", item.delete)
                },
            )
            suggestion.target?.let { put("target", Json.encodeToJsonElement(MomentRecord.serializer(), it)) }
            suggestion.targetPlace?.let { put("targetPlace", it) }
            if (suggestion.checkin) put("checkin", true)
            put("spot", spotJson(suggestion.spot))
            suggestion.added?.let { put("added", it) }
        }

        private fun suggestionOf(saved: JsonObject): PlanSuggestion? {
            val item = saved["item"] as? JsonObject ?: return null
            val place = (item["place"] as? JsonObject)?.let { place -> text(place["name"])?.let { PlanPlace(it, fixOf(place["near"])) } }
            val by = PlanAssistant.byOf(text(item["by"]))
            val planItem = PlanItem(text(item["day"]).orEmpty(), text(item["time"]), text(item["text"]).orEmpty(), place, text(item["edits"]), bool(item["delete"]), by)
            val target = (saved["target"] as? JsonObject)?.let { runCatching { Json.decodeFromJsonElement(MomentRecord.serializer(), it) }.getOrNull() }
            if (planItem.edits != null && target == null) return null
            return PlanSuggestion(planItem, target, text(saved["targetPlace"]), bool(saved["checkin"])).apply {
                spot = spotOf(saved["spot"] as? JsonObject)
                added = text(saved["added"])
            }
        }

        private fun spotJson(spot: PlanSpot) = when (spot) {
            is PlanSpot.At -> buildJsonObject {
                put("name", spot.name)
                when (val choice = spot.choice) {
                    is PlaceChoice.Known -> {
                        put("at", "known")
                        put("id", choice.id)
                    }
                    is PlaceChoice.New -> {
                        put("at", "new")
                        put("fix", fixJson(choice.fix))
                        choice.kind?.let { put("kind", it.name) }
                        choice.source?.let {
                            put("app", it.app)
                            put("venue", it.venue)
                        }
                    }
                    PlaceChoice.None -> put("at", "nowhere")
                }
            }
            PlanSpot.Finding -> buildJsonObject { put("at", "finding") }
            PlanSpot.Missing -> buildJsonObject { put("at", "missing") }
            PlanSpot.Nowhere -> buildJsonObject { put("at", "nowhere") }
        }

        private fun spotOf(saved: JsonObject?): PlanSpot = when (text(saved?.get("at"))) {
            "known" -> PlanSpot.At(PlaceChoice.Known(text(saved!!["id"]).orEmpty()), text(saved["name"]).orEmpty())
            "new" -> {
                val fix = fixOf(saved!!["fix"])
                val app = text(saved["app"])
                val venue = text(saved["venue"])
                if (fix == null) PlanSpot.Missing
                else PlanSpot.At(
                    PlaceChoice.New(
                        text(saved["name"]).orEmpty(),
                        fix,
                        text(saved["kind"])?.let { kind -> PlaceKind.entries.firstOrNull { it.name == kind } },
                        if (app != null && venue != null) PlaceSource(app, venue) else null,
                    ),
                    text(saved["name"]).orEmpty(),
                )
            }
            "missing" -> PlanSpot.Missing
            "nowhere" -> PlanSpot.Nowhere
            // Still being looked for when the app went: looked for again on the page.
            else -> PlanSpot.Finding
        }

        private fun fixJson(fix: Fix) = buildJsonObject {
            put("lat", fix.latitude)
            put("lng", fix.longitude)
        }

        private fun fixOf(element: JsonElement?): Fix? {
            val at = element as? JsonObject ?: return null
            return Fix((at["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null, (at["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null)
        }

        private fun endJson(end: WayEnd) = buildJsonObject {
            put("name", end.name)
            put("at", fixJson(end.fix))
        }

        private fun endOf(element: JsonElement?): WayEnd? {
            val end = element as? JsonObject ?: return null
            return WayEnd(text(end["name"]) ?: return null, fixOf(end["at"]) ?: return null)
        }

        private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

        private fun bool(element: JsonElement?): Boolean = (element as? JsonPrimitive)?.booleanOrNull == true
    }
}
