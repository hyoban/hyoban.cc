package cc.hyoban.moments.data.things

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.entryUid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

/**
 * A check-in with neither words nor photos: when, until when for one kept from a stay, the venue
 * it was at, that venue's place, and whether it is kept off the site.
 */
data class Checkin(val uid: String, val at: String, val venue: String, val place: String? = null, val end: String? = null, val private: Boolean = false) {
    /** The time it covers, in epoch milliseconds: from its start to its end, or [AROUND_MS] either side of a moment without one; null for a day alone. */
    val span: LongRange? by lazy {
        val start = millis(at) ?: return@lazy null
        val until = end?.let(::millis)
        if (until != null && until >= start) start..until else (start - AROUND_MS)..(start + AROUND_MS)
    }

    companion object {
        /** How far either side of a check-in with no end it is taken to cover, as a bill's footprints are looked for. */
        const val AROUND_MS = 15 * 60_000L
    }
}

private fun millis(stamp: String): Long? = runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull()

/** The check-ins alone among the entries, oldest first, by their day and by their place: worked out once as the entries change. */
class Checkins(all: List<Checkin> = emptyList()) {
    val byDay: Map<String, List<Checkin>> = all.groupBy { it.at.take(10) }
    /** Newest first, as a place lists them. */
    val byPlace: Map<String, List<Checkin>> = all.asReversed().mapNotNull { checkin -> checkin.place?.let { it to checkin } }.groupBy({ it.first }, { it.second })

    /**
     * Those whose time meets [start]..[end], epoch milliseconds, on the days around it: one that
     * started the day before (a night at a hotel) counts too.
     */
    fun overlapping(start: Long, end: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Checkin> {
        val first = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
        val last = java.time.Instant.ofEpochMilli(end).atZone(zone).toLocalDate().plusDays(1)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
            .flatMap { byDay[it.toString()].orEmpty() }
            .filter { checkin -> checkin.span?.let { it.first <= end && it.last >= start } == true }
            .toList()
    }

    /** The check-in with a place whose time covers [time], the shortest when several do: a shop's inside a mall's. */
    fun covering(time: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Checkin? =
        overlapping(time, time, zone).filter { it.place != null }
            .minByOrNull { checkin -> checkin.span!!.let { it.last - it.first } }

    companion object {
        fun of(entries: Collection<JsonObject>) = Checkins(entries.mapNotNull(::checkinOf).sortedBy { it.at })
    }
}

/** An entry's check-in when it is one alone: no words, so no Moment. */
fun checkinOf(entry: JsonObject): Checkin? {
    if (Entries.isMoment(entry)) return null
    val checkin = (entry["parts"] as? JsonObject)?.get("checkin") as? JsonObject ?: return null
    val text = { key: String -> (entry[key] as? JsonPrimitive)?.content }
    return Checkin(entry.entryUid, text("at") ?: return null, Entries.checkinName(checkin) ?: return null, text("place"), text("end"), Entries.isPrivate(entry))
}

/**
 * A check-in alone as the Moment the editor opens it as: no words nor photos, at its place, when
 * it was, public or private as it is, its check-in and the rest of the entry kept beside them.
 */
fun checkinAsMoment(entry: JsonObject): MomentRecord? {
    if (checkinOf(entry) == null) return null
    val parts = entry["parts"] as? JsonObject ?: return null
    val text = buildJsonObject {
        put("v", 1)
        put("text", "")
    }
    return Entries.toMoment(JsonObject(entry + ("parts" to JsonObject(parts + ("text" to text)))))
}

/**
 * What the editor keeps of [moment]: with words, photos or a meal, a Moment; with none, at a
 * place, a check-in there. The check-in it had stays, named [venue] (its place's name) when its
 * place is another now, and goes when it is at no place; one with nothing but its place becomes
 * a check-in of the phone's, named [venue].
 */
fun editedEntry(moment: MomentRecord, venue: String?, placeChanged: Boolean): JsonObject {
    val entry = Entries.fromMoment(moment)
    val parts = entry["parts"] as? JsonObject ?: JsonObject(emptyMap())
    val written = moment.text.isNotBlank() || moment.media.isNotEmpty() || moment.food != null
    val had = parts["checkin"] as? JsonObject
    val checkin = when {
        moment.places.isEmpty() || venue.isNullOrBlank() && had == null -> null
        had != null && (!placeChanged || venue.isNullOrBlank()) -> had
        had != null -> JsonObject(had - "alias" + ("venue" to JsonPrimitive(venue)))
        written -> null
        else -> buildJsonObject {
            put("v", 1)
            put("venue", venue)
            put("source", buildJsonObject { put("app", "phone") })
        }
    }
    val kept = (if (written) parts else parts - setOf("text", "media", "food")) - "checkin"
    return JsonObject(entry + ("parts" to JsonObject(if (checkin != null) kept + ("checkin" to checkin) else kept)))
}
