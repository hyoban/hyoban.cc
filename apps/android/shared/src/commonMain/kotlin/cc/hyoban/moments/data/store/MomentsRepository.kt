package cc.hyoban.moments.data.store

import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.api.ChangeSet
import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.api.MomentsWriter
import cc.hyoban.moments.data.api.NoTokenException
import cc.hyoban.moments.data.api.UploadPart
import cc.hyoban.moments.data.copy.CopyStore
import cc.hyoban.moments.data.copy.CopyTables
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.entryUid
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/**
 * The records the screens show: read from the [CopyStore] first, then brought up to
 * date from the API's rows. Every published Moment is offered, a private one marked so by the screens.
 * Beside them, what was written or changed on this phone, every record as the entry it
 * will be (a Moment, a check-in, a device), with the places they made, kept in [local], a
 * store of their own that no sync or change of API touches, and the site's entries deleted
 * on the phone. None of it reaches the API until [publishPending] sends it all through
 * [writer], as [pending] lists it for a look first, with what of the ledger and the
 * footprints [outside] makes public.
 */
class MomentsRepository(
    private val copy: CopyStore,
    private val source: MomentsSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val local: DataStore? = null,
    private val writer: MomentsWriter? = null,
    /** The ledger's public bills, which publish with the Moments. */
    private val outside: OutsideChanges? = null,
) {
    private val lock = Mutex()
    private var all: Map<String, MomentRecord> = emptyMap()
    private var apiPlaces: Map<String, Place> = emptyMap()
    /** The Moments among [changedEntries], worked out from them each time they change. */
    private var phone: Map<String, MomentRecord> = emptyMap()
    private var phonePlaces: Map<String, Place> = emptyMap()
    /** The phone's places kept on their own, which stay when no Moment is at them. */
    private var keptPlaces: Set<String> = emptySet()
    /** The site's places whose kind was changed on the phone, as they will be, until it is published. */
    private var changedPlaces: Map<String, Place> = emptyMap()
    /** The trips changed or made on the phone, as collections, by trip id, until published. */
    private var changedTrips: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()
    /** The trips as the API has them, before the phone's changes. */
    private var apiTrips: List<TripRecord> = emptyList()
    /**
     * Every entry written or changed on the phone, by uid, until published: Moments, check-ins,
     * devices and the rest alike, so one becomes another (a Moment cleared of its words a
     * check-in) by being saved as it now is.
     */
    private var changedEntries: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()
    /** The site's entries deleted on the phone, Moments among them, by uid, until published. */
    private var removedEntries: Set<String> = emptySet()

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

    private val _records = MutableStateFlow(Records())
    /**
     * The API's records beside the Moments and trips: the entries with no Moment (a
     * charge, a public bill, a mark) and the Moments with parts the first shape doesn't hold,
     * and the collections.
     */
    val records: StateFlow<Records> = _records.asStateFlow()

    private val _entries = MutableStateFlow<Map<String, kotlinx.serialization.json.JsonObject>>(emptyMap())

    /** The entries beyond the Moments, by uid, with the phone's changes to them: what the 设备, 订阅 and 车 pages read. */
    val entries: StateFlow<Map<String, kotlinx.serialization.json.JsonObject>> = _entries.asStateFlow()

    private val _checkins = MutableStateFlow(cc.hyoban.moments.data.things.Checkins())

    /** The check-ins alone among [entries], by day and by place, worked out as they change rather than by each page. */
    val checkins: StateFlow<cc.hyoban.moments.data.things.Checkins> = _checkins.asStateFlow()

    /** Reads the copy on the phone. */
    suspend fun load() = lock.withLock {
        withContext(io) {
            readCopy()
            if (local != null) {
                phonePlaces = local.readPlaces()
                keptPlaces = local.readKeptPlaces()
                changedPlaces = local.readChangedPlaces()
                changedTrips = local.readChangedTrips()
                local.readChangedEntries().let { (entries, gone) ->
                    changedEntries = entries
                    removedEntries = gone
                }
                takeOldMoments(local)
                showPhone()
                // What a snackbar was still offering to undo when the app was last closed: done
                // now, as the snackbar going without its undo would have done it.
                val (deleting, reverting) = local.readHeld()
                deleting.forEach(::deleteNow)
                reverting.forEach(::revertNow)
                if (deleting.isNotEmpty() || reverting.isNotEmpty()) local.writeHeld(emptySet(), emptySet())
            }
            publishPlaces()
            showEntries()
            showTrips()
            publish()
            _loaded.value = true
        }
    }

    /**
     * The Moments an app from before kept as files of their own, and the site's Moments it noted
     * as deleted, taken in as the entries they are and their files gone: every change is an entry now.
     */
    private fun takeOldMoments(local: DataStore) {
        val moments = local.readOldMoments()
        val removed = local.readOldRemoved()
        if (moments.isEmpty() && removed.isEmpty()) return
        changedEntries = changedEntries + moments.associate { it.uid to Entries.fromMoment(it) }
        removedEntries = removedEntries + removed
        local.writeChangedEntries(changedEntries, removedEntries)
        local.dropOldMoments()
    }

    /**
     * Brings the copy up to the API's rows, `/v2/rows`: every row that changed since the last
     * refresh, page by page, written over the copy's at once. A database restored to an
     * earlier revision than the copy's, or another than the copy's, is read again from the start.
     * Gives the API's revision.
     */
    suspend fun refresh(): Result<Long> = lock.withLock {
        _syncing.value = true
        try {
            val revision = withContext(io) {
                val known = copy.revision()
                var since = known
                var page = source.rows(since, cursor = null)
                val database = copy.database()
                // Another database, one since put back or made again, even at a later revision.
                val another = database != null && page.database != null && page.database != database
                if (page.revision < known || another) {
                    since = 0
                    page = source.rows(0, cursor = null)
                }
                val first = page
                // The same revision, so everything is as the copy has it.
                if (first.revision != known || since != known) {
                    val rows = page.rows.toMutableList()
                    while (true) {
                        page = source.rows(since, page.next ?: break)
                        rows += page.rows
                    }
                    // A table the copy doesn't keep, as one the API has come to serve since, is left out.
                    val copied = rows.filter { it.table in CopyTables.names }.map { it.toCopyRow() }
                    copy.apply(copied, first.revision, fresh = since == 0L, database = first.database)
                    // From the start the copy is new, read whole; after a revision only its rows changed.
                    if (since == 0L) readCopy() else takeRows(copied, first.revision)
                    // An entry the site now has as the phone changed it is the site's.
                    val settled = changedEntries.filter { (uid, entry) -> copyEntries[uid] == entry }.keys
                    if (settled.isNotEmpty()) setChanges(changedEntries - settled)
                } else if (database == null && first.database != null) {
                    // A copy from before the API said which database it is, kept as of this one.
                    copy.apply(emptyList(), known, database = first.database)
                }

                showTrips()
                // A deletion of an entry the site no longer has is done already.
                if (removedEntries.any { it !in copyEntries }) setChanges(removed = removedEntries.filterTo(HashSet()) { it in copyEntries })
                publishPlaces()
                publish()
                first.revision
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

    /** The copy's entries, read once, so a sync parses only the rows it brought. */
    private var copyEntries: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()

    /**
     * What the screens are built from, read from the copy: the places, the entries with words
     * as Moments, the trips worked out from the collections and their marks, and the API's
     * records beside them.
     */
    private fun readCopy() {
        fun objects(table: String) = copy.live(table).mapValues { parse(it.value) }
        copyEntries = objects("entries")
        apiPlaces = copy.live("places").mapValues { Spec.json.decodeFromString(Place.serializer(), it.value) }
        _records.value = Records(
            copy.revision(),
            copyEntries.filterValues(::isMoreThanMoment),
            objects("collections"),
            objects("vehicle_months"),
            objects("vehicle_assisted"),
        )
        all = copyEntries.values.mapNotNull(::momentOf).associateBy { it.uid }
        apiTrips = tripsOf()
    }

    /**
     * Takes the [rows] a sync brought over what the screens are built from, rebuilding only
     * what they touch: the Moments of the entries that changed, every one only when a
     * collection did (it may name their trip), and the trips when a collection or a mark did.
     */
    private fun takeRows(rows: List<cc.hyoban.moments.data.copy.CopyRow>, revision: Long) {
        val byTable = rows.groupBy { it.table }
        fun <T> merge(current: Map<String, T>, table: String, read: (String) -> T): Map<String, T> {
            val changed = byTable[table] ?: return current
            return current.toMutableMap().apply {
                for (row in changed) if (row.deletedAt == null) put(row.key, read(row.record)) else remove(row.key)
            }
        }
        val touched = byTable["entries"].orEmpty().map { it.key }.toSet()
        val before = copyEntries
        copyEntries = merge(copyEntries, "entries", ::parse)
        val marksMoved = touched.any { uid -> listOfNotNull(before[uid], copyEntries[uid]).any(Entries::isMark) }
        apiPlaces = merge(apiPlaces, "places") { Spec.json.decodeFromString(Place.serializer(), it) }
        val records = _records.value
        _records.value = Records(
            revision,
            records.entries - touched + touched.mapNotNull { uid -> copyEntries[uid]?.takeIf(::isMoreThanMoment)?.let { uid to it } },
            merge(records.collections, "collections", ::parse),
            merge(records.vehicleMonths, "vehicle_months", ::parse),
            merge(records.vehicleAssisted, "vehicle_assisted", ::parse),
        )
        val collectionsMoved = "collections" in byTable
        all = if (collectionsMoved) {
            copyEntries.values.mapNotNull(::momentOf).associateBy { it.uid }
        } else {
            all - touched + touched.mapNotNull { copyEntries[it]?.let(::momentOf) }.associateBy { it.uid }
        }
        if (collectionsMoved || marksMoved) apiTrips = tripsOf()
    }

    private fun parse(text: String) = Spec.json.parseToJsonElement(text).jsonObject

    /** An entry's Moment if it has words; a Moment a trip takes in from outside its days names that trip, as the spec's entryToMoment has it. */
    private fun momentOf(entry: kotlinx.serialization.json.JsonObject): MomentRecord? {
        val moment = Entries.toMoment(entry) ?: return null
        val trip = _records.value.collections.entries.firstOrNull { (_, collection) ->
            moment.uid in Entries.strings(collection, "include") && moment.uid !in Entries.strings(collection, "exclude")
        }?.key
        return if (moment.trip == trip) moment else moment.copy(trip = trip)
    }

    /** The trips, worked out from the collections and their marks. */
    private fun tripsOf(): List<TripRecord> =
        _records.value.collections.values.map { Entries.toTrip(it, copyEntries.values) }.sortedWith(compareBy({ it.start }, { it.id }))

    private fun kotlinx.serialization.json.JsonObject.text(key: String) =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    /**
     * The entries the pages read: the copy's beyond its Moments, with the phone's changes and
     * deletions, those set aside out of sight and those being taken back as the site has them.
     */
    private fun showEntries() {
        val changes = changedEntries - holding
        _entries.value = _records.value.entries - (removedEntries - holding) - changes.keys - setAside +
            changes.filterValues(::isMoreThanMoment).filterKeys { it !in setAside }
        _checkins.value = cc.hyoban.moments.data.things.Checkins.of(_entries.value.values)
    }

    /** The Moments among the phone's changes, worked out again. */
    private fun showPhone() {
        phone = changedEntries.values.mapNotNull(::momentOf).associateBy { it.uid }
    }

    /** The phone's changes and deletions as they now are, kept on the phone. */
    private fun setChanges(
        entries: Map<String, kotlinx.serialization.json.JsonObject> = changedEntries,
        removed: Set<String> = removedEntries,
    ) {
        changedEntries = entries
        removedEntries = removed
        showPhone()
        local?.writeChangedEntries(entries, removed)
    }

    /**
     * Keeps an entry written on the phone or changed there until it is published, whatever it
     * is: a Moment, a check-in, a device; [place] is the one it made, if it made one. Saved as
     * another shape than it was (a Moment as a check-in), it is that from now on. The photos the
     * app copied in for it that it no longer has go, and the places only it was at.
     */
    suspend fun saveEntry(entry: kotlinx.serialization.json.JsonObject, place: Pair<String, Place>? = null) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's changes" }
        withContext(io) {
            val uid = entry.entryUid
            val stored = copyEntries[uid]
            val before = changedEntries[uid]
            if (place != null) phonePlaces = phonePlaces + place
            setChanges(
                if (stored != null && JsonObjectsEqualApartFrom(stored, entry, "updatedAt")) changedEntries - uid else changedEntries + (uid to entry),
                removedEntries - uid,
            )
            letGo(before, changedEntries[uid])
            local.writePlaces(phonePlaces)
            followBounds()
            publishPlaces()
            publish()
        }
    }

    /** Deletes an entry: one only on the phone at once, one the site has once published. */
    suspend fun deleteEntry(uid: String) = lock.withLock {
        checkNotNull(local) { "No store for the phone's changes" }
        withContext(io) {
            deleteNow(uid)
            followBounds()
            publishPlaces()
            publish()
        }
    }

    /** Takes back the phone's change to an entry, or its deletion. */
    suspend fun revertEntry(uid: String) = lock.withLock {
        withContext(io) {
            val before = changedEntries[uid]
            setChanges(changedEntries - uid, removedEntries - uid)
            letGo(before, null)
            followBounds()
            publishPlaces()
            publish()
        }
    }

    /** Whether an entry holds what the first shape's Moment of it doesn't: it is none, or has other parts. */
    private fun isMoreThanMoment(entry: kotlinx.serialization.json.JsonObject): Boolean {
        val parts = entry["parts"] as? kotlinx.serialization.json.JsonObject ?: return true
        return !Entries.isMoment(entry) || parts.keys.any { it !in MOMENT_PARTS }
    }

    /**
     * Forgets the copy, on the phone and in memory, as when the records are to come from
     * another API. What was written on the phone stays; what was changed or deleted of the
     * old API's records goes with them.
     */
    suspend fun clear() = lock.withLock {
        withContext(io) {
            val old = copyEntries.keys
            copy.clear()
            copyEntries = emptyMap()
            val before = changedEntries.filterKeys { it in old }
            setChanges(changedEntries - old, emptySet())
            before.values.forEach { letGo(it, null) }
            all = emptyMap()
            apiPlaces = emptyMap()
            publishPlaces()
            apiTrips = emptyList()
            _records.value = Records()
            showTrips()
            _error.value = null
            publish()
        }
    }

    /** Keeps a Moment written on the phone, with the place it made if it made one. */
    suspend fun addLocal(moment: MomentRecord, place: Pair<String, Place>? = null) = saveEntry(Entries.fromMoment(moment), place)

    /** Of [uids], those here already: the site's, or kept, changed or deleted on the phone. */
    suspend fun known(uids: Collection<String>): Set<String> = lock.withLock { uids.filterTo(HashSet(), ::isKnown) }

    private fun isKnown(uid: String) =
        uid in copyEntries || uid in changedEntries || uid in removedEntries

    /**
     * Keeps what an import brought, all at once: [moments] as Moments written on the phone,
     * [entries] as entries changed on it and [places] as the phone's, kept on their own as an
     * entry is at one with no Moment, until they are published. Those whose uid is here already
     * are left as they are; gives how many were kept.
     */
    suspend fun addImported(
        moments: List<MomentRecord>,
        entries: List<kotlinx.serialization.json.JsonObject>,
        places: Map<String, Place> = emptyMap(),
    ): Int = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's changes" }
        withContext(io) {
            val newPlaces = places.filterKeys { it !in phonePlaces && it !in apiPlaces }
            if (newPlaces.isNotEmpty()) {
                phonePlaces = phonePlaces + newPlaces
                keptPlaces = keptPlaces + newPlaces.keys
                local.writePlaces(phonePlaces)
                local.writeKeptPlaces(keptPlaces)
                publishPlaces()
            }
            val added = (moments.map(Entries::fromMoment) + entries).filterNot { isKnown(it.entryUid) }.distinctBy { it.entryUid }
            if (added.isNotEmpty()) setChanges(changedEntries + added.associateBy { it.entryUid })
            publish()
            added.size
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
    suspend fun setPlaceKind(id: String, kind: String) = editPlace(id) { it.copy(kind = kind) }

    /**
     * Changes a place, by its id, so every Moment and entry at it stays there: one the phone
     * made at once, one of the site's as a change kept until it is published, taken back when
     * it is the site's again. Moved, it loses its division, which the API looks up again.
     */
    suspend fun editPlace(id: String, edit: (Place) -> Place) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's own places" }
        withContext(io) {
            val own = phonePlaces[id]
            val site = apiPlaces[id]
            fun edited(place: Place) = edit(place).let {
                if (it.latitude == place.latitude && it.longitude == place.longitude) it else it.copy(division = null)
            }
            when {
                own != null -> {
                    phonePlaces = phonePlaces + (id to edited(own))
                    local.writePlaces(phonePlaces)
                }
                site != null -> {
                    val place = edited(changedPlaces[id] ?: site).let {
                        if (it.latitude == site.latitude && it.longitude == site.longitude) it.copy(division = site.division) else it
                    }
                    changedPlaces = if (place == site) changedPlaces - id else changedPlaces + (id to place)
                    local.writeChangedPlaces(changedPlaces)
                }
                else -> error("Unknown place $id")
            }
            publishPlaces()
            publish()
        }
    }

    /**
     * Takes back every change kept on the phone: the Moments written here deleted, their
     * photos' copies and the places only they were at with them, and the site's Moments,
     * places, trips and entries as the site has them again, deletions undone.
     */
    suspend fun clearChanges() = lock.withLock {
        withContext(io) {
            val before = changedEntries.values
            setChanges(emptyMap(), emptySet())
            before.forEach { letGo(it, null) }
            setAside = emptySet()
            holding = emptySet()
            changedPlaces = emptyMap()
            changedTrips = emptyMap()
            local?.run {
                writeChangedPlaces(changedPlaces)
                writeChangedTrips(changedTrips)
            }
            writeHeld()
            showTrips()
            publishPlaces()
            publish()
        }
    }

    /** Takes back a change to one of the site's places. */
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
     * written here, or one of the site's until the changes are published, as [saveEntry] does.
     */
    suspend fun updateLocal(moment: MomentRecord, place: Pair<String, Place>? = null) = saveEntry(Entries.fromMoment(moment), place)

    /**
     * Deletes a Moment, or any entry. One written on the phone goes, with its photos the app
     * copied in and the places it made that nothing else is at; one of the site's drops any
     * change kept for it and is taken off the site when the changes are published.
     */
    suspend fun delete(uid: String) = lock.withLock {
        withContext(io) {
            setAside = setAside - uid
            deleteNow(uid)
            writeHeld()
            followBounds()
            publishPlaces()
            publish()
        }
    }

    private fun deleteNow(uid: String) {
        val before = changedEntries[uid]
        setChanges(changedEntries - uid, if (uid in copyEntries) removedEntries + uid else removedEntries)
        letGo(before, null)
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
            followBounds()
            publishPlaces()
            publish()
        }
    }

    private fun revertNow(uid: String) {
        if (uid !in copyEntries) return
        val before = changedEntries[uid]
        setChanges(changedEntries - uid, removedEntries - uid)
        letGo(before, null)
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
     * What the phone kept for a change, [before], now it is [after] (none when it is gone or
     * published): the photos the app copied in that it no longer has, and the places it made
     * that nothing is at any more.
     */
    private fun letGo(before: kotlinx.serialization.json.JsonObject?, after: kotlinx.serialization.json.JsonObject?) {
        val local = local ?: return
        before ?: return
        val kept = after?.let(Entries::toMoment)?.media.orEmpty().map { it.uid }.toSet()
        deleteCopies(local, Entries.toMoment(before)?.media.orEmpty().filter { it.uid !in kept })
        if (dropUnused(listOfNotNull(before.text("place")))) local.writePlaces(phonePlaces)
    }

    /**
     * Of [ids], the phone's places a Moment made that no change is at any more, forgotten;
     * those kept on their own stay. Whether any went.
     */
    private fun dropUnused(ids: List<String>): Boolean {
        val used = changedEntries.values.mapNotNullTo(HashSet()) { it.text("place") }
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
        val (changes, known, own, deletions, edits) = lock.withLock {
            // A change being taken back isn't published, whatever becomes of it.
            Snapshot(changedEntries - holding, apiPlaces, phonePlaces, (removedEntries - holding).toList(), changedPlaces)
        }
        // The Moments go as Moments, for their photos and their bills; the rest as the entries they are.
        val written = lock.withLock { changes.values.mapNotNull(::momentOf) }.sortedWith(oldestFirst)
        val things = changes - written.map { it.uid }.toSet()
        val extra = outside?.pending() ?: Outside()
        val trips = lock.withLock { changedTrips }
        if (changes.isEmpty() && deletions.isEmpty() && edits.isEmpty() && extra.size == 0 && trips.isEmpty()) return
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
        // The site's places changed here go under their own ids, which the API takes the name, point and kind of.
        // An entry that isn't a Moment, as an import's check-in, may be at one of the phone's places too.
        val entryPlaces = things.values.mapNotNull { (it["place"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val places = (moments.flatMap { it.places } + entryPlaces).distinct().filter { it !in known }
            .associateWith { id -> own[id] ?: error("Unknown place $id") } + edits
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
        withContext(io) {
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
        }
        // Once the change set is on its way it goes through and the phone notes it: a cancel
        // coming as it is sent can't leave the site with what the phone still calls unpublished.
        withContext(NonCancellable) {
            val applied = withContext(io) {
                writer.applyChanges(
                    ChangeSet(
                        places,
                        moments,
                        deletions + alone.filter { it.part == null }.map { it.uid },
                        entries = alone.filter { it.part != null }.map { it.entry() } + things.values,
                        parts = parts,
                        collections = trips.values.toList(),
                    ),
                )
            }
            outside?.published(extra)
            lock.withLock {
                withContext(io) {
                    // A trip changed again while this was sent stays to be published.
                    changedTrips = changedTrips.filter { (id, collection) -> trips[id] != collection }
                    local?.writeChangedTrips(changedTrips)
                    // The site has the entries as sent, and the Moments as it answered them, until the copy
                    // has them from the rows. The trips sent are the site's, so one made on the phone stays
                    // in sight until the copy has it.
                    val sent = things + applied.moments.associate { it.uid to Entries.fromMoment(it) }
                    val gone = deletions.toSet()
                    copyEntries = copyEntries - gone + sent
                    val records = _records.value
                    _records.value = records.copy(
                        entries = records.entries - gone - sent.keys + sent.filterValues(::isMoreThanMoment),
                        collections = records.collections + trips,
                    )
                    all = all - gone - sent.keys + applied.moments.associateBy { it.uid }
                    apiTrips = tripsOf()
                    showTrips()
                }
            }
            lock.withLock {
                withContext(io) {
                    apiPlaces = apiPlaces + applied.places.filterValues { it !in apiPlaces }.entries.associate { (given, id) -> id to places.getValue(given) } +
                        edits
                    // A place changed again while this was sent stays to be published.
                    changedPlaces = changedPlaces.filter { (id, place) -> edits[id] != place }
                    local?.writeChangedPlaces(changedPlaces)
                    // What was sent leaves the phone, its copied photos with it; one changed again
                    // while this was sent stays to be published.
                    val settled = changes.filter { (uid, entry) -> changedEntries[uid] == entry }
                    setChanges(changedEntries - settled.keys, removedEntries - deletions.toSet())
                    settled.values.forEach { letGo(it, null) }
                    adoptPlaces(applied.places)
                    publishPlaces()
                    publish()
                }
            }
            onProgress(PublishProgress(total, total))
        }
    }

    private data class Snapshot(
        val changes: Map<String, kotlinx.serialization.json.JsonObject>,
        val known: Map<String, Place>,
        val own: Map<String, Place>,
        val deletions: List<String>,
        val edits: Map<String, Place>,
    )

    /**
     * The phone's places the API now has, by their ids there: gone from the phone's, and
     * the phone's changed entries at them, Moments too, moved to the API's ids. The API keeps
     * the id a place is sent with, so this moves nothing but what an older API renamed.
     */
    private fun adoptPlaces(ids: Map<String, String>) {
        val local = local ?: return
        val adopted = ids.filterKeys { it in phonePlaces }
        if (adopted.isEmpty()) return
        phonePlaces = phonePlaces - adopted.keys
        keptPlaces = keptPlaces - adopted.keys
        fun moved(entry: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
            val id = (entry["place"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return entry
            val to = adopted[id] ?: return entry
            return kotlinx.serialization.json.JsonObject(entry + ("place" to kotlinx.serialization.json.JsonPrimitive(to)))
        }
        val changed = changedEntries.mapValues { (_, entry) -> moved(entry) }
        if (changed != changedEntries) setChanges(changed)
        _records.value = _records.value.let { it.copy(entries = it.entries.mapValues { (_, entry) -> moved(entry) }) }
        local.writePlaces(phonePlaces)
        local.writeKeptPlaces(keptPlaces)
    }

    /** Whether a Moment, or any entry, was written on this phone rather than read from the API. */
    fun isLocal(uid: String): Boolean = uid in changedEntries && uid !in copyEntries

    /** Whether one of the site's Moments has a change on the phone not yet published. */
    fun isChanged(uid: String): Boolean = uid !in holding && uid in phone && uid in copyEntries

    /** A Moment by its uid as the phone shows it; none for an entry that isn't one, as a check-in. */
    fun moment(uid: String): MomentRecord? =
        phone[uid]?.takeIf { uid !in holding }
            ?: all[uid]?.takeIf { isShown(it) && ((uid !in removedEntries && uid !in changedEntries) || uid in holding) }

    private fun publish() {
        showPhone()
        showEntries()
        // A change being taken back, as though it were already.
        val phone = phone.filterKeys { it !in holding }
        val changes = changedEntries - holding
        val removed = removedEntries - holding
        // A Moment a trip changed or made on the phone takes in from outside its days names that trip already.
        val taken = changedTrips.flatMap { (id, collection) ->
            (Entries.strings(collection, "include") - Entries.strings(collection, "exclude").toSet()).map { it to id }
        }.toMap()
        _moments.value = (all.values.filter { isShown(it) && it.uid !in changes && it.uid !in removed } + phone.values)
            .filter { it.uid !in setAside }
            .map { moment -> taken[moment.uid]?.takeIf { it != moment.trip }?.let { moment.copy(trip = it) } ?: moment }
            .sortedWith(newestFirst)
        // A Moment the site has as one is changed; one it has as a check-in, or not at all, is added.
        val (added, changed) = phone.values.filter { it.uid !in setAside }.sortedWith(oldestFirst).partition { it.uid !in all }
        _pending.value = PendingChanges(
            added,
            changed.map { Change(all[it.uid], it) },
            removed.mapNotNull { all[it] }.sortedWith(oldestFirst),
            changedPlaces.mapNotNull { (id, place) -> apiPlaces[id]?.let { PlaceChange(id, it, place) } }.sortedBy { it.after.name },
            outside?.pending() ?: Outside(),
            changedTrips.map { (id, collection) -> TripChange(id, apiTrips.firstOrNull { it.id == id }, collection) },
            (changes - phone.keys).map { (uid, entry) -> EntryChange(_records.value.entries[uid], entry) } +
                removed.filter { it !in all }.mapNotNull { uid -> _records.value.entries[uid]?.let { EntryChange(it, null) } },
        )
    }

    private fun publishPlaces() {
        _places.value = phonePlaces + apiPlaces + changedPlaces
    }

    // A private one is the phone's to see, marked so.
    private fun isShown(moment: MomentRecord) = moment.published != null

    /** The trips the screens show: the API's, with the phone's changes to their names and days. */
    private fun showTrips() {
        // A trip made on the phone is worked out from its collection as the API's are.
        val made = changedTrips.filterKeys { id -> apiTrips.none { it.id == id } }.values
            .map { Entries.toTrip(it, copyEntries.values) }
        _trips.value = made + apiTrips.map { trip ->
            val changed = changedTrips[trip.id] ?: return@map trip
            val span = changed["span"] as? kotlinx.serialization.json.JsonObject
            trip.copy(
                covers = Entries.coversOf(changed).map { (entry, media) -> cc.hyoban.moments.data.spec.Cover(entry, media) },
                title = (changed["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: trip.title,
                start = (span?.get("start") as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(10) ?: trip.start,
                end = (span?.get("end") as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(10) ?: trip.end,
                startAt = (span?.get("start") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.length > 10 },
                endAt = (span?.get("end") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.length > 10 },
            )
        }.sortedByDescending { it.start }
    }

    /** Whether a trip is private: by the phone's change to it, else as the API has it. */
    fun isTripPrivate(id: String): Boolean =
        (changedTrips[id] ?: _records.value.collections[id])?.let(Entries::isPrivate) == true

    /** Whether a trip was made on the phone and isn't published yet. */
    fun isTripNew(id: String): Boolean = id in changedTrips && apiTrips.none { it.id == id }

    /**
     * Makes a trip on the phone: a collection of its own, public unless said otherwise, kept
     * like any change until it is published. Its id is its uid in lowercase, as the API
     * will know it. Returns that id.
     */
    suspend fun createTrip(
        title: String,
        start: String,
        end: String,
        visibility: cc.hyoban.moments.data.spec.Visibility,
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        covers: List<Pair<String, String>> = emptyList(),
        from: String? = null,
        to: String? = null,
    ): String = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's changes" }
        withContext(io) {
            val now = cc.hyoban.moments.data.local.recordTime()
            val base = kotlinx.serialization.json.buildJsonObject {
                put("uid", kotlinx.serialization.json.JsonPrimitive(cc.hyoban.moments.data.local.Ulid.next()))
                put("kind", kotlinx.serialization.json.JsonPrimitive("trip"))
                put("createdAt", kotlinx.serialization.json.JsonPrimitive(now))
            }
            val collection = Entries.editCollection(base, title, start, end, visibility, now, include, exclude, covers)
                .let { made -> withBounds(made, from, to) }
            val id = Entries.collectionKey(collection)
            changedTrips = changedTrips + (id to collection)
            local.writeChangedTrips(changedTrips)
            showTrips()
            publish()
            id
        }
    }

    /** An entry's time as the phone has it now, or none once it is deleted. */
    private fun entryAt(uid: String): String? =
        (changedEntries[uid] ?: copyEntries[uid]?.takeIf { uid !in removedEntries })
            ?.let { (it["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content }

    /**
     * A trip running from the record [from] to the record [to], either or both: that end of
     * its span the record's time, kept following it; without one, the end as given.
     */
    private fun withBounds(collection: kotlinx.serialization.json.JsonObject, from: String?, to: String?): kotlinx.serialization.json.JsonObject {
        val span = collection["span"] as? kotlinx.serialization.json.JsonObject ?: return collection
        val start = from?.let(::entryAt)
        val end = to?.let(::entryAt)
        val kept = listOfNotNull(start?.let { "from" to from }, end?.let { "to" to to })
        return kotlinx.serialization.json.JsonObject(
            collection - "bounds" + ("span" to kotlinx.serialization.json.JsonObject(
                span + listOfNotNull(start?.let { "start" to kotlinx.serialization.json.JsonPrimitive(it) }, end?.let { "end" to kotlinx.serialization.json.JsonPrimitive(it) }),
            )) + if (kept.isEmpty()) emptyMap() else mapOf(
                "bounds" to kotlinx.serialization.json.JsonObject(kept.associate { (key, uid) -> key to kotlinx.serialization.json.JsonPrimitive(uid) }),
            ),
        )
    }

    /** The records a trip runs between, each end's if it has one, or none. */
    fun boundsOf(collection: kotlinx.serialization.json.JsonObject): Pair<String?, String?>? {
        val bounds = collection["bounds"] as? kotlinx.serialization.json.JsonObject ?: return null
        val from = (bounds["from"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val to = (bounds["to"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return if (from == null && to == null) null else from to to
    }

    /**
     * Has each trip between records follow them: each end of its span its record's time as it
     * now is, and, the record deleted, that end its own. A trip brought back to what the site
     * has is no longer a change.
     */
    private fun followBounds() {
        val now by lazy { cc.hyoban.moments.data.local.recordTime() }
        val site = _records.value.collections
        var trips = changedTrips
        for ((id, collection) in site.filterKeys { it !in changedTrips } + changedTrips) {
            val (from, to) = boundsOf(collection) ?: continue
            // A record gone, its end stays where it was, its own from then on.
            val followed = withBounds(collection, from?.takeIf { entryAt(it) != null }, to?.takeIf { entryAt(it) != null })
            if (followed == collection) continue
            val onSite = site[id]?.takeIf { apiTrips.any { trip -> trip.id == id } }
            trips = if (onSite != null && JsonObjectsEqualApartFrom(onSite, followed, "updatedAt")) {
                trips - id
            } else {
                trips + (id to kotlinx.serialization.json.JsonObject(followed + ("updatedAt" to kotlinx.serialization.json.JsonPrimitive(now))))
            }
        }
        if (trips == changedTrips) return
        changedTrips = trips
        local?.writeChangedTrips(changedTrips)
        showTrips()
    }

    /** A trip as its collection, with the phone's change to it. */
    fun tripCollection(id: String): kotlinx.serialization.json.JsonObject? =
        changedTrips[id] ?: apiTrips.firstOrNull { it.id == id }?.let { Entries.collectionOf(it, _records.value.collections[id]) }

    /** Keeps a change to a trip on the phone, its name, its days and who sees it, until it is published. */
    suspend fun editTrip(
        id: String,
        title: String,
        start: String,
        end: String,
        visibility: cc.hyoban.moments.data.spec.Visibility,
        include: List<String>? = null,
        exclude: List<String>? = null,
        covers: List<Pair<String, String>>? = null,
        from: String? = null,
        to: String? = null,
    ) = lock.withLock {
        val local = checkNotNull(local) { "No store for the phone's changes" }
        withContext(io) {
            // One made on the phone is edited as it was made, and stays a change however it ends.
            val made = changedTrips[id]?.takeIf { apiTrips.none { it.id == id } }
            val base = made ?: Entries.collectionOf(
                apiTrips.firstOrNull { it.id == id } ?: error("Unknown trip $id"),
                _records.value.collections[id],
            )
            val now = cc.hyoban.moments.data.local.recordTime()
            val edited = Entries.editCollection(
                base, title, start, end, visibility, now,
                include = include ?: Entries.strings(base, "include"),
                exclude = exclude ?: Entries.strings(base, "exclude"),
                covers = covers ?: Entries.coversOf(base),
            ).let { edited -> withBounds(edited, from, to) }
            val same = made == null && JsonObjectsEqualApartFrom(base, edited, "updatedAt")
            changedTrips = if (same) changedTrips - id else changedTrips + (id to edited)
            local.writeChangedTrips(changedTrips)
            showTrips()
            publish()
        }
    }

    /** Takes back the phone's change to a trip; one made on the phone is gone with it. */
    suspend fun revertTrip(id: String) = lock.withLock {
        withContext(io) {
            changedTrips = changedTrips - id
            local?.writeChangedTrips(changedTrips)
            showTrips()
            publish()
        }
    }

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
    /** The trips changed on the phone. */
    val trips: List<TripChange> = emptyList(),
    /** The devices and subscriptions written, changed or deleted on the phone. */
    val entries: List<EntryChange> = emptyList(),
) {
    val size get() = added.size + changed.size + removed.size + places.size + outside.size + trips.size + entries.size
}

/** An entry that isn't a Moment as the site has it, [before] (null when new), and as it will be, [after] (null when deleted). */
data class EntryChange(val before: kotlinx.serialization.json.JsonObject?, val after: kotlinx.serialization.json.JsonObject?)

/** A trip as the site has it, [before] (none for one made on the phone), and the collection it is changed to or made as. */
data class TripChange(val id: String, val before: TripRecord?, val after: kotlinx.serialization.json.JsonObject)

/** One of the site's places as the site has it, [before], and with the kind changed on the phone. */
data class PlaceChange(val id: String, val before: Place, val after: Place)

/** One of the site's Moments as the site has it, [before] (null when the copy no longer has it), and as changed. */
data class Change(val before: MomentRecord?, val after: MomentRecord)

/** Whether two records are the same but for [key]. */
private fun JsonObjectsEqualApartFrom(a: kotlinx.serialization.json.JsonObject, b: kotlinx.serialization.json.JsonObject, key: String) =
    (a - key) == (b - key)

/** The parts a Moment of the first shape holds. */
private val MOMENT_PARTS = setOf("text", "media", "food")
