package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The app's data, as files in the spec's layout under one directory: the truth the
 * screens read and write. Backing it up is copying the directory.
 */
class DataStore(val root: File) {
    private val placesSerializer = MapSerializer(String.serializer(), Place.serializer())

    fun readManifest(): Manifest? = read("manifest.json", Manifest.serializer())

    fun writeManifest(manifest: Manifest) = write("manifest.json", Manifest.serializer(), manifest)

    fun readPlaces(): Map<String, Place> = read("places.json", placesSerializer) ?: emptyMap()

    fun writePlaces(places: Map<String, Place>) = write("places.json", placesSerializer, places.toSortedMap())

    fun readTrips(): List<TripRecord> = list("trips").map { file -> decode(file, TripRecord.serializer()) }

    fun writeTrip(trip: TripRecord) = write("trips/${trip.id}.json", TripRecord.serializer(), trip)

    fun readMoments(): List<MomentRecord> = list("moments").map { file -> decode(file, MomentRecord.serializer()) }

    fun writeMoment(moment: MomentRecord) = write(fileOf(moment), MomentRecord.serializer(), moment)

    fun deleteMoment(moment: MomentRecord) {
        File(root, fileOf(moment)).delete()
    }

    /** `moments/YYYY/MM/<uid>.json`, as the spec places it. */
    fun fileOf(moment: MomentRecord) =
        "moments/${moment.occurredAt.take(4)}/${moment.occurredAt.substring(5, 7)}/${moment.uid}.json"

    private fun <T> read(path: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val file = File(root, path)
        return if (file.exists()) decode(file, serializer) else null
    }

    private fun <T> decode(file: File, serializer: kotlinx.serialization.KSerializer<T>): T =
        Spec.json.decodeFromString(serializer, file.readText())

    private fun <T> write(path: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
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
