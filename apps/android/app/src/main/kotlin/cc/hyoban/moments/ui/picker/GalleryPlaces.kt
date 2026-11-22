package cc.hyoban.moments.ui.picker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.exifinterface.media.ExifInterface
import cc.hyoban.moments.logWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Where the gallery's photos were taken, for 地图's photos: the media store keeps no place, so
 * each photo's EXIF is read once, from its original, and kept on the phone ([file], out of its
 * backups like the footprints), by its id and size, so only a photo new or changed is read again.
 */
class GalleryLocations(context: Context) {
    private val context = context.applicationContext
    private val file = File(context.noBackupFilesDir, "gallery-places.tsv")
    private val lock = Mutex()

    /** Each photo read, by its id: its size when read, and where it was taken, or null for nowhere. */
    private var known: MutableMap<Long, Pair<Long, Pair<Double, Double>?>>? = null

    private val _located = MutableStateFlow<Map<Long, Pair<Double, Double>>>(emptyMap())

    /** The photos read that say where they were taken, by id. */
    val located: StateFlow<Map<Long, Pair<Double, Double>>> = _located

    private val _read = MutableStateFlow<Set<Long>>(emptySet())

    /** Every photo read, with a place or without one. */
    val read: StateFlow<Set<Long>> = _read

    /** Reads the photos of [items] not read yet, a few hundred at a time, each kept as it is read. */
    suspend fun scan(items: List<LocalItem>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val known = known ?: load().also { known = it }
            val missing = items.filter { !it.video && known[it.id]?.first != (it.bytes ?: -1L) }
            missing.chunked(PAGE).forEach { page ->
                val lines = StringBuilder()
                page.forEach { item ->
                    val at = runCatching {
                        context.contentResolver.openInputStream(LocalMedia.forMoment(context, item.uri))?.use { ExifInterface(it).latLong }
                    }.onFailure { logWarning("Moments", "gallery: a photo's place not read", it) }.getOrNull()
                        ?.takeIf { it[0] != 0.0 || it[1] != 0.0 }?.let { it[0] to it[1] }
                    known[item.id] = (item.bytes ?: -1L) to at
                    lines.append(item.id).append('\t').append(item.bytes ?: -1L).append('\t')
                        .append(at?.first ?: "").append('\t').append(at?.second ?: "").append('\n')
                }
                file.appendText(lines.toString())
                publish(known)
            }
            publish(known)
        }
    }

    private fun publish(known: Map<Long, Pair<Long, Pair<Double, Double>?>>) {
        _located.value = known.mapNotNull { (id, read) -> read.second?.let { id to it } }.toMap()
        _read.value = known.keys.toSet()
    }

    private fun load(): MutableMap<Long, Pair<Long, Pair<Double, Double>?>> {
        val read = HashMap<Long, Pair<Long, Pair<Double, Double>?>>()
        if (file.exists()) file.forEachLine { line ->
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@forEachLine
            val bytes = parts.getOrNull(1)?.toLongOrNull() ?: -1L
            val lat = parts.getOrNull(2)?.toDoubleOrNull()
            val lng = parts.getOrNull(3)?.toDoubleOrNull()
            read[id] = bytes to if (lat != null && lng != null) lat to lng else null
        }
        publish(read)
        return read
    }

    private companion object {
        const val PAGE = 200
    }
}

/**
 * The gallery's photos as places on 地图: those read so far, by where they were taken, when they
 * were taken for those that say nowhere, and the heap opened.
 */
internal class GalleryPlaces(private val gallery: GallerySource, private val locations: GalleryLocations) : Places {
    private var located by mutableStateOf<Map<Long, Pair<Double, Double>>>(emptyMap())
    private var read by mutableStateOf<Set<Long>>(emptySet())
    private var openedIds by mutableStateOf<List<Spot>?>(null)

    override val spots: List<Spot>
        get() {
            val located = located
            return gallery.items.mapNotNull { item ->
                located[item.id]?.let { (lat, lng) ->
                    Instant.ofEpochMilli(item.taken).atZone(ZoneId.systemDefault()).toLocalDate().let { day -> Spot(item.id, lat, lng, day.year, day = day.toEpochDay()) }
                }
            }
        }

    override val taken: Map<Long, Long>
        get() {
            val read = read
            return gallery.items.filter { !it.video && it.id in read }.associate { it.id to it.taken }
        }

    override val failed: (@Composable () -> Unit)? get() = null

    override suspend fun load() {
        // Every read shown as it comes, and the photos taken since read after them.
        kotlinx.coroutines.coroutineScope {
            val shown = launch { locations.located.collect { located = it } }
            val known = launch { locations.read.collect { read = it } }
            androidx.compose.runtime.snapshotFlow { gallery.items }.collect { items -> if (items.isNotEmpty()) locations.scan(items) }
            shown.cancel()
            known.cancel()
        }
    }

    override suspend fun thumbnail(spot: Spot): ImageBitmap? =
        gallery.items.firstOrNull { it.id == spot.id }?.let { GalleryPick(it, gallery).thumbnail(160) }

    override val opened get() = openedIds

    override fun open(heap: List<Spot>?) {
        openedIds = heap
    }

    @Composable
    override fun picks(): List<Pick> {
        val ids = openedIds?.mapTo(HashSet()) { it.id } ?: return emptyList()
        val items = gallery.items
        return remember(ids, items) { items.filter { it.id in ids }.sortedByDescending { it.taken }.map { GalleryPick(it, gallery) } }
    }

    override val reading get() = false

    override fun more() {}
}
