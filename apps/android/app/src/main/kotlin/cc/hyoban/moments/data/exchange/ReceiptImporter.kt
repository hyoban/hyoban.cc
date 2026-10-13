package cc.hyoban.moments.data.exchange

import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.store.MomentsRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A receipt is what `pnpm data:publish` sends back: the records it published, the
 * places they name, and the places it renamed. Taking it in follows three rules: rename
 * places first, copy `published` blocks the phone lacks, and adopt alt text the phone
 * left empty. Everything else stays as the phone has it.
 */
class ReceiptImporter(private val repository: MomentsRepository) {
    data class Receipt(
        val manifest: Manifest,
        val places: Map<String, Place>,
        val trips: List<TripRecord>,
        val moments: List<MomentRecord>,
    )

    data class Summary(val renamed: Int, val published: Int, val added: Int)

    suspend fun apply(receipt: Receipt): Summary {
        var renamed = 0
        var published = 0
        var added = 0

        for ((from, to) in receipt.manifest.renamedPlaces.orEmpty()) {
            if (to !in repository.places.value) {
                receipt.places[to]?.let { repository.savePlace(to, it) }
            }
            if (to in repository.places.value && from in repository.places.value) {
                repository.renamePlace(from, to)
                renamed += 1
            }
        }

        for ((id, place) in receipt.places) {
            if (id !in repository.places.value) repository.savePlace(id, place)
        }

        for (trip in receipt.trips) {
            if (repository.trips.value.none { it.id == trip.id }) repository.saveTrip(trip)
        }

        for (theirs in receipt.moments) {
            val mine = repository.moment(theirs.uid)
            if (mine == null) {
                repository.saveMoment(theirs.copy(media = theirs.media.map { it.copy(local = null) }))
                added += 1
                continue
            }
            val merged = mine.copy(
                published = mine.published ?: theirs.published,
                media = mine.media.map { item ->
                    val their = theirs.media.firstOrNull { it.uid == item.uid } ?: return@map item
                    item.copy(
                        published = item.published ?: their.published,
                        alt = item.alt.ifBlank { their.alt },
                    )
                },
            )
            if (merged != mine) {
                repository.saveMoment(merged)
                published += 1
            }
        }

        return Summary(renamed, published, added)
    }

    companion object {
        fun read(zip: InputStream): Receipt = ZipInputStream(zip).use { input ->
            val entries = mutableMapOf<String, ByteArray>()
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name.removePrefix("./")] = input.readBytes()
                input.closeEntry()
            }
            read(entries)
        }

        fun read(entries: Map<String, ByteArray>): Receipt {
            val manifest = Spec.json.decodeFromString(
                Manifest.serializer(),
                entries["manifest.json"]?.decodeToString() ?: error("The receipt has no manifest."),
            )
            require(manifest.schemaVersion == Spec.SCHEMA_VERSION) {
                "The receipt is schema version ${manifest.schemaVersion}; this app reads version ${Spec.SCHEMA_VERSION}."
            }
            return Receipt(
                manifest = manifest,
                places = entries["places.json"]?.let {
                    Spec.json.decodeFromString(MapSerializer(String.serializer(), Place.serializer()), it.decodeToString())
                }.orEmpty(),
                trips = entries.filterKeys { it.startsWith("trips/") && it.endsWith(".json") }.values
                    .map { Spec.json.decodeFromString(TripRecord.serializer(), it.decodeToString()) },
                moments = entries.filterKeys { it.startsWith("moments/") && it.endsWith(".json") }.values
                    .map { Spec.json.decodeFromString(MomentRecord.serializer(), it.decodeToString()) },
            )
        }
    }
}
