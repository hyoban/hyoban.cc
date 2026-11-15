package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import java.io.File

/** The API's records the copy holds beside the Moments and trips: entries by uid, collections by their key (slug, else uid in lowercase). */
data class Records(
    val revision: Long = 0,
    val entries: Map<String, JsonObject> = emptyMap(),
    val collections: Map<String, JsonObject> = emptyMap(),
    /** The car's statistics by their keys: a month, `<device>/<YYYY-MM>`, and an assisted-driving observation, `<device>/<captured at>`. */
    val vehicleMonths: Map<String, JsonObject> = emptyMap(),
    val vehicleAssisted: Map<String, JsonObject> = emptyMap(),
)

/**
 * What the phone keeps of its own as files under one directory: the places made on it, and its
 * notes of what it wrote and what it changed of the site's until that is published, every
 * record among them an entry, Moments too. The copy of what the API serves is [cc.hyoban.moments.data.copy.CopyStore].
 */
class DataStore(val root: File) {
    private val placesSerializer = MapSerializer(String.serializer(), Place.serializer())

    fun readPlaces(): Map<String, Place> = read("places.json", placesSerializer) ?: emptyMap()

    fun writePlaces(places: Map<String, Place>) = write("places.json", placesSerializer, places.toSortedMap())

    /** The site's places with a kind changed on the phone and not yet on the site, as they will be: the app's own note. */
    fun readChangedPlaces(): Map<String, Place> = read("changed-places.json", placesSerializer) ?: emptyMap()

    fun writeChangedPlaces(places: Map<String, Place>) = write("changed-places.json", placesSerializer, places.toSortedMap())

    /** The trips changed on the phone, as collections, by trip id, until published: the app's own note. */
    fun readChangedTrips(): Map<String, JsonObject> = read("changed-trips.json", MapSerializer(String.serializer(), JsonObject.serializer())) ?: emptyMap()

    fun writeChangedTrips(trips: Map<String, JsonObject>) = write("changed-trips.json", MapSerializer(String.serializer(), JsonObject.serializer()), trips.toSortedMap())

    @Serializable
    private data class KeptPlaces(val ids: List<String>)

    /** The places kept on their own on the phone, not made by a Moment: the app's own note, outside the spec. */
    fun readKeptPlaces(): Set<String> = read("kept-places.json", KeptPlaces.serializer())?.ids?.toSet().orEmpty()

    fun writeKeptPlaces(ids: Set<String>) = write("kept-places.json", KeptPlaces.serializer(), KeptPlaces(ids.sorted()))

    @Serializable
    private data class Removed(val uids: List<String>)

    /** The site's Moments an app from before noted as deleted on the phone, before such deletions were entries'. */
    fun readOldRemoved(): Set<String> = read(OLD_REMOVED, Removed.serializer())?.uids?.toSet().orEmpty()

    @Serializable
    private data class Held(val deleting: List<String> = emptyList(), val reverting: List<String> = emptyList())

    /**
     * The Moments being deleted, and the site's Moments whose change is being taken back, while
     * a snackbar can still undo it: the app's own note, outside the spec, so what the app was
     * closed on before the snackbar went is done when it next starts, as the snackbar going would.
     */
    fun readHeld(): Pair<Set<String>, Set<String>> =
        read("held.json", Held.serializer())?.let { it.deleting.toSet() to it.reverting.toSet() } ?: (emptySet<String>() to emptySet())

    fun writeHeld(deleting: Set<String>, reverting: Set<String>) =
        write("held.json", Held.serializer(), Held(deleting.sorted(), reverting.sorted()))

    @Serializable
    private data class ChangedEntries(val entries: Map<String, JsonObject> = emptyMap(), val removed: List<String> = emptyList())

    /**
     * The entries written or changed on the phone, Moments, check-ins and devices alike, by uid,
     * and the site's deleted there, until published: the app's own note.
     */
    fun readChangedEntries(): Pair<Map<String, JsonObject>, Set<String>> =
        read("changed-entries.json", ChangedEntries.serializer())?.let { it.entries to it.removed.toSet() } ?: (emptyMap<String, JsonObject>() to emptySet())

    fun writeChangedEntries(entries: Map<String, JsonObject>, removed: Set<String>) =
        write("changed-entries.json", ChangedEntries.serializer(), ChangedEntries(entries.toSortedMap(), removed.sorted()))

    /** The Moments an app from before kept as files of their own, `moments/YYYY/MM/<uid>.json`, before they were entries. */
    fun readOldMoments(): List<MomentRecord> = list(OLD_MOMENTS).map { file -> decode(file, MomentRecord.serializer()) }

    /** Those files and that note gone, once they are kept as entries. */
    fun dropOldMoments() {
        File(root, OLD_MOMENTS).deleteRecursively()
        File(root, OLD_REMOVED).delete()
    }

    /** Drops the whole copy, revision and all. */
    fun clear() {
        root.deleteRecursively()
    }

    private fun <T> read(path: String, serializer: KSerializer<T>): T? {
        val file = File(root, path)
        return if (file.exists()) decode(file, serializer) else null
    }

    private fun <T> decode(file: File, serializer: KSerializer<T>): T =
        Spec.json.decodeFromString(serializer, file.readText())

    private fun <T> write(path: String, serializer: KSerializer<T>, value: T) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(Spec.json.encodeToString(serializer, value) + "\n")
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun list(directory: String): List<File> =
        File(root, directory).walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
}

private const val OLD_MOMENTS = "moments"
private const val OLD_REMOVED = "removed.json"
