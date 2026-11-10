package cc.hyoban.moments

import cc.hyoban.moments.res.*
import androidx.compose.runtime.snapshotFlow
import androidx.sqlite.SQLiteDriver
import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.copy.CopyStore
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.BillChange
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.store.OutsideChanges
import cc.hyoban.moments.data.store.PublishProgress
import cc.hyoban.moments.data.store.Shown
import cc.hyoban.moments.data.store.shown
import cc.hyoban.moments.data.trips.TripRoutes
import cc.hyoban.moments.ui.format.failureReason
import java.io.File
import java.io.Reader
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the app shares on every device: built once when it starts, handed to the screens. The
 * records, their copy and their publishing are here; what only a phone has (the ledger's and
 * the footprints' stores, the widgets, the work it leaves running) is its own container's, over this.
 *
 * [files] holds what the device keeps, [private] what must not leave it nor be backed up, and
 * [outside] what publishes beside the Moments.
 */
abstract class AppContainer(
    val settings: Settings,
    files: File,
    private: File,
    driver: SQLiteDriver,
    outside: OutsideChanges,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cities: List<cc.hyoban.moments.ui.map.FogRegion>? = null

    /** The fog's cities as the device keeps them, to read once. */
    protected abstract fun openCities(): Reader

    /** The fog's cities, read the first time they're asked for and kept: the map's fog, and where the homes set by hand are. */
    suspend fun cityRegions(): List<cc.hyoban.moments.ui.map.FogRegion> = cities ?: kotlinx.coroutines.withContext(Dispatchers.Default) {
        openCities().use { cc.hyoban.moments.ui.map.parseFogRegions(it) }
    }.also { cities = it }

    // Each its own: `copy.db` the copy of what the API serves, row for row as D1 keeps it,
    // out of the backup since a sync brings it again; `local/` the Moments written on this
    // phone, their photos and places, which no sync touches. `api/` may still hold the copy
    // as it was kept before, as JSON files, and `data/` what the app recorded before it only
    // showed the site's records; both are left as they were.
    val localStore = DataStore(File(files, "local"))
    protected val api = MomentsApi(baseUrl = { settings.apiUrl.value }, token = { settings.apiToken.value })
    val repository = MomentsRepository(CopyStore(File(private, "copy.db"), driver), api, local = localStore, writer = api, outside = outside)
    val mediaUrls = MediaUrls { settings.assetOrigin.value }
    /** The trips' routes along the roads, from the API. */
    val routes = TripRoutes({ settings.apiUrl.value }, File(files, "routes.json"), token = { settings.apiToken.value })

    /** The routes of the trips the API hasn't routed, as one made on the phone, worked out here the same way. */
    val phoneRoutes = cc.hyoban.moments.data.trips.PhoneRoutes(File(files, "phone-routes.json"))

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

    /** What the device draws from the records beside its pages (a phone's widgets), drawn again once they change. */
    protected open suspend fun recordsChanged() {}

    /** Starts publishing as the device runs work that outlasts its pages, which then calls [runPublish]. */
    protected abstract fun enqueuePublish()

    /** Stops the publishing [enqueuePublish] started. */
    protected abstract fun cancelPublishWork()

    /** Puts back each of [bills] going public or private, which the device's own ledger keeps. */
    protected open suspend fun revertOutside(bills: List<BillChange>) {}

    /**
     * Reads what the device kept and then the API, and routes the trips the API hasn't: called by
     * the device's container once it is built, so nothing here runs before it is.
     */
    protected fun start() {
        // The legs of the trips the API hasn't routed are routed here, once they settle.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            phoneLegs.debounce(ROUTE_SETTLE_MS).collectLatest { legs ->
                phoneRoutes.fill(legs.values.flatten().mapNotNull { cc.hyoban.moments.data.trips.requestOf(it) })
            }
        }
        scope.launch {
            repository.load()
            routes.load()
            phoneRoutes.load()
            refreshAll()
        }
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

    protected val _messages = Channel<Message>(Channel.BUFFERED)

    /**
     * What the app has to say while it is open, shown as snackbars in the order said; kept
     * until the app's page is up to show them, as when a share opens it.
     */
    val messages: Flow<Message> = _messages.receiveAsFlow()

    protected fun say(text: String) {
        _messages.trySend(Message(text))
    }

    /** Keeps a Moment written on the phone, and draws the widgets again with it. */
    suspend fun addLocal(moment: MomentRecord, place: Pair<String, Place>?) {
        repository.addLocal(moment, place)
        recordsChanged()
    }

    /**
     * Keeps a Moment as changed on the phone, one written here or one of the site's until the
     * changes are published, and draws the widgets again with it.
     */
    suspend fun updateLocal(moment: MomentRecord, place: Pair<String, Place>?) {
        repository.updateLocal(moment, place)
        recordsChanged()
    }

    /** Whether the app may write to the API: it has one, and the owner's token. */
    val canPublish get() = settings.connected

    /** How far publishing the changes has got, done of total, while it runs. */
    private val _publishing = MutableStateFlow<PublishProgress?>(null)

    /** How far publishing the changes has got, while it runs, so the pages say so whenever they're opened. */
    val publishing: StateFlow<PublishProgress?> = _publishing.asStateFlow()

    /**
     * Publishes every change kept on the phone, as the device's work does it, past the page
     * that asked and the app being left; the only way the app changes the site.
     */
    fun publishPending() {
        if (_publishing.value != null) return
        _publishing.value = PublishProgress(0, repository.pending.value.size)
        enqueuePublish()
    }

    /** Publishing itself, for the device's work: the changes once the phone's copy is read, [onProgress] hearing how far it has got. */
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
            recordsChanged()
        }
    }

    /** Stops publishing, running or waiting for the network; the changes not yet sent stay on the phone. */
    fun cancelPublish() {
        if (_publishing.value == null) return
        cancelPublishWork()
        _publishing.value = null
        say(textOf(Res.string.message_publish_cancelled))
    }

    /**
     * Takes back every change waiting to publish: the Moments written on the phone deleted, the
     * site's records as the site has them, and each bill going public or private as it was.
     */
    fun clearChanges() {
        if (_publishing.value != null) return
        scope.launch {
            repository.clearChanges()
            revertOutside(repository.pending.value.outside.bills)
            recordsChanged()
            say(textOf(Res.string.message_changes_cleared))
        }
    }

    /** Says publishing went through, on the page open when the app is next on screen. */
    fun reportPublished() = say(textOf(Res.string.message_published))

    /** Says publishing stopped, and why. */
    fun reportPublishFailed(error: Exception) {
        logWarning("Moments", "Publishing failed", error)
        say(textOf(Res.string.error_publish, failureReason(error)))
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
                    textOf(Res.string.message_moment_deleted),
                    action = textOf(Res.string.action_undo),
                    onAction = { scope.launch { repository.bringBack(uid) } },
                    onDismiss = {
                        scope.launch {
                            repository.delete(uid)
                            recordsChanged()
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
                    textOf(Res.string.message_change_reverted),
                    action = textOf(Res.string.action_undo),
                    onAction = { scope.launch { repository.keepChange(uid) } },
                    onDismiss = {
                        scope.launch {
                            repository.revert(uid)
                            recordsChanged()
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
            .onFailure { logWarning("Moments", "Couldn't read where the photos are served from", it) }
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
        recordsChanged()
    }
}

/** How long a trip's legs stay put before the phone routes them, so an edit in progress asks nothing. */
private const val ROUTE_SETTLE_MS = 2_000L
