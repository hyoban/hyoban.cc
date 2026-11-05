package cc.hyoban.moments.footprints

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.fix
import cc.hyoban.moments.data.local.nameAt
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.things.Checkins
import cc.hyoban.moments.ledger.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Where a bill with no place of its own was, as worked out for it, and how that is known. */
data class BillSpot(
    val name: String,
    /** The known place it is, ready to be the bill's; null for a spot that is only named. */
    val place: String?,
    /** The footprints' point, for a spot to be kept as a new place; null for a check-in's. */
    val point: Fix?,
    val from: From,
) {
    enum class From { CHECKIN, FOOTPRINTS }
}

/**
 * Where a bill was paid, for one with no place of its own nor a Moment's: the place of the
 * check-in whose time covers it, else the footprints' sample nearest its time, which is a known
 * place within [KNOWN_PLACE_M] or else named by Photon as a new Moment's place is. Only the point
 * goes out, nothing of the bill; Photon's name is kept beside the footprints so each spot is asked
 * about once, and a spot Photon couldn't be reached for is asked about again next time the app runs.
 */
class BillPlaces(
    private val footprints: FootprintStore,
    private val search: PlaceSearch,
    /** The phone's places and check-ins, as the repository has them. */
    val places: StateFlow<Map<String, Place>> = MutableStateFlow(emptyMap()),
    val checkins: StateFlow<Checkins> = MutableStateFlow(Checkins()),
    private val checkinsOn: () -> Boolean = { true },
    private val enabled: () -> Boolean = { true },
) {
    private val names = ConcurrentHashMap<String, String>()
    private val unreachable = ConcurrentHashMap.newKeySet<String>()
    private val asking = Mutex()

    /** Where a bill at [time] was, by a check-in, a known place or the footprints; null where none of them says. */
    suspend fun spotAt(time: Long): BillSpot? {
        checkinPlace(time)?.let { return it }
        val point = pointAt(time) ?: return null
        knownPlace(point)?.let { (id, place) -> return BillSpot(place.name, id, point, BillSpot.From.FOOTPRINTS) }
        return nameOf(point)?.let { BillSpot(it, null, point, BillSpot.From.FOOTPRINTS) }
    }

    /** The place of the check-in covering [time], while check-ins are on. */
    fun checkinPlace(time: Long): BillSpot? {
        if (!checkinsOn()) return null
        val checkin = checkins.value.covering(time) ?: return null
        val place = places.value[checkin.place] ?: return null
        return BillSpot(place.name, checkin.place, null, BillSpot.From.CHECKIN)
    }

    /** The known place nearest [point], when within [KNOWN_PLACE_M]: a bill's spot is that place, not a new name for it. */
    fun knownPlace(point: Fix): Pair<String, Place>? =
        places.value.entries.map { it to distance(point, it.value.fix()) }
            .filter { it.second <= KNOWN_PLACE_M }.minByOrNull { it.second }?.first?.toPair()

    /** Where the footprints were at [time], or null where they weren't kept. */
    suspend fun pointAt(time: Long): Fix? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        footprints.pointNear(time, WITHIN_MS, ACCURACY_M)?.let { Fix(it.latitude, it.longitude) }
    }

    /** The name of what is at [point], asked about and kept as a bill's spot is; null where nothing there has one. */
    suspend fun nameOf(point: Fix): String? = withContext(Dispatchers.IO) {
        val cell = String.format(Locale.ROOT, "%.4f,%.4f", point.latitude, point.longitude)
        val known = names[cell] ?: footprints.placeName(cell)?.also { names[cell] = it }
        if (known != null) return@withContext known.ifEmpty { null }
        if (cell in unreachable) return@withContext null
        // One question at a time, so a list of bills doesn't flood Photon.
        asking.withLock {
            names[cell]?.let { return@withLock it.ifEmpty { null } }
            val found = runCatching { search.nearby(point) }.getOrElse {
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
        /** How near a known place a bill's spot must be to be it: closer than a stay's, so two shops in one mall stay apart. */
        const val KNOWN_PLACE_M = 100.0
    }
}

/** The app's [BillPlaces], for the rows of any list of bills; none where the app doesn't give it. */
val LocalBillPlaces = staticCompositionLocalOf<BillPlaces?> { null }

/** Where [txn] was, for a bill with no place of its own nor a Moment's; null until it is known. */
@Composable
fun billSpot(txn: Txn): BillSpot? {
    val spots = LocalBillPlaces.current ?: return null
    val places by spots.places.collectAsStateWithLifecycle()
    val checkins by spots.checkins.collectAsStateWithLifecycle()
    val wanted = txn.place == null && txn.moment == null
    return produceState<BillSpot?>(null, spots, wanted, txn.time, places, checkins) {
        value = if (wanted) spots.spotAt(txn.time) else null
    }.value
}

/** The name of where [txn] was, as [billSpot] has it, for a row of bills. */
@Composable
fun footprintPlace(txn: Txn): String? = billSpot(txn)?.name
