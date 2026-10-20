package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.api.ChangeSet
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
 * API touches, with the site's Moments changed or deleted on the phone. None of it
 * reaches the API until [publishPending] sends it all through [writer], as [pending]
 * lists it for a look first.
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
    /** The site's Moments deleted on the phone, by uid, until the deletions are published. */
    private var removed: Set<String> = emptySet()
    private val _moments = MutableStateFlow<List<MomentRecord>>(emptyList())
    private val _places = MutableStateFlow<Map<String, Place>>(emptyMap())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val _syncing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _pending = MutableStateFlow(PendingChanges())

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
    /** What publishing would change on the site. */
    val pending: StateFlow<PendingChanges> = _pending.asStateFlow()

    /** Reads the copy on the phone. */
    suspend fun load() = lock.withLock {
        withContext(io) {
            all = store.readMoments().associateBy { it.uid }
            apiPlaces = store.readPlaces()
            if (local != null) {
                phone = local.readMoments().associateBy { it.uid }
                phonePlaces = local.readPlaces()
                keptPlaces = local.readKeptPlaces()
                removed = local.readRemoved()
            }
            publishPlaces()
            _trips.value = store.readTrips().sortedByDescending { it.start }
            publish()
            _loaded.value = true
        }
    }

    /**
     * Brings the copy up to the API: the Moments changed since the last refresh, page by
     * page, and the places and trips whole, unless the API's revision is still the copy's.
     * A database restored to an earlier revision than the copy's is read again from the
     * start. Gives the API's revision.
     */
    suspend fun refresh(): Result<Long> = lock.withLock {
        _syncing.value = true
        try {
            val revision = withContext(io) {
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

                // The same revision, so the places and trips are as the copy has them.
                if (revision != since) {
                    val places = source.places().places
                    val trips = source.trips().trips

                    store.writePlaces(places)
                    store.replaceTrips(trips)
                    store.writeRevision(revision)
                    apiPlaces = places
                    _trips.value = trips.sortedByDescending { it.start }
                }

                all = moments
                // A deletion of a Moment the site no longer has is done already.
                if (removed.any { it !in moments }) {
                    removed = removed.filter { it in moments }.toSet()
                    local?.writeRemoved(removed)
                }
                publishPlaces()
                publish()
                revision
            }
            _error.value = null
            Result.success(revision)
        } catch (error: Exception) {
            _error.value = error.message ?: error.toString()
            Result.failure(error)
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Forgets the copy, on the phone and in memory, as when the records are to come from
     * another API. What was written on the phone stays; what was changed or deleted of the
     * old API's Moments goes with them.
     */
    suspend fun clear() = lock.withLock {
        withContext(io) {
            store.clear()
            phone.values.filter { it.published != null }.forEach(::forgetLocal)
            removed = emptySet()
            local?.writeRemoved(removed)
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
     * Keeps a Moment as changed on the phone, with the place it made if it made one: one
     * written here, or one of the site's until the changes are published. A move to another
     * month moves its file, and the photos it no longer has and the places it made that no
     * Moment is at any more go.
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
     * Deletes a Moment. One written on the phone goes, with its photos the app copied in and
     * the places it made that no other Moment is at; one of the site's drops any change kept
     * for it and is taken off the site when the changes are published.
     */
    suspend fun delete(uid: String) = lock.withLock {
        withContext(io) {
            phone[uid]?.let(::forgetLocal)
            if (uid in all) {
                removed = removed + uid
                local?.writeRemoved(removed)
            }
            publishPlaces()
            publish()
        }
    }

    /** Takes back what publishing would do to one of the site's Moments: its change, or its deletion. */
    suspend fun revert(uid: String) = lock.withLock {
        withContext(io) {
            phone[uid]?.takeIf { it.published != null }?.let(::forgetLocal)
            if (uid in removed) {
                removed = removed - uid
                local?.writeRemoved(removed)
            }
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
     * Publishes all that [pending] lists: the originals of the photos not yet published, one
     * by one, then everything else in one write, which the API takes whole or not at all,
     * the Moments written on the phone and those changed, each published, the places only
     * the phone knows that they are at, and the deletions. One that fails stops it, all of
     * it kept for the next time, and its error is thrown; [onProgress] hears how many steps
     * are done of how many. What comes back is the site's: the Moments leave the phone,
     * their copied photos with them, and the places sent are the API's from then on, for
     * the phone's other Moments too. The network is outside the lock, so a sync isn't held
     * up by the photos.
     */
    suspend fun publishPending(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val writer = checkNotNull(writer) { "No API to write to" }
        val (moments, known, own, deletions) = lock.withLock {
            Snapshot(phone.values.sortedWith(oldestFirst), apiPlaces, phonePlaces, removed.toList())
        }
        if (moments.isEmpty() && deletions.isEmpty()) return
        val places = moments.flatMap { it.places }.distinct().filter { it !in known }
            .associateWith { id -> own[id] ?: error("Unknown place $id") }
        val photos = moments.flatMap { moment -> moment.media.filter { it.published == null }.map { moment.uid to it } }
        val total = photos.size + 1
        var done = 0
        onProgress(done, total)
        val applied = withContext(io) {
            for ((uid, media) in photos) {
                val file = media.local?.uri?.let { runCatching { File(URI(it)) }.getOrNull() }?.takeIf(File::isFile)
                    ?: error("The photo ${media.uid} isn't on the phone")
                writer.uploadImage(uid, media.uid, file, contentTypeOf(file))
                onProgress(++done, total)
            }
            writer.applyChanges(ChangeSet(places, moments, deletions))
        }
        lock.withLock {
            withContext(io) {
                apiPlaces = apiPlaces + applied.places.filterValues { it !in apiPlaces }.entries.associate { (given, id) -> id to places.getValue(given) }
                store.writePlaces(apiPlaces)
                for (saved in applied.moments) {
                    keep(saved)
                    phone[saved.uid]?.let(::forgetLocal)
                }
                for (uid in deletions) {
                    all[uid]?.let(store::deleteMoment)
                    all = all - uid
                }
                removed = removed - deletions.toSet()
                local?.writeRemoved(removed)
                adoptPlaces(applied.places)
                publishPlaces()
                publish()
            }
        }
        onProgress(total, total)
    }

    private data class Snapshot(
        val moments: List<MomentRecord>,
        val known: Map<String, Place>,
        val own: Map<String, Place>,
        val deletions: List<String>,
    )

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
    fun isLocal(uid: String): Boolean = phone[uid]?.let { it.published == null } == true

    /** Whether one of the site's Moments has a change on the phone not yet published. */
    fun isChanged(uid: String): Boolean = phone[uid]?.published != null

    fun moment(uid: String): MomentRecord? = phone[uid] ?: all[uid]?.takeIf { isShown(it) && uid !in removed }

    private fun publish() {
        _moments.value = (all.values.filter { isShown(it) && it.uid !in phone && it.uid !in removed } + phone.values).sortedWith(newestFirst)
        val (added, changed) = phone.values.sortedWith(oldestFirst).partition { it.published == null }
        _pending.value = PendingChanges(added, changed.map { Change(all[it.uid], it) }, removed.mapNotNull { all[it] }.sortedWith(oldestFirst))
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
    private val oldestFirst = compareBy<MomentRecord> { it.occurredAt }.thenBy { it.uid }
}

/** What publishing would change on the site: the Moments written on the phone, those changed, those deleted. */
data class PendingChanges(
    val added: List<MomentRecord> = emptyList(),
    val changed: List<Change> = emptyList(),
    val removed: List<MomentRecord> = emptyList(),
) {
    val size get() = added.size + changed.size + removed.size
}

/** One of the site's Moments as the site has it, [before] (null when the copy no longer has it), and as changed. */
data class Change(val before: MomentRecord?, val after: MomentRecord)
