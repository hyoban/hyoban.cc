package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.local.Fix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale

// How long and far a way is, as a router answered, kept on the device past a restart so the same
// way is never asked twice: by its two ends to about ten metres and its mode. An answer that there
// is no way is kept too; a failure to ask is not.

/** What is known of a way: its time and length, or that there is none ([travel] null). */
data class KnownTravel(val travel: Routers.Travel?)

object TravelCache {
    /** How many ways are kept, the least lately asked going first past it. */
    private const val KEPT = 5_000

    @Volatile
    private var file: File? = null
    private var loaded = false
    private val kept = object : LinkedHashMap<String, Routers.Travel?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Routers.Travel?>) = size > KEPT
    }

    /** Where the ways are kept; without it, none are, past the run. */
    fun open(at: File) {
        synchronized(kept) {
            file = at
            kept.clear()
            loaded = false
        }
    }

    /** A way's key: its ends to four decimals, about ten metres, and its mode. */
    fun key(from: Fix, to: Fix, by: Routers.By): String =
        String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f@%s", from.latitude, from.longitude, to.latitude, to.longitude, by.name.lowercase())

    /** What is known of the way [from] [to] [by] a mode; null when it was never asked. */
    suspend fun known(from: Fix, to: Fix, by: Routers.By): KnownTravel? {
        load()
        val key = key(from, to, by)
        return synchronized(kept) { if (key in kept) KnownTravel(kept[key]) else null }
    }

    /** Keeps what a router answered of the way [from] [to] [by] a mode. */
    suspend fun keep(from: Fix, to: Fix, by: Routers.By, travel: Routers.Travel?) {
        load()
        val json = synchronized(kept) {
            kept[key(from, to, by)] = travel
            JsonObject(kept.mapValues { (_, it) -> it?.let { JsonArray(listOf(JsonPrimitive(it.seconds), JsonPrimitive(it.metres))) } ?: JsonNull })
        }
        val at = file ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                at.parentFile?.mkdirs()
                val next = File(at.parentFile, at.name + ".tmp")
                next.writeText(json.toString())
                if (!next.renameTo(at)) at.writeText(json.toString())
            }
        }
    }

    private suspend fun load() {
        if (loaded) return
        val at = file
        val read = if (at == null || !at.exists()) null else withContext(Dispatchers.IO) { runCatching { Json.parseToJsonElement(at.readText()) as? JsonObject }.getOrNull() }
        synchronized(kept) {
            if (loaded) return
            read?.forEach { (key, value) ->
                val pair = value as? JsonArray
                kept[key] = pair?.let {
                    val seconds = (it.getOrNull(0) as? JsonPrimitive)?.longOrNull
                    val metres = (it.getOrNull(1) as? JsonPrimitive)?.longOrNull
                    if (seconds != null && metres != null) Routers.Travel(seconds, metres) else null
                }
            }
            loaded = true
        }
    }
}
