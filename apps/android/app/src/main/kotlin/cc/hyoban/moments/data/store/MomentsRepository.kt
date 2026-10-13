package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.Timestamps
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.spec.Ulid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The records in memory, read from the [DataStore] once and written back on every
 * change. The files are the truth; this is the index the screens observe.
 */
class MomentsRepository(
    private val store: DataStore,
    private val generator: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _moments = MutableStateFlow<List<MomentRecord>>(emptyList())
    private val _places = MutableStateFlow<Map<String, Place>>(emptyMap())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _loaded = MutableStateFlow(false)

    /** Newest first. */
    val moments: StateFlow<List<MomentRecord>> = _moments.asStateFlow()
    val places: StateFlow<Map<String, Place>> = _places.asStateFlow()
    /** Newest first. */
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = lock.withLock {
        withContext(io) {
            if (store.readManifest() == null) {
                store.writeManifest(Manifest(Spec.SCHEMA_VERSION, generator, Timestamps.now()))
            }
            _places.value = store.readPlaces()
            _trips.value = store.readTrips().sortedByDescending { it.start }
            _moments.value = store.readMoments().sortedWith(newestFirst)
            _loaded.value = true
        }
    }

    fun moment(uid: String): MomentRecord? = _moments.value.firstOrNull { it.uid == uid }

    suspend fun saveMoment(record: MomentRecord) = lock.withLock {
        withContext(io) {
            val previous = _moments.value.firstOrNull { it.uid == record.uid }
            if (previous != null && store.fileOf(previous) != store.fileOf(record)) {
                store.deleteMoment(previous)
            }
            store.writeMoment(record)
            touchManifest()
        }
        _moments.update { moments -> (moments.filter { it.uid != record.uid } + record).sortedWith(newestFirst) }
    }

    suspend fun deleteMoment(uid: String) = lock.withLock {
        val record = _moments.value.firstOrNull { it.uid == uid } ?: return@withLock
        withContext(io) {
            store.deleteMoment(record)
            touchManifest()
        }
        _moments.update { moments -> moments.filter { it.uid != uid } }
    }

    suspend fun savePlace(id: String, place: Place) = lock.withLock {
        val places = _places.value + (id to place)
        withContext(io) {
            store.writePlaces(places)
            touchManifest()
        }
        _places.value = places
    }

    /** A new place at a point, under a provisional id; publishing names it properly. */
    suspend fun newPlace(name: String, latitude: Double, longitude: Double): String {
        val id = "p-${Ulid.new().lowercase()}"
        savePlace(id, Place(name = name.trim(), latitude = round(latitude), longitude = round(longitude), createdAt = Timestamps.now()))
        return id
    }

    /** Drops a place and names [to] wherever it was named; [to] must exist. */
    suspend fun renamePlace(from: String, to: String) = lock.withLock {
        require(to in _places.value) { "Unknown place $to" }
        if (from == to || from !in _places.value) return@withLock
        val places = _places.value - from
        val moments = _moments.value.map { moment ->
            if (from !in moment.places) moment
            else moment.copy(places = moment.places.map { if (it == from) to else it }.distinct())
        }
        val trips = _trips.value.map { trip ->
            trip.copy(
                waypoints = trip.waypoints.map { if (it.place == from) it.copy(place = to) else it },
                straightLegs = trip.straightLegs.map { it.copy(from = if (it.from == from) to else it.from, to = if (it.to == from) to else it.to) },
                sameWayBack = trip.sameWayBack.map { it.copy(from = if (it.from == from) to else it.from, to = if (it.to == from) to else it.to) },
            )
        }
        withContext(io) {
            store.writePlaces(places)
            moments.filterIndexed { index, moment -> moment != _moments.value[index] }.forEach(store::writeMoment)
            trips.filterIndexed { index, trip -> trip != _trips.value[index] }.forEach(store::writeTrip)
            touchManifest()
        }
        _places.value = places
        _moments.value = moments
        _trips.value = trips
    }

    suspend fun saveTrip(trip: TripRecord) = lock.withLock {
        withContext(io) {
            store.writeTrip(trip)
            touchManifest()
        }
        _trips.update { trips -> (trips.filter { it.id != trip.id } + trip).sortedByDescending { it.start } }
    }

    /** The nearest known place within [withinKm], for recording where the phone is. */
    fun nearestPlace(latitude: Double, longitude: Double, withinKm: Double = 0.5): Pair<String, Place>? =
        _places.value.entries
            .map { (id, place) -> Triple(id, place, distanceKm(latitude, longitude, place.latitude, place.longitude)) }
            .filter { it.third <= withinKm }
            .minByOrNull { it.third }
            ?.let { it.first to it.second }

    private fun touchManifest() {
        store.writeManifest(Manifest(Spec.SCHEMA_VERSION, generator, Timestamps.now()))
    }

    private val newestFirst = compareByDescending<MomentRecord> { it.occurredAt }.thenByDescending { it.uid }

    companion object {
        /** About a metre: as precise as places.json keeps a point. */
        private fun round(value: Double) = Math.round(value * 100_000) / 100_000.0

        /** Great-circle distance, the way the site measures its trips. */
        fun distanceKm(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
            val radians = Math.PI / 180
            val dLatitude = (latitude2 - latitude1) * radians
            val dLongitude = (longitude2 - longitude1) * radians
            val a = sin(dLatitude / 2) * sin(dLatitude / 2) +
                cos(latitude1 * radians) * cos(latitude2 * radians) * sin(dLongitude / 2) * sin(dLongitude / 2)
            return 2 * 6371.0088 * asin(sqrt(a))
        }
    }
}
