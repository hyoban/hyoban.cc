package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The app's copy of what the API serves, as files in the spec's layout under one
 * directory, with the revision it was last brought up to. The API is the truth; this is
 * what the screens show when it can't be reached.
 */
class DataStore(val root: File) {
    private val placesSerializer = MapSerializer(String.serializer(), Place.serializer())

    @Serializable
    private data class SyncState(val revision: Long)

    /** The API's revision the copy was last brought up to; 0 before the first sync. */
    fun readRevision(): Long = read("sync.json", SyncState.serializer())?.revision ?: 0

    fun writeRevision(revision: Long) = write("sync.json", SyncState.serializer(), SyncState(revision))

    fun readPlaces(): Map<String, Place> = read("places.json", placesSerializer) ?: emptyMap()

    fun writePlaces(places: Map<String, Place>) = write("places.json", placesSerializer, places.toSortedMap())

    fun readTrips(): List<TripRecord> = list("trips").map { file -> decode(file, TripRecord.serializer()) }

    /** Makes the trips on disk exactly these. */
    fun replaceTrips(trips: List<TripRecord>) {
        val kept = trips.map { "${it.id}.json" }.toSet()
        list("trips").filter { it.name !in kept }.forEach(File::delete)
        trips.forEach { write("trips/${it.id}.json", TripRecord.serializer(), it) }
    }

    fun readMoments(): List<MomentRecord> = list("moments").map { file -> decode(file, MomentRecord.serializer()) }

    fun writeMoment(moment: MomentRecord) = write(fileOf(moment), MomentRecord.serializer(), moment)

    fun deleteMoment(moment: MomentRecord) {
        File(root, fileOf(moment)).delete()
    }

    fun clearMoments() {
        File(root, "moments").deleteRecursively()
    }

    /** `moments/YYYY/MM/<uid>.json`, as the spec places it. */
    fun fileOf(moment: MomentRecord) =
        "moments/${moment.occurredAt.take(4)}/${moment.occurredAt.substring(5, 7)}/${moment.uid}.json"

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
