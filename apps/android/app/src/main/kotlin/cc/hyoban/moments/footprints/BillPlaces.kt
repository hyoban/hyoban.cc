package cc.hyoban.moments.footprints

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.nameAt
import cc.hyoban.moments.ledger.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a bill was paid, read off the footprints: the sample nearest its time, named by Photon as
 * a new Moment's place is. Only the point goes out, nothing of the bill; the name is kept beside
 * the footprints so each spot is asked about once, and a spot Photon couldn't be reached for is
 * asked about again next time the app runs.
 */
class BillPlaces(private val footprints: FootprintStore, private val search: PlaceSearch) {
    private val names = ConcurrentHashMap<String, String>()
    private val unreachable = ConcurrentHashMap.newKeySet<String>()
    private val asking = Mutex()

    /** The name of where the footprints were at [time], or null where they weren't kept or nothing there has one. */
    suspend fun nameAt(time: Long): String? = withContext(Dispatchers.IO) {
        val point = footprints.pointNear(time, WITHIN_MS, ACCURACY_M) ?: return@withContext null
        val cell = String.format(Locale.ROOT, "%.4f,%.4f", point.latitude, point.longitude)
        val known = names[cell] ?: footprints.placeName(cell)?.also { names[cell] = it }
        if (known != null) return@withContext known.ifEmpty { null }
        if (cell in unreachable) return@withContext null
        // One question at a time, so a list of bills doesn't flood Photon.
        asking.withLock {
            names[cell]?.let { return@withLock it.ifEmpty { null } }
            val found = runCatching { search.nearby(Fix(point.latitude, point.longitude)) }.getOrElse {
                unreachable += cell
                return@withLock null
            }
            // Nothing named there is kept too, as an empty name, so it isn't asked again.
            val name = nameAt(found).orEmpty()
            names[cell] = name
            footprints.savePlaceName(cell, name)
            name.ifEmpty { null }
        }
    }

    companion object {
        /** How far either side of a bill's time a sample may be: the recorder samples sparsely while still. */
        private const val WITHIN_MS = 15 * 60_000L
        private const val ACCURACY_M = 100.0
    }
}

/** The app's [BillPlaces], for the rows of any list of bills; none where the app doesn't give it. */
val LocalBillPlaces = staticCompositionLocalOf<BillPlaces?> { null }

/** Where the footprints say [txn] was paid, for a bill with no place of its own nor a Moment's; null until it is known. */
@Composable
fun footprintPlace(txn: Txn): String? {
    val places = LocalBillPlaces.current
    val wanted = places != null && txn.place == null && txn.moment == null
    return produceState<String?>(null, places, wanted, txn.time) {
        value = if (wanted) places!!.nameAt(txn.time) else null
    }.value
}
