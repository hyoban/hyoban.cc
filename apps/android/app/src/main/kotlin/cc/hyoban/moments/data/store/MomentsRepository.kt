package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The records the screens show: read from the [DataStore] first, then brought up to
 * date from the API. Only what the site shows is offered: hidden Moments stay in the
 * copy but not in [moments].
 */
class MomentsRepository(
    private val store: DataStore,
    private val source: MomentsSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private var all: Map<String, MomentRecord> = emptyMap()
    private val _moments = MutableStateFlow<List<MomentRecord>>(emptyList())
    private val _places = MutableStateFlow<Map<String, Place>>(emptyMap())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val _syncing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /** The Moments the site shows, newest first. */
    val moments: StateFlow<List<MomentRecord>> = _moments.asStateFlow()
    val places: StateFlow<Map<String, Place>> = _places.asStateFlow()
    /** Newest first. */
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()
    /** Whether the copy on the phone has been read, so an empty list means there is nothing. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    /** Why the last refresh failed, until one succeeds. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Reads the copy on the phone. */
    suspend fun load() = lock.withLock {
        withContext(io) {
            all = store.readMoments().associateBy { it.uid }
            _places.value = store.readPlaces()
            _trips.value = store.readTrips().sortedByDescending { it.start }
            publish()
            _loaded.value = true
        }
    }

    /**
     * Brings the copy up to the API: the Moments changed since the last refresh, page by
     * page, and the places and trips whole. A database restored to an earlier revision
     * than the copy's is read again from the start.
     */
    suspend fun refresh(): Result<Unit> = lock.withLock {
        _syncing.value = true
        try {
            withContext(io) {
                var since = store.readRevision()
                var page = source.moments(since, cursor = null)

                if (page.revision < since) {
                    store.clearMoments()
                    all = emptyMap()
                    since = 0
                    page = source.moments(since, cursor = null)
                }

                val revision = page.revision
                val moments = all.toMutableMap()

                while (true) {
                    for (uid in page.deleted) {
                        moments.remove(uid)?.let(store::deleteMoment)
                    }
                    for (moment in page.moments) {
                        // A Moment moved to another day lives in another month's directory.
                        moments[moment.uid]?.let { previous ->
                            if (store.fileOf(previous) != store.fileOf(moment)) store.deleteMoment(previous)
                        }
                        moments[moment.uid] = moment
                        store.writeMoment(moment)
                    }
                    page = source.moments(since, page.next ?: break)
                }

                val places = source.places().places
                val trips = source.trips().trips

                store.writePlaces(places)
                store.replaceTrips(trips)
                store.writeRevision(revision)

                all = moments
                _places.value = places
                _trips.value = trips.sortedByDescending { it.start }
                publish()
            }
            _error.value = null
            Result.success(Unit)
        } catch (error: Exception) {
            _error.value = error.message ?: error.toString()
            Result.failure(error)
        } finally {
            _syncing.value = false
        }
    }

    fun moment(uid: String): MomentRecord? = all[uid]?.takeIf(::isShown)

    private fun publish() {
        _moments.value = all.values.filter(::isShown).sortedWith(newestFirst)
    }

    private fun isShown(moment: MomentRecord) = moment.published != null && !moment.hidden

    private val newestFirst = compareByDescending<MomentRecord> { it.occurredAt }.thenByDescending { it.uid }
}
