package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.api.ChangeSet
import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.api.MomentsWriter
import cc.hyoban.moments.data.api.NoTokenException
import cc.hyoban.moments.data.api.UploadPart
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/**
 * The records the screens show: read from the [DataStore] first, then brought up to
 * date from the API. Every published Moment is offered, a private one marked so by the screens,
 * those about tech only when [showTech] says so. Beside them, the Moments written on this phone and the
 * places they made, kept in [local], a store of their own that no sync or change of
 * API touches, with the site's Moments changed or deleted on the phone. None of it
 * reaches the API until [publishPending] sends it all through [writer], as [pending]
 * lists it for a look first, with what of the ledger and the footprints [outside] makes public.
 */
class MomentsRepository(
    private val store: DataStore,
    private val source: MomentsSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val local: DataStore? = null,
    private val writer: MomentsWriter? = null,
    private var showTech: Boolean = false,
    /** The ledger's public bills and the footprints' public spans, which publish with the Moments. */
    private val outside: OutsideChanges? = null,
) {
    private val lock = Mutex()
    private var all: Map<String, MomentRecord> = emptyMap()
    private var apiPlaces: Map<String, Place> = emptyMap()
    private var phone: Map<String, MomentRecord> = emptyMap()
    private var phonePlaces: Map<String, Place> = emptyMap()
    /** The phone's places kept on their own, which stay when no Moment is at them. */
    private var keptPlaces: Set<String> = emptySet()
    /** The site's places whose kind was changed on the phone, as they will be, until it is published. */
    private var changedPlaces: Map<String, Place> = emptyMap()
    /** The site's Moments deleted on the phone, by uid, until the deletions are published. */
    private var removed: Set<String> = emptySet()

    /** Moments set aside while their deletion can still be undone: out of sight, not yet deleted. */
    private var setAside: Set<String> = emptySet()
    /** The site's Moments whose change or deletion is being taken back while that can still be undone: shown as the site has them, the change still kept. */
    private var holding: Set<String> = emptySet()
    private val _moments = MutableStateFlow<List<MomentRecord>>(emptyList())
    private val _places = MutableStateFlow<Map<String, Place>>(emptyMap())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val _syncing = MutableStateFlow(false)
    private val _error = MutableStateFlow<Throwable?>(null)
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
    val error: StateFlow<Throwable?> = _error.asStateFlow()
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
                changedPlaces = local.readChangedPlaces()
                removed = local.readRemoved()
                // What a snackbar was still offering to undo when the app was last closed: done
                // now, as the snackbar going without its undo would have done it.
                val (deleting, reverting) = local.readHeld()
                deleting.forEach(::deleteNow)
                reverting.forEach(::revertNow)
                if (deleting.isNotEmpty() || reverting.isNotEmpty()) local.writeHeld(emptySet(), emptySet())
            }
            publishPlaces()
            _trips.value = store.readTrips().sortedByDescending { it.start }
            publish()
            _loaded.value = true
        }
    }

    /**
     * Brings the copy up to the API: what changed since the last refresh, the Moments page
     * by page and the places and trips with the first. A database restored to an earlier
     * revision than the copy's is read again from the start. Gives the API's revision.
     */
    suspend fun refresh(): Result<Long> = lock.withLock {
        _syncing.value = true
        try {
            val revision = withContext(io) {
                var since = store.readRevision()
                var page = source.changes(since, cursor = null)

                if (page.revision < since) {
                    store.clearMoments()
                    all = emptyMap()
                    since = 0
                    page = source.changes(since, cursor = null)
                }

                val first = page
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
                    page = source.changes(since, page.next ?: break)
                }

                // The same revision, so the places and trips are as the copy has them.
                if (revision != since) {
                    // From the start the feed has every place and trip; after a revision, what changed.
                    val fresh = since == 0L
                    val gone = first.deletedTrips.toSet() + first.trips.map { it.id }
                    val places = (if (fresh) emptyMap() else apiPlaces - first.deletedPlaces.toSet()) + first.places
                    val trips = (if (fresh) emptyList() else _trips.value.filter { it.id !in gone }) + first.trips

                    if (fresh || first.places.isNotEmpty() || first.deletedPlaces.isNotEmpty()) store.writePlaces(places)
                    if (fresh || first.trips.isNotEmpty() || first.deletedTrips.isNotEmpty()) store.replaceTrips(trips)
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
            _error.value = error
            Result.failure(error)
        } finally {
            _syncing.value = false
        }
    }

    /** Shows the Moments about tech beside what the site shows, or leaves them out again. */
    suspend fun changeShowTech(on: Boolean) = lock.withLock {
        showTech = on
        publish()
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
     * Sets a place's kind: one the phone made at once, one of the site's as a change kept
     * until it is published, taken back when it is set to the site's kind again.
     */
    suspend fun setPlaceKind(id: String, kind: String) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's own places" }
        withContext(io) {
            val own = phonePlaces[id]
            val site = apiPlaces[id]
            when {
                own != null -> {
                    phonePlaces = phonePlaces + (id to own.copy(kind = kind))
                    local.writePlaces(phonePlaces)
                }
                site != null -> {
                    changedPlaces = if (site.kind == kind) changedPlaces - id else changedPlaces + (id to site.copy(kind = kind))
                    local.writeChangedPlaces(changedPlaces)
                }
                else -> error("Unknown place $id")
            }
            publishPlaces()
            publish()
        }
    }

    /** Takes back a site's place's changed kind. */
    suspend fun revertPlace(id: String) = lock.withLock {
        withContext(io) {
            changedPlaces = changedPlaces - id
            local?.writeChangedPlaces(changedPlaces)
            publishPlaces()
            publish()
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
            setAside = setAside - uid
            deleteNow(uid)
            writeHeld()
            publishPlaces()
            publish()
        }
    }

    private fun deleteNow(uid: String) {
        phone[uid]?.let(::forgetLocal)
        if (uid in all) {
            removed = removed + uid
            local?.writeRemoved(removed)
        }
    }

    /**
     * Takes a Moment out of sight while its deletion can still be undone; [delete] deletes it,
     * [bringBack] undoes it. Noted on the phone, so a deletion the app is closed on before
     * either is done when it next starts.
     */
    suspend fun setAside(uid: String) = lock.withLock {
        setAside = setAside + uid
        withContext(io) { writeHeld() }
        publish()
    }

    /** Brings back a Moment [setAside], its deletion undone. */
    suspend fun bringBack(uid: String) = lock.withLock {
        setAside = setAside - uid
        withContext(io) { writeHeld() }
        publish()
    }

    /**
     * Shows one of the site's Moments as the site has it while taking back its change can be
     * undone; [revert] takes it back, [keepChange] undoes that. Noted on the phone as [setAside] is.
     */
    suspend fun holdRevert(uid: String) = lock.withLock {
        holding = holding + uid
        withContext(io) { writeHeld() }
        publish()
    }

    /** Keeps the change [holdRevert] was taking back. */
    suspend fun keepChange(uid: String) = lock.withLock {
        holding = holding - uid
        withContext(io) { writeHeld() }
        publish()
    }

    /** Takes back what publishing would do to one of the site's Moments: its change, or its deletion. */
    suspend fun revert(uid: String) = lock.withLock {
        withContext(io) {
            holding = holding - uid
            revertNow(uid)
            writeHeld()
            publishPlaces()
            publish()
        }
    }

    private fun revertNow(uid: String) {
        phone[uid]?.takeIf { it.published != null }?.let(::forgetLocal)
        if (uid in removed) {
            removed = removed - uid
            local?.writeRemoved(removed)
        }
    }

    /** What a snackbar can still undo, kept on the phone with the rest. */
    private fun writeHeld() {
        local?.writeHeld(setAside, holding)
    }

    /** The app's own copies of [media], gone from its store. */
    private fun deleteCopies(local: DataStore, media: List<MomentMedia>) {
        media.flatMap { it.local?.takeIf { copy -> copy.copied }?.let { copy -> listOfNotNull(copy.uri, copy.videoUri) }.orEmpty() }
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
     * Publishes all that [pending] lists: the originals of the photos not yet published, a
     * few at a time and none the API already has, as after a publish that failed, then
     * everything else in one write, which the API takes whole or not at all,
     * the Moments written on the phone and those changed, each published, the places only
     * the phone knows that they are at, and the deletions. One that fails stops it, all of
     * it kept for the next time, and its error is thrown; [onProgress] hears how many steps
     * are done of how many, and how many of the originals' bytes have gone. What comes back is the site's: the Moments leave the phone,
     * their copied photos with them, and the places sent are the API's from then on, for
     * the phone's other Moments too. The network is outside the lock, so a sync isn't held
     * up by the photos.
     */
    suspend fun publishPending(onProgress: (PublishProgress) -> Unit = {}) {
        val writer = checkNotNull(writer) { "No API to write to" }
        val (written, known, own, deletions, kinds) = lock.withLock {
            // A change being taken back isn't published, whatever becomes of it.
            Snapshot(phone.values.filter { it.uid !in holding }.sortedWith(oldestFirst), apiPlaces, phonePlaces, (removed - holding).toList(), changedPlaces)
        }
        val extra = outside?.pending() ?: Outside()
        if (written.isEmpty() && deletions.isEmpty() && kinds.isEmpty() && extra.size == 0) return
        // A bill of a Moment goes as a part of it, the Moment sent again with it (or without it,
        // taken off) when it isn't among the changes already; a bill alone is an entry of its own.
        val (ofMoments, alone) = lock.withLock {
            extra.bills.partition { bill -> bill.txn.moment?.let { phone[it] ?: all[it] } != null }
        }
        val carriers = lock.withLock {
            ofMoments.mapNotNull { bill -> bill.txn.moment?.takeIf { uid -> written.none { it.uid == uid } }?.let { phone[it] ?: all[it] } }
        }.distinctBy { it.uid }
        val moments = written + carriers
        val parts = ofMoments.filter { it.part != null }.groupBy { it.txn.moment!! }
            .mapValues { (_, bills) -> mapOf("bill" to bills.first().part!!) }
        // The site's places with a changed kind go under their own ids, which the API takes the kind of.
        val places = moments.flatMap { it.places }.distinct().filter { it !in known }
            .associateWith { id -> own[id] ?: error("Unknown place $id") } + kinds
        // A live photo sends its still and its motion, each an upload of its own; a video only
        // itself, its still on the phone left for the site to take from it.
        val originals = moments.flatMap { moment ->
            moment.media.filter { it.published == null }.flatMap { media ->
                listOfNotNull(
                    Triple(moment.uid, media, UploadPart.IMAGE).takeIf { media.type != MediaType.VIDEO },
                    Triple(moment.uid, media, UploadPart.VIDEO).takeIf { media.type != MediaType.IMAGE },
                )
            }
        }
        val total = originals.size + 1
        val applied = withContext(io) {
            val files = originals.map { (uid, media, part) ->
                val uri = if (part == UploadPart.VIDEO) media.local?.videoUri else media.local?.uri
                val file = uri?.let { runCatching { File(URI(it)) }.getOrNull() }?.takeIf(File::isFile)
                    ?: error("The ${part.path} of ${media.uid} isn't on the phone")
                Triple(uid to part, media, file)
            }
            val size = files.sumOf { (_, _, file) -> file.length() }
            var done = 0
            var sent = 0L
            val progress = Any()
            // Said as each stretch of bytes goes, so a big photo shows it is on its way.
            fun report(steps: Int = 0, bytes: Long = 0) = synchronized(progress) {
                done += steps
                sent = minOf(size, sent + bytes)
                onProgress(PublishProgress(done, total, sent, size))
            }
            report()
            val uploading = Semaphore(UPLOADS_AT_ONCE)
            coroutineScope {
                files.map { (key, media, file) ->
                    val (uid, part) = key
                    async {
                        var counted = 0L
                        uploading.withPermit {
                            // A link that drops now and then, as a proxy's does, gets a few tries
                            // before the whole publish gives up; what the API answers doesn't.
                            for (attempt in 1..UPLOAD_TRIES) {
                                try {
                                    if (writer.uploaded(uid, media.uid, part) != md5(file)) {
                                        writer.upload(uid, media.uid, part, file, contentTypeOf(file)) { bytes ->
                                            counted += bytes
                                            report(bytes = bytes)
                                        }
                                    }
                                    break
                                } catch (error: IOException) {
                                    if (error is HttpException || error is NoTokenException || attempt == UPLOAD_TRIES) throw error
                                    report(bytes = -counted)
                                    counted = 0
                                    delay(RETRY_WAIT_MS shl (attempt - 1))
                                }
                            }
                        }
                        // One the API had already, or a retry that sent it again, counts as all of it once.
                        report(steps = 1, bytes = file.length() - counted)
                    }
                }.awaitAll()
            }
            writer.applyChanges(
                ChangeSet(
                    places,
                    moments,
                    deletions + alone.filter { it.part == null }.map { it.uid },
                    entries = alone.filter { it.part != null }.map { it.entry() },
                    parts = parts,
                    tracks = extra.tracks.mapNotNull { it.line },
                    deletedTracks = extra.tracks.filter { it.line == null }.map { it.share.uid },
                ),
            )
        }
        outside?.published(extra)
        lock.withLock {
            withContext(io) {
                apiPlaces = apiPlaces + applied.places.filterValues { it !in apiPlaces }.entries.associate { (given, id) -> id to places.getValue(given) } +
                    kinds.mapValues { (id, place) -> apiPlaces[id]?.copy(kind = place.kind) ?: place }
                store.writePlaces(apiPlaces)
                // A kind changed again while this was sent stays to be published.
                changedPlaces = changedPlaces.filter { (id, place) -> kinds[id] != place }
                local?.writeChangedPlaces(changedPlaces)
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
                // The write came right after the copy's revision, so the copy is now of the
                // write's: the next sync doesn't hand the same Moments back.
                if (applied.revision != null && applied.revision == store.readRevision() + 1) {
                    store.writeRevision(applied.revision)
                }
                publishPlaces()
                publish()
            }
        }
        onProgress(PublishProgress(total, total))
    }

    private data class Snapshot(
        val moments: List<MomentRecord>,
        val known: Map<String, Place>,
        val own: Map<String, Place>,
        val deletions: List<String>,
        val kinds: Map<String, Place>,
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
    fun isChanged(uid: String): Boolean = uid !in holding && phone[uid]?.published != null

    fun moment(uid: String): MomentRecord? =
        phone[uid]?.takeIf { uid !in holding } ?: all[uid]?.takeIf { isShown(it) && (uid !in removed || uid in holding) }

    private fun publish() {
        // A change being taken back, as though it were already.
        val phone = phone.filterKeys { it !in holding }
        val removed = removed - holding
        _moments.value = (all.values.filter { isShown(it) && it.uid !in phone && it.uid !in removed } + phone.values)
            .filter { it.uid !in setAside }.sortedWith(newestFirst)
        val (added, changed) = phone.values.filter { it.uid !in setAside }.sortedWith(oldestFirst).partition { it.published == null }
        _pending.value = PendingChanges(
            added,
            changed.map { Change(all[it.uid], it) },
            removed.mapNotNull { all[it] }.sortedWith(oldestFirst),
            changedPlaces.mapNotNull { (id, place) -> apiPlaces[id]?.let { PlaceChange(id, it, place) } }.sortedBy { it.after.name },
            outside?.pending() ?: Outside(),
        )
    }

    private fun publishPlaces() {
        _places.value = phonePlaces + apiPlaces + changedPlaces
    }

    // A private one is the phone's to see, marked so; one about tech only when asked for.
    private fun isShown(moment: MomentRecord) = moment.published != null && (showTech || moment.topic != "tech")

    /** Notes again what waits beside the Moments, the ledger or the footprints having changed. */
    fun noteOutside() {
        _pending.value = _pending.value.copy(outside = outside?.pending() ?: Outside())
    }

    /** The type the API takes a photo as, from the extension it was copied in with. */
    /** The file's MD5, as R2 gives an object put in one piece for its ETag. */
    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun contentTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> error("The API doesn't take .${file.extension} files")
    }

    private val newestFirst = compareByDescending<MomentRecord> { it.occurredAt }.thenByDescending { it.order ?: Int.MAX_VALUE }.thenByDescending { it.uid }
    private val oldestFirst = compareBy<MomentRecord> { it.occurredAt }.thenBy { it.order ?: Int.MAX_VALUE }.thenBy { it.uid }
}

/** How many photos go up at once: enough to fill the connection, few enough to keep memory and the radio calm. */
private const val UPLOADS_AT_ONCE = 3
private const val UPLOAD_TRIES = 3
/** The wait before an upload's second try, doubled before each after. */
private const val RETRY_WAIT_MS = 2_000L

/**
 * How far publishing has got: [done] of its [total] steps, an upload for each original and
 * the write after them, and [sent] of the originals' [size] bytes.
 */
data class PublishProgress(val done: Int, val total: Int, val sent: Long = 0, val size: Long = 0) {
    /**
     * How much of it is done, by the originals' bytes while they upload, which is most of the
     * time it takes, and the write after them the last bit.
     */
    val fraction: Float
        get() = when {
            size > 0 -> sent.toFloat() / size * UPLOADED_SHARE + if (done == total) 1 - UPLOADED_SHARE else 0f
            total == 0 -> 0f
            else -> done.toFloat() / total
        }

    private companion object {
        /** How much of it the uploads are; the write after them is the rest. */
        const val UPLOADED_SHARE = 0.95f
    }
}

/** What publishing would change on the site: the Moments written on the phone, those changed, those deleted. */
data class PendingChanges(
    val added: List<MomentRecord> = emptyList(),
    val changed: List<Change> = emptyList(),
    val removed: List<MomentRecord> = emptyList(),
    val places: List<PlaceChange> = emptyList(),
    /** The bills and footprints going public, or private again. */
    val outside: Outside = Outside(),
) {
    val size get() = added.size + changed.size + removed.size + places.size + outside.size
}

/** One of the site's places as the site has it, [before], and with the kind changed on the phone. */
data class PlaceChange(val id: String, val before: Place, val after: Place)

/** One of the site's Moments as the site has it, [before] (null when the copy no longer has it), and as changed. */
data class Change(val before: MomentRecord?, val after: MomentRecord)
