package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.api.ApiException
import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.api.MomentsWriter
import cc.hyoban.moments.data.spec.MomentMedia
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
import java.io.File
import java.net.URI

/**
 * The records the screens show: read from the [DataStore] first, then brought up to
 * date from the API. Only what the site shows is offered: hidden Moments stay in the
 * copy but not in [moments]. Beside them, the Moments written on this phone and the
 * places they made, kept in [local], a store of their own that no sync or change of
 * API touches, until they are sent to the API through [writer] and become the site's.
 */
class MomentsRepository(
    private val store: DataStore,
    private val source: MomentsSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val local: DataStore? = null,
    private val writer: MomentsWriter? = null,
) {
    private val lock = Mutex()
    private var all: Map<String, MomentRecord> = emptyMap()
    private var apiPlaces: Map<String, Place> = emptyMap()
    private var phone: Map<String, MomentRecord> = emptyMap()
    private var phonePlaces: Map<String, Place> = emptyMap()
    /** The phone's places kept on their own, which stay when no Moment is at them. */
    private var keptPlaces: Set<String> = emptySet()
    private val _moments = MutableStateFlow<List<MomentRecord>>(emptyList())
    private val _places = MutableStateFlow<Map<String, Place>>(emptyMap())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val _syncing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /** The Moments the site shows and those written on this phone, newest first. */
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
            apiPlaces = store.readPlaces()
            if (local != null) {
                phone = local.readMoments().associateBy { it.uid }
                phonePlaces = local.readPlaces()
                keptPlaces = local.readKeptPlaces()
            }
            publishPlaces()
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
                apiPlaces = places
                publishPlaces()
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

    /**
     * Forgets the copy, on the phone and in memory, as when the records are to come from
     * another API. What was written on the phone stays.
     */
    suspend fun clear() = lock.withLock {
        withContext(io) {
            store.clear()
            all = emptyMap()
            apiPlaces = emptyMap()
            publishPlaces()
            _trips.value = emptyList()
            _error.value = null
            publish()
        }
    }

    /** Keeps a Moment written on the phone, with the place it made if it made one. */
    suspend fun addLocal(moment: MomentRecord, place: Pair<String, Place>? = null) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's own Moments" }
        withContext(io) {
            if (place != null) {
                phonePlaces = phonePlaces + place
                local.writePlaces(phonePlaces)
                publishPlaces()
            }
            local.writeMoment(moment)
            phone = phone + (moment.uid to moment)
            publish()
        }
    }

    /** Keeps a place made on the phone, on its own, for Moments to come. */
    suspend fun addLocalPlace(id: String, place: Place) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's own places" }
        withContext(io) {
            phonePlaces = phonePlaces + (id to place)
            keptPlaces = keptPlaces + id
            local.writePlaces(phonePlaces)
            local.writeKeptPlaces(keptPlaces)
            publishPlaces()
        }
    }

    /**
     * Keeps a Moment written on the phone as changed, with the place it made if it made
     * one: a move to another month moves its file, and the photos it no longer has and the
     * places it made that no Moment is at any more go.
     */
    suspend fun updateLocal(moment: MomentRecord, place: Pair<String, Place>? = null) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's own Moments" }
        withContext(io) {
            val previous = phone[moment.uid]
            if (place != null) phonePlaces = phonePlaces + place
            if (previous != null && local.fileOf(previous) != local.fileOf(moment)) local.deleteMoment(previous)
            local.writeMoment(moment)
            phone = phone + (moment.uid to moment)
            if (previous != null) {
                val kept = moment.media.map { it.uid }.toSet()
                deleteCopies(local, previous.media.filter { it.uid !in kept })
                dropUnused(previous.places)
            }
            local.writePlaces(phonePlaces)
            publishPlaces()
            publish()
        }
    }

    /**
     * Forgets a Moment written on the phone, with its photos the app copied in and the
     * places it made that no other Moment is at.
     */
    suspend fun deleteLocal(uid: String) = lock.withLock {
        withContext(io) {
            val moment = phone[uid] ?: return@withContext
            forgetLocal(moment)
            publishPlaces()
            publish()
        }
    }

    /** The app's own copies of [media], gone from its store. */
    private fun deleteCopies(local: DataStore, media: List<MomentMedia>) {
        media.mapNotNull { it.local?.takeIf { copy -> copy.copied }?.uri }
            .mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
            .filter { it.startsWith(local.root) }
            .forEach { it.delete() }
    }

    /**
     * Of [ids], the phone's places a Moment made that no Moment is at any more, forgotten;
     * those kept on their own stay. Whether any went.
     */
    private fun dropUnused(ids: List<String>): Boolean {
        val used = phone.values.flatMap { it.places }.toSet()
        val unused = ids.filter { it in phonePlaces && it !in used && it !in keptPlaces }.toSet()
        phonePlaces = phonePlaces - unused
        return unused.isNotEmpty()
    }

    /**
     * Sends a Moment to the API and publishes it: one written on the phone, or one of the
     * site's as changed here, with [place] when the change made one. The places only the
     * phone knows go first, under the ids the API gives them, then the photos not yet
     * published, then the Moment, which a draft follows by publishing. What comes back
     * is the site's: a Moment of the phone's leaves the phone, its copied photos with it,
     * and the phone's places sent are the API's from then on, for its other Moments too.
     * The network is outside the lock, so a sync isn't held up by the photos.
     */
    suspend fun push(moment: MomentRecord, place: Pair<String, Place>? = null): MomentRecord {
        val writer = checkNotNull(writer) { "No API to write to" }
        val (known, own) = lock.withLock { apiPlaces to (phonePlaces + listOfNotNull(place)) }
        val (saved, sentPlaces) = withContext(io) {
            val sentPlaces = moment.places.distinct().filter { it !in known }.associateWith { id ->
                writer.savePlace(id, own[id] ?: error("Unknown place $id"))
            }
            for (media in moment.media.filter { it.published == null }) {
                val file = media.local?.uri?.let { runCatching { File(URI(it)) }.getOrNull() }?.takeIf(File::isFile)
                    ?: error("The photo ${media.uid} isn't on the phone")
                writer.uploadImage(moment.uid, media.uid, file, contentTypeOf(file))
            }
            val sent = moment.copy(places = moment.places.map { sentPlaces[it]?.id ?: it })
            val saved = writer.saveMoment(sent).let { if (it.published == null) writer.publishMoment(it.uid) else it }
            saved to sentPlaces
        }
        lock.withLock {
            withContext(io) {
                apiPlaces = apiPlaces + sentPlaces.values.associate { it.id to it.place }
                store.writePlaces(apiPlaces)
                keep(saved)
                val written = phone[moment.uid]
                if (written != null) forgetLocal(written) else local?.let { deleteCopies(it, moment.media) }
                adoptPlaces(sentPlaces.mapValues { it.value.id })
                publishPlaces()
                publish()
            }
        }
        return saved
    }

    /** Takes one of the site's Moments off it; one the API no longer has is gone already. */
    suspend fun deletePublished(uid: String) {
        val writer = checkNotNull(writer) { "No API to write to" }
        try {
            withContext(io) { writer.deleteMoment(uid) }
        } catch (error: ApiException) {
            if (error.status != 404) throw error
        }
        lock.withLock {
            withContext(io) {
                all[uid]?.let(store::deleteMoment)
                all = all - uid
                publish()
            }
        }
    }

    /** One of the site's Moments as the API answered it, kept in the copy. */
    private fun keep(moment: MomentRecord) {
        all[moment.uid]?.let { previous -> if (store.fileOf(previous) != store.fileOf(moment)) store.deleteMoment(previous) }
        all = all + (moment.uid to moment)
        store.writeMoment(moment)
    }

    /** A Moment of the phone's gone from it, with its copied photos and the places only it was at. */
    private fun forgetLocal(moment: MomentRecord) {
        val local = local ?: return
        local.deleteMoment(moment)
        deleteCopies(local, moment.media)
        phone = phone - moment.uid
        if (dropUnused(moment.places)) local.writePlaces(phonePlaces)
    }

    /**
     * The phone's places the API now has, by their ids there: gone from the phone's, and
     * the phone's other Moments at them moved to the API's ids.
     */
    private fun adoptPlaces(ids: Map<String, String>) {
        val local = local ?: return
        val adopted = ids.filterKeys { it in phonePlaces }
        if (adopted.isEmpty()) return
        phonePlaces = phonePlaces - adopted.keys
        keptPlaces = keptPlaces - adopted.keys
        phone = phone.mapValues { (_, moment) ->
            if (moment.places.none { it in adopted }) {
                moment
            } else {
                moment.copy(places = moment.places.map { adopted[it] ?: it }).also(local::writeMoment)
            }
        }
        local.writePlaces(phonePlaces)
        local.writeKeptPlaces(keptPlaces)
    }

    /** Whether a Moment was written on this phone rather than read from the API. */
    fun isLocal(uid: String): Boolean = uid in phone

    fun moment(uid: String): MomentRecord? = phone[uid] ?: all[uid]?.takeIf(::isShown)

    private fun publish() {
        _moments.value = (all.values.filter { isShown(it) && it.uid !in phone } + phone.values).sortedWith(newestFirst)
    }

    private fun publishPlaces() {
        _places.value = phonePlaces + apiPlaces
    }

    private fun isShown(moment: MomentRecord) = moment.published != null && !moment.hidden

    /** The type the API takes a photo as, from the extension it was copied in with. */
    private fun contentTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "gif" -> "image/gif"
        else -> error("The API doesn't take .${file.extension} photos")
    }

    private val newestFirst = compareByDescending<MomentRecord> { it.occurredAt }.thenByDescending { it.uid }
}
