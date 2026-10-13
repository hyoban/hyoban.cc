package cc.hyoban.moments.data.exchange

import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.Timestamps
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a bundle: the records in the spec's layout and, under `media/`, the originals
 * of media that isn't published yet, named by media uid. A backup is the same with
 * every media file and the `local` blocks kept.
 */
class BundleWriter(private val media: MediaFiles, private val generator: String) {
    data class Options(
        /** Keep each media item's `local` block (a backup) rather than strip it (a bundle). */
        val includeLocal: Boolean = false,
        /** Pack every media file, published or not. */
        val allMedia: Boolean = false,
    )

    data class Result(
        val moments: Int,
        val mediaFiles: Int,
        /** `<moment uid>/<media uid>` of media whose file couldn't be opened. */
        val missing: List<String>,
    )

    fun write(
        output: OutputStream,
        places: Map<String, Place>,
        trips: List<TripRecord>,
        moments: List<MomentRecord>,
        options: Options = Options(),
    ): Result {
        val missing = mutableListOf<String>()
        var mediaFiles = 0
        val named = buildSet {
            moments.forEach { addAll(it.places) }
            trips.forEach { trip ->
                trip.waypoints.forEach { add(it.place) }
                trip.straightLegs.forEach { add(it.from); add(it.to) }
                trip.sameWayBack.forEach { add(it.from); add(it.to) }
            }
        }

        ZipOutputStream(output).use { zip ->
            zip.putJson("manifest.json", Manifest.serializer(), Manifest(Spec.SCHEMA_VERSION, generator, Timestamps.now()))
            zip.putJson(
                "places.json",
                MapSerializer(String.serializer(), Place.serializer()),
                places.filterKeys { it in named }.toSortedMap(),
            )
            trips.sortedBy { it.id }.forEach { zip.putJson("trips/${it.id}.json", TripRecord.serializer(), it) }

            moments.sortedBy { it.uid }.forEach { moment ->
                val stripped = if (options.includeLocal) moment else moment.copy(media = moment.media.map { it.copy(local = null) })
                zip.putJson(fileOf(moment), MomentRecord.serializer(), stripped)

                moment.media.filter { options.allMedia || it.published == null }.forEach { item ->
                    val local = item.local
                    val wanted = buildList {
                        add(local?.uri)
                        if (item.type == MediaType.LIVE_PHOTO) add(local?.videoUri)
                    }
                    for (uri in wanted) {
                        val stream = uri?.let(media::open)
                        if (stream == null) {
                            missing += "${moment.uid}/${item.uid}"
                            break
                        }
                        val extension = media.extensionOf(uri) ?: if (uri == local?.videoUri) "mp4" else "jpg"
                        zip.putNextEntry(ZipEntry("media/${item.uid}.$extension"))
                        stream.use { it.copyTo(zip) }
                        zip.closeEntry()
                        mediaFiles += 1
                    }
                }
            }
        }

        return Result(moments.size, mediaFiles, missing)
    }

    private fun <T> ZipOutputStream.putJson(name: String, serializer: KSerializer<T>, value: T) {
        putNextEntry(ZipEntry(name))
        write((Spec.json.encodeToString(serializer, value) + "\n").toByteArray())
        closeEntry()
    }

    companion object {
        fun fileOf(moment: MomentRecord) =
            "moments/${moment.occurredAt.take(4)}/${moment.occurredAt.substring(5, 7)}/${moment.uid}.json"
    }
}
