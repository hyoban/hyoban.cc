package cc.hyoban.moments.data.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// The spec's second shape (packages/data-spec/src/entry.ts): every record an entry, a moment
// and an optional point, what it holds parts by plugin (words, photos, a meal, a bill, a
// charge), a trip a collection, and public spans of the footprints. The app keeps its Moments
// in the first shape and writes them as entries when it publishes (`POST /v2/changes`), the
// API keeping both; these are the same mappings as the spec's, kept as JSON so a part or a
// field this version doesn't know goes through as it came.

/** Who sees something. Two for now; written as the spec's `visibility`, to widen to an audience. */
enum class Visibility(val value: String) {
    PUBLIC("public"),
    PRIVATE("private");

    companion object {
        fun of(value: String?): Visibility? = entries.firstOrNull { it.value == value }
    }
}

object Entries {
    private val momentKeys = setOf(
        "uid", "occurredAt", "places", "text", "hidden", "topic", "media", "food", "trip", "order",
        "published", "createdAt", "updatedAt",
    )
    private val entryKeys = setOf(
        "uid", "at", "end", "point", "place", "parts", "links", "visibility", "topic", "order",
        "published", "createdAt", "updatedAt",
    )

    /**
     * A Moment as an entry, as the spec's `momentToEntry`: its words, photos and meal as parts,
     * a meal's bill its cost and people, and private when hidden; [parts] are added beside them
     * (a public bill of the meal). Fields the Moment kept that the spec doesn't know stay.
     */
    fun fromMoment(moment: MomentRecord, parts: Map<String, JsonObject> = emptyMap()): JsonObject {
        val record = Spec.json.encodeToJsonElement(MomentRecord.serializer(), moment).jsonObject
        return buildJsonObject {
            record.filterKeys { it !in momentKeys }.forEach { (key, value) -> put(key, value) }
            put("uid", moment.uid)
            put("at", moment.occurredAt)
            moment.places.firstOrNull()?.let { put("place", it) }
            put("parts", buildJsonObject {
                put("text", buildJsonObject {
                    put("v", 1)
                    put("text", moment.text)
                })
                if (moment.media.isNotEmpty()) {
                    put("media", buildJsonObject {
                        put("v", 1)
                        put("items", record.getValue("media"))
                    })
                }
                record["food"]?.jsonObject?.let { food ->
                    put("food", buildJsonObject {
                        put("v", 1)
                        food.filterKeys { it != "bill" }.forEach { (key, value) -> put(key, value) }
                        food["bill"]?.jsonObject?.let { bill ->
                            bill["amount"]?.let { put("cost", it) }
                            bill["people"]?.let { put("people", it) }
                        }
                    })
                }
                parts.forEach { (plugin, part) -> put(plugin, part) }
            })
            if (moment.hidden) put("visibility", Visibility.PRIVATE.value)
            moment.topic?.let { put("topic", it) }
            moment.order?.let { put("order", it) }
            record["published"]?.let { put("published", it) }
            put("createdAt", moment.createdAt)
            put("updatedAt", moment.updatedAt)
        }
    }

    /**
     * An entry as a Moment, as the spec's `entryToMoment`, or null for one with no words (a
     * bill alone, a mark): its words, photos and meal, hidden when private by its own say.
     */
    fun toMoment(entry: JsonObject): MomentRecord? {
        val parts = entry["parts"]?.jsonObject ?: return null
        val text = parts["text"]?.jsonObject ?: return null
        val food = parts["food"]?.jsonObject?.takeUnless { it.visibility() == Visibility.PRIVATE }
        val media = parts["media"]?.jsonObject?.takeUnless { it.visibility() == Visibility.PRIVATE }
        val record = buildJsonObject {
            entry.filterKeys { it !in entryKeys }.forEach { (key, value) -> put(key, value) }
            put("uid", entry.string("uid"))
            put("occurredAt", entry.string("at"))
            put("places", JsonArray(listOfNotNull(entry["place"])))
            put("text", text["text"] ?: JsonPrimitive(""))
            put("hidden", entry.visibility() == Visibility.PRIVATE)
            entry["topic"]?.let { put("topic", it) }
            put("media", JsonArray(media?.get("items")?.jsonArray?.map { item -> JsonObject(item.jsonObject - "point") }.orEmpty()))
            if (food != null) {
                put("food", buildJsonObject {
                    food.filterKeys { it !in setOf("v", "visibility", "cost", "people") }.forEach { (key, value) -> put(key, value) }
                    food["cost"]?.let { cost ->
                        put("bill", buildJsonObject {
                            put("amount", cost)
                            food["people"]?.let { put("people", it) }
                        })
                    }
                })
            }
            entry["order"]?.let { put("order", it) }
            entry["published"]?.let { put("published", it) }
            put("createdAt", entry.string("createdAt"))
            put("updatedAt", entry.string("updatedAt"))
        }
        return Spec.json.decodeFromJsonElement(MomentRecord.serializer(), record)
    }

    /** A bill on its own as an entry: the ledger's, at its time, with nothing but its public part. */
    fun bill(uid: String, at: String, place: String?, bill: JsonObject, createdAt: String, updatedAt: String): JsonObject = buildJsonObject {
        put("uid", uid)
        put("at", at)
        place?.let { put("place", it) }
        put("parts", buildJsonObject { put("bill", bill) })
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    /**
     * A bill's part as it may leave the phone: what is public of it alone, the amount in the
     * currency's unit, its kind, category and note; never its account, book or balance.
     */
    fun billPart(kind: String, amount: Double, currency: String, category: String?, note: String?): JsonObject = buildJsonObject {
        put("v", 1)
        put("visibility", Visibility.PUBLIC.value)
        put("kind", kind)
        put("amount", amount)
        put("currency", currency)
        category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
        note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
    }

    /** A trip's collection uid, as the spec's migration named it: stable from its creation and id. */
    fun collectionUid(trip: TripRecord): String = stableUlid(trip.createdAt, "trip:${trip.id}")

    /**
     * The spec's `getStableUlid`: the time's milliseconds, then 80 bits of the seed's FNV-1a
     * hashes, so the same time and seed name the same thing on every side.
     */
    fun stableUlid(time: String, seed: String): String {
        var milliseconds = java.time.OffsetDateTime.parse(if (time.length == 10) "${time}T00:00:00Z" else time).toInstant().toEpochMilli()
        val head = StringBuilder()
        repeat(10) {
            head.insert(0, CROCKFORD[(milliseconds % 32).toInt()])
            milliseconds /= 32
        }
        val tail = StringBuilder()
        var round = 0
        while (tail.length < 16) {
            var hash = 0x811c9dc5L
            "$round:$seed".codePoints().forEach { point ->
                hash = hash xor point.toLong()
                hash = (hash * 0x01000193L) and 0xffffffffL
            }
            repeat(6) {
                if (tail.length < 16) {
                    tail.append(CROCKFORD[(hash and 31).toInt()])
                    hash = hash ushr 5
                }
            }
            round++
        }
        return head.toString() + tail
    }

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Whether an entry is a Moment, or has none (a bill, a charge, a mark). */
    fun isMoment(entry: JsonObject): Boolean = entry["parts"]?.jsonObject?.containsKey("text") == true

    private fun JsonObject.visibility(): Visibility? = Visibility.of((this["visibility"] as? JsonPrimitive)?.contentOrNull)

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}

/** A record of the second shape, kept as JSON, read as far as the app needs to. */
val JsonElement.entryUid: String get() = jsonObject.getValue("uid").jsonPrimitive.content
