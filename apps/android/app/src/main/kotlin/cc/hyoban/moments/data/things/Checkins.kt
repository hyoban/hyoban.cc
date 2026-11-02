package cc.hyoban.moments.data.things

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.entryUid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A check-in with neither words nor photos: when, the venue it was at, and that venue's place. */
data class Checkin(val uid: String, val at: String, val venue: String, val place: String? = null)

/** The check-ins alone among the entries, oldest first, by their day and by their place: worked out once as the entries change. */
class Checkins(all: List<Checkin> = emptyList()) {
    val byDay: Map<String, List<Checkin>> = all.groupBy { it.at.take(10) }
    /** Newest first, as a place lists them. */
    val byPlace: Map<String, List<Checkin>> = all.asReversed().mapNotNull { checkin -> checkin.place?.let { it to checkin } }.groupBy({ it.first }, { it.second })

    companion object {
        fun of(entries: Collection<JsonObject>) = Checkins(entries.mapNotNull(::checkinOf).sortedBy { it.at })
    }
}

/** An entry's check-in when it is one alone: no words, so no Moment. */
fun checkinOf(entry: JsonObject): Checkin? {
    if (Entries.isMoment(entry)) return null
    val checkin = (entry["parts"] as? JsonObject)?.get("checkin") as? JsonObject ?: return null
    val text = { key: String -> (entry[key] as? JsonPrimitive)?.content }
    return Checkin(entry.entryUid, text("at") ?: return null, Entries.checkinName(checkin) ?: return null, text("place"))
}
