package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Module
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.snapshotFlow
import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.local.AmapShare
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.SAME_PLACE_METRES
import cc.hyoban.moments.data.local.SharedPlace
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.coordinatesName
import cc.hyoban.moments.data.local.nearestPlaces
import cc.hyoban.moments.data.local.newPlaceId
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.undo
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.store.PublishProgress
import cc.hyoban.moments.data.store.Shown
import cc.hyoban.moments.data.store.shown
import cc.hyoban.moments.publish.PublishWorker
import cc.hyoban.moments.data.trips.TripRoutes
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.viewer.MotionCache
import cc.hyoban.moments.widget.Widgets
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app shares: built once by the Application, handed to the screens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cities: List<cc.hyoban.moments.ui.map.FogRegion>? = null

    /** The fog's cities, read from the app's assets the first time they're asked for and kept: the map's fog, and where the homes set by hand are. */
    suspend fun cityRegions(): List<cc.hyoban.moments.ui.map.FogRegion> = cities ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
        appContext.assets.open(cc.hyoban.moments.ui.map.FOG_REGIONS_ASSET).bufferedReader().use { cc.hyoban.moments.ui.map.parseFogRegions(it) }
    }.also { cities = it }

    val settings = Settings(context)
    /** GPS tracks stay on this phone, private; only the spans made public publish, simplified. */
    val footprints = cc.hyoban.moments.footprints.FootprintStore(context)
    val footprintDiagnostics = cc.hyoban.moments.footprints.FootprintDiagnostics(
        File(context.noBackupFilesDir, "footprint-diagnostics"), scope,
    )
    val connectionRules = cc.hyoban.moments.footprints.ConnectionRulesStore(context)
    /** The ledger stays on this phone, outside API sync and Android's backup; only its public bills publish. */
    val ledger = cc.hyoban.moments.ledger.LedgerStore(context)
    /** The databases backed up to the owner's WebDAV directory, when one is set. */
    val backup = cc.hyoban.moments.backup.Backup(context)
    /** The app's own updates, from its GitHub releases. */
    val updates = cc.hyoban.moments.update.Updates(context)

    // Each its own: `copy.db` the copy of what the API serves, row for row as D1 keeps it,
    // out of the backup since a sync brings it again; `local/` the Moments written on this
    // phone, their photos and places, which no sync touches. `api/` may still hold the copy
    // as it was kept before, as JSON files, and `data/` what the app recorded before it only
    // showed the site's records; both are left as they were.
    val localStore = DataStore(File(context.filesDir, "local"))
    private val api = MomentsApi(baseUrl = { settings.apiUrl.value }, token = { settings.apiToken.value })
    val repository = MomentsRepository(cc.hyoban.moments.data.copy.CopyStore(File(context.noBackupFilesDir, "copy.db")), api, local = localStore, writer = api,
        outside = cc.hyoban.moments.data.store.PhoneOutside(ledger, footprints),
    )
    /** Swarm's check-ins brought in from its export, their photos beside the phone's own Moments'. */
    val swarm = cc.hyoban.moments.data.swarm.SwarmImport(
        open = { uri -> appContext.contentResolver.openInputStream(uri) ?: error("Can't read $uri") },
        repository = repository,
        mediaDirectory = File(localStore.root, "media"),
        scope = scope,
    )
    val mediaUrls = MediaUrls { settings.assetOrigin.value }
    /** Places by name and around a point, for a new Moment's place. */
    val placeSearch = PlaceSearch(BuildConfig.PLACE_SEARCH_URL, tencentKey = { settings.tencentMapKey.value },
        amapKey = { settings.amapKey.value },
        tencentFirst = { settings.chinaPlaces.value == CHINA_PLACES_TENCENT },
    )
    /** Where each bill was paid, by the footprints. */
    val billPlaces = cc.hyoban.moments.footprints.BillPlaces(
        footprints, placeSearch, repository.places, repository.checkins,
        checkinsOn = { settings.isOn(Module.CHECKIN) },
    ) { settings.isOn(Module.FOOTPRINTS) }
    /** The trips' routes along the roads, from the API. */
    val routes = TripRoutes({ settings.apiUrl.value }, File(context.filesDir, "routes.json"), token = { settings.apiToken.value })

    /** The routes of the trips the API hasn't routed, as one made on the phone, worked out here the same way. */
    val phoneRoutes = cc.hyoban.moments.data.trips.PhoneRoutes(File(context.filesDir, "phone-routes.json"))

    /** The Moments and trips the screens show: all of them, or in demo mode only the public's. */
    val shown: StateFlow<Shown> =
        kotlinx.coroutines.flow.combine(repository.moments, repository.trips, snapshotFlow { settings.demo }) { moments, trips, demo ->
            shown(moments, trips, demo, repository::isTripPrivate)
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, shown(repository.moments.value, repository.trips.value, settings.demo, repository::isTripPrivate))
    val shownMoments: StateFlow<List<MomentRecord>> = shown.map { it.moments }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, shown.value.moments)
    val shownTrips: StateFlow<List<cc.hyoban.moments.data.spec.TripRecord>> = shown.map { it.trips }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, shown.value.trips)

    /**
     * The legs of each trip the API won't route, by trip id: with an API, only one made on the
     * phone and not yet published, so the site's are never asked for while their routes load.
     */
    private val phoneLegs: kotlinx.coroutines.flow.StateFlow<Map<String, List<cc.hyoban.moments.data.trips.TripLeg>>> =
        kotlinx.coroutines.flow.combine(repository.trips, repository.moments, repository.places, routes.routes) { trips, moments, places, roads ->
            trips.filter { it.id !in roads && (!settings.connected || repository.isTripNew(it.id)) }.associate { trip ->
                trip.id to cc.hyoban.moments.data.trips.legsOf(trip, cc.hyoban.moments.data.trips.stopsOf(trip, moments, trips, places))
            }
        // Every trip's stops, its Moments sought among all of them: off the main thread, which many trips would stall.
        }.flowOn(Dispatchers.Default).stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

    /**
     * Each trip's route as the screens draw and play it: the footprints recorded on it where a
     * span following the trip is public (each unbroken stretch solid, the gaps between dashed,
     * never guessed), else the route along the roads, the API's or, for a trip it hasn't
     * routed, the phone's.
     */
    val tripRoutes: kotlinx.coroutines.flow.StateFlow<Map<String, List<cc.hyoban.moments.data.trips.RouteSegment>>> =
        kotlinx.coroutines.flow.combine(routes.routes, repository.records, repository.trips, phoneLegs, phoneRoutes.lines) { roads, records, trips, legs, lines ->
            val own = legs.mapValues { (_, tripLegs) -> cc.hyoban.moments.data.trips.segmentsOf(tripLegs, lines) }.filterValues { it.isNotEmpty() }
            roads + own + trips.mapNotNull { trip ->
                val uid = repository.tripUid(trip)
                val track = records.tracks.values.firstOrNull { (it["collection"] as? kotlinx.serialization.json.JsonPrimitive)?.content == uid }
                    ?: return@mapNotNull null
                val stretches = (track["segments"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { segment ->
                    (segment as? kotlinx.serialization.json.JsonObject)?.get("coordinates")?.let { it as? kotlinx.serialization.json.JsonArray }
                        ?.mapNotNull { point ->
                            val pair = point as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                            val lng = (pair.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                            val lat = (pair.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                            if (lng != null && lat != null) lng to lat else null
                        }?.takeIf { it.size > 1 }
                }
                if (stretches.isEmpty()) null
                else trip.id to (stretches.map { cc.hyoban.moments.data.trips.RouteSegment(it, straight = false) } +
                    stretches.zipWithNext { a, b -> cc.hyoban.moments.data.trips.RouteSegment(listOf(a.last(), b.first()), straight = true) })
            }
        }.flowOn(Dispatchers.Default).stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

    /** Where shared photos wait for a Moment; copied again into its own store when it is saved. */
    private val sharedDirectory = File(context.cacheDir, "shared")

    init {
        // Shared photos no draft took within a day are left behind.
        sharedDirectory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach(File::delete)
        // Routes were once kept per trip from the site's trip pages, under routes/.
        File(context.filesDir, "routes").takeIf { it.isDirectory }?.deleteRecursively()
        scope.launch { ledger.load() }
        // The widgets show only what the public sees in demo mode, so they are drawn again as it turns.
        scope.launch { snapshotFlow { settings.demo }.drop(1).collect { Widgets.update(appContext) } }
        // The legs of the trips the API hasn't routed are routed here, once they settle.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            phoneLegs.debounce(ROUTE_SETTLE_MS).collectLatest { legs ->
                phoneRoutes.fill(legs.values.flatten().mapNotNull { cc.hyoban.moments.data.trips.requestOf(it) })
            }
        }
        cc.hyoban.moments.backup.BackupWorker.schedule(appContext, backup, replace = false)
        cc.hyoban.moments.update.UpdateWorker.schedule(appContext, updates.auto)
        // A bill or a span going public, or private again, is a change to publish too.
        scope.launch {
            kotlinx.coroutines.flow.combine(ledger.ledger, footprints.revision) { _, _ -> }.collect {
                withContext(Dispatchers.IO) { repository.noteOutside() }
            }
        }
        scope.launch {
            repository.load()
            routes.load()
            phoneRoutes.load()
            refreshAll()
        }
        // The videos' disk cache opens its index and database off the main thread, before a
        // video is first opened, rather than in the frame that opens it.
        scope.launch(Dispatchers.IO) { MotionCache.of(appContext) }
        // The map's native library, 13 MB, loaded off the main thread too, so the map tab's
        // first showing doesn't load it in the frame that opens it.
        scope.launch(Dispatchers.IO) { runCatching { System.loadLibrary("maplibre") } }
    }

    fun refresh() {
        scope.launch { refreshAll() }
    }

    /**
     * Reads from another API: the copy of the one before is dropped rather than brought up
     * to a revision it never had, and everything is read again from the new one.
     */
    fun changeApi(url: String) {
        val before = settings.apiUrl.value
        settings.apiUrl.set(url)
        if (settings.apiUrl.value == before) return
        settings.assetOrigin.set("")
        scope.launch {
            repository.clear()
            routes.clear()
            refreshAll()
        }
    }

    /**
     * 初次打开's 连接网站: keeps the API and its token and reads everything from it, in the app's
     * own scope, so leaving the page leaves the reading going. One that can't be read is
     * forgotten again, the phone left its own.
     */
    fun connect(url: String, token: String): Deferred<Result<Long>> {
        val before = settings.apiUrl.value to settings.apiToken.value
        settings.apiToken.set(token)
        settings.apiUrl.set(url)
        return scope.async {
            repository.clear()
            routes.clear()
            repository.refresh()
                .onSuccess { revision ->
                    readAssetOrigin()
                    routes.refresh(revision)
                }
                .onFailure {
                    settings.apiUrl.set(before.first)
                    settings.apiToken.set(before.second)
                    repository.clear()
                }
        }
    }

    /** Something to say in a snackbar, and what its one action, if any, does; [onDismiss] when it goes without it. */
    class Message(val text: String, val action: String? = null, val onAction: () -> Unit = {}, val onDismiss: () -> Unit = {})

    private val _messages = Channel<Message>(Channel.BUFFERED)

    /**
     * What the app has to say while it is open, shown as snackbars in the order said; kept
     * until the app's page is up to show them, as when a share opens it.
     */
    val messages: Flow<Message> = _messages.receiveAsFlow()

    private fun say(text: String) {
        _messages.trySend(Message(text))
    }

    /**
     * Offers [place], a check-in's just kept from a stay, to the bills paid in that stay from
     * [start] to [end] that have no place of their own nor a Moment's; nothing is said when
     * there are none or the ledger is off.
     */
    fun offerPlaceToBills(place: String, start: Long, end: Long) {
        if (!settings.isOn(Module.LEDGER)) return
        scope.launch {
            val bills = ledger.ledger.value.txns.filter {
                it.kind != cc.hyoban.moments.ledger.TxnKind.TRANSFER && it.place == null && it.moment == null && it.time in start..end
            }
            if (bills.isEmpty()) return@launch
            _messages.send(
                Message(
                    appContext.resources.getQuantityString(R.plurals.message_checkin_bills, bills.size, bills.size),
                    action = appContext.getString(R.string.ledger_use_place),
                    onAction = { scope.launch { ledger.saveTxns(bills.map { it.copy(place = place) }) } },
                ),
            )
        }
    }

    private val _sharedPlace = MutableStateFlow<SharedPlace?>(null)

    /** A place shared into the app from a map, until a new Moment takes it. */
    val sharedPlace: StateFlow<SharedPlace?> = _sharedPlace.asStateFlow()

    /** Finds the place in a text shared from a map, following its link; says so when there is none. */
    fun receiveSharedPlace(text: String) {
        scope.launch {
            val place = AmapShare.resolve(text)
            if (place != null) {
                _sharedPlace.value = place
            } else {
                say(appContext.getString(R.string.error_shared_place))
            }
        }
    }

    /**
     * Keeps the place in a text shared from a map as a place of the phone's own, and says
     * so; one of the same name close by is already kept, and isn't kept twice. The app isn't
     * opened for it, so it says so in a toast, there being no page for a snackbar.
     */
    fun saveSharedPlace(text: String) {
        scope.launch {
            val shared = AmapShare.resolve(text)
            if (shared == null) {
                Toast.makeText(appContext, R.string.error_shared_place, Toast.LENGTH_LONG).show()
                return@launch
            }
            repository.loaded.first { it }
            val name = shared.name ?: coordinatesName(shared.fix)
            val places = repository.places.value
            val same = nearestPlaces(places, shared.fix, limit = 3)
                .firstOrNull { (id, metres) -> metres <= SAME_PLACE_METRES && places.getValue(id).name == name }
            if (same == null) {
                repository.addLocalPlace(newPlaceId(), Place(name, shared.fix.latitude, shared.fix.longitude, createdAt = recordTime()))
            }
            val message = if (same == null) R.string.message_place_saved else R.string.message_place_known
            Toast.makeText(appContext, appContext.getString(message, name), Toast.LENGTH_SHORT).show()
        }
    }

    private val _sharedPhotos = MutableStateFlow<List<Uri>>(emptyList())

    /** Photos and videos shared into the app from the gallery, as the app's own copies, until a new Moment takes them. */
    val sharedPhotos: StateFlow<List<Uri>> = _sharedPhotos.asStateFlow()

    /**
     * Copies photos and videos shared from another app into the app's cache, as they are, EXIF and
     * all, while the sharing still lets them be read; those that can't be read are left out.
     */
    suspend fun receiveSharedPhotos(uris: List<Uri>) {
        val copies = uris.mapNotNull { uri ->
            runCatching { Photos.copy(appContext, uri, sharedDirectory, Ulid.next()).file }.getOrNull()?.let(Uri::fromFile)
        }
        if (copies.size < uris.size) say(appContext.getString(R.string.error_shared_photos))
        _sharedPhotos.value = _sharedPhotos.value + copies
    }

    private val _sharedExport = MutableStateFlow<Uri?>(null)

    /** An iCost export shared into the app, as the app's own copy, until the ledger's import takes it. */
    val sharedExport: StateFlow<Uri?> = _sharedExport.asStateFlow()

    /** Copies iCost's export or backup shared from another app while the sharing still lets it be read; says so when it can't. */
    suspend fun receiveSharedExport(uri: Uri) {
        val copy = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(sharedDirectory, "${Ulid.next()}.icost").also { it.parentFile?.mkdirs() }
                appContext.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                Uri.fromFile(file)
            }.getOrNull()
        }
        if (copy == null) say(appContext.getString(R.string.ledger_error_import)) else _sharedExport.value = copy
    }

    /** The iCost export shared into the app, taken so it is imported once. */
    fun takeSharedExport(): Uri? = _sharedExport.getAndUpdate { null }

    /** The photos shared into the app, taken so they are used once. */
    fun takeSharedPhotos(): List<Uri> = _sharedPhotos.getAndUpdate { emptyList() }

    /** The place shared into the app, taken so it is used once. */
    fun takeSharedPlace(): SharedPlace? = _sharedPlace.getAndUpdate { null }

    /** Keeps a Moment written on the phone, and draws the widgets again with it. */
    suspend fun addLocal(moment: MomentRecord, place: Pair<String, Place>?) {
        repository.addLocal(moment, place)
        Widgets.update(appContext)
    }

    /**
     * Keeps a Moment as changed on the phone, one written here or one of the site's until the
     * changes are published, and draws the widgets again with it.
     */
    suspend fun updateLocal(moment: MomentRecord, place: Pair<String, Place>?) {
        repository.updateLocal(moment, place)
        Widgets.update(appContext)
    }

    /** Whether the app may write to the API: it has one, and the owner's token. */
    val canPublish get() = settings.connected

    /** How far publishing the changes has got, done of total, while it runs. */
    private val _publishing = MutableStateFlow<PublishProgress?>(null)

    /** How far publishing the changes has got, while it runs, so the pages say so whenever they're opened. */
    val publishing: StateFlow<PublishProgress?> = _publishing.asStateFlow()

    /**
     * Publishes every change kept on the phone, as [PublishWorker] does it, past the page
     * that asked and the app being left; the only way the app changes the site.
     */
    fun publishPending() {
        if (_publishing.value != null) return
        _publishing.value = PublishProgress(0, repository.pending.value.size)
        PublishWorker.enqueue(appContext)
    }

    /** Publishing itself, for [PublishWorker]: the changes once the phone's copy is read, [onProgress] hearing how far it has got. */
    suspend fun runPublish(onProgress: (PublishProgress) -> Unit) {
        repository.loaded.first { it }
        _publishing.value = PublishProgress(0, repository.pending.value.size)
        try {
            repository.publishPending {
                _publishing.value = it
                onProgress(it)
            }
            // The copy takes what was published from the rows, as D1 now has it.
            refreshAll()
        } finally {
            _publishing.value = null
            Widgets.update(appContext)
        }
    }

    /** Stops publishing, running or waiting for the network; the changes not yet sent stay on the phone. */
    fun cancelPublish() {
        if (_publishing.value == null) return
        PublishWorker.cancel(appContext)
        _publishing.value = null
        say(appContext.getString(R.string.message_publish_cancelled))
    }

    /**
     * Takes back every change waiting to publish: the Moments written on the phone deleted, the
     * site's records as the site has them, and each bill going public or private as it was.
     */
    fun clearChanges() {
        if (_publishing.value != null) return
        scope.launch {
            repository.clearChanges()
            for (change in repository.pending.value.outside.bills) {
                val visibility = change.undo() ?: continue
                ledger.saveTxn(change.txn.copy(visibility = visibility))
            }
            Widgets.update(appContext)
            say(appContext.getString(R.string.message_changes_cleared))
        }
    }

    /** Says publishing went through, on the page open when the app is next on screen. */
    fun reportPublished() = say(appContext.getString(R.string.message_published))

    /** Says publishing stopped, and why. */
    fun reportPublishFailed(error: Exception) {
        Log.w("Moments", "Publishing failed", error)
        say(appContext.getString(R.string.error_publish, failureReason(appContext, error)))
    }

    /**
     * Deletes a Moment, as Material 3 has a deletion undone from its snackbar rather than
     * confirmed first: it goes from sight at once, and is deleted only once the snackbar
     * saying so goes without its undo, one of the phone's then for good, one of the site's
     * when the changes are published.
     */
    fun delete(uid: String) {
        scope.launch {
            repository.setAside(uid)
            _messages.send(
                Message(
                    appContext.getString(R.string.message_moment_deleted),
                    action = appContext.getString(R.string.action_undo),
                    onAction = { scope.launch { repository.bringBack(uid) } },
                    onDismiss = {
                        scope.launch {
                            repository.delete(uid)
                            Widgets.update(appContext)
                        }
                    },
                ),
            )
        }
    }

    /**
     * Takes back a change or a deletion of one of the site's Moments, not yet published, as
     * a deletion is: gone from the changes at once, taken back only once the snackbar saying
     * so goes without its undo.
     */
    fun revert(uid: String) {
        scope.launch {
            repository.holdRevert(uid)
            _messages.send(
                Message(
                    appContext.getString(R.string.message_change_reverted),
                    action = appContext.getString(R.string.action_undo),
                    onAction = { scope.launch { repository.keepChange(uid) } },
                    onDismiss = {
                        scope.launch {
                            repository.revert(uid)
                            Widgets.update(appContext)
                        }
                    },
                ),
            )
        }
    }

    /** Takes back a change to one of the site's places, not yet published. */
    fun revertPlace(id: String) {
        scope.launch { repository.revertPlace(id) }
    }

    /** Where the API says its photos and videos are served from; kept as it was when it can't be asked or doesn't say. */
    private suspend fun readAssetOrigin() {
        runCatching { api.site() }
            .onSuccess { site -> site.assets?.let(settings.assetOrigin::set) }
            .onFailure { Log.w("Moments", "Couldn't read where the photos are served from", it) }
    }

    /** What the widgets were last drawn from, and on which day, so a refresh that brought nothing new leaves them be. */
    private var widgetsDrawn: Triple<Any, Any, LocalDate>? = null

    private suspend fun refreshAll() {
        // Without an API and its token the app is the phone's alone, and asks nothing of the network.
        if (settings.connected) {
            repository.refresh().onSuccess { revision ->
                readAssetOrigin()
                routes.refresh(revision)
            }
        }
        // The widgets show what the sync brought, or what was on the phone if it failed; the
        // same Moments and trips on the same day draw them the same, so they aren't again.
        val drawn = Triple(repository.moments.value, repository.trips.value, LocalDate.now())
        val last = widgetsDrawn
        if (last != null && last.first === drawn.first && last.second === drawn.second && last.third == drawn.third) return
        widgetsDrawn = drawn
        Widgets.update(appContext)
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** How long a trip's legs stay put before the phone routes them, so an edit in progress asks nothing. */
private const val ROUTE_SETTLE_MS = 2_000L
