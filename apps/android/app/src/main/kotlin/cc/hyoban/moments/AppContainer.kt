package cc.hyoban.moments

import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import cc.hyoban.moments.data.local.stamp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.trips.TripRoutes
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.viewer.MotionCache
import cc.hyoban.moments.widget.Widgets
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What the app shares: built once by the Application, handed to the screens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = Settings(context)

    // Directories of their own: `api/` the copy of what the API serves, `local/` the
    // Moments written on this phone, their photos and places, which no sync touches. `data/`
    // may still hold what the app recorded before it only showed the site's records, and
    // that is left as it was.
    val localStore = DataStore(File(context.filesDir, "local"))
    private val api = MomentsApi(baseUrl = { settings.apiUrl.value }, token = { settings.apiToken.value })
    val repository = MomentsRepository(DataStore(File(context.filesDir, "api")), api, local = localStore, writer = api)
    val mediaUrls = MediaUrls { settings.assetOrigin.value }
    /** Places by name and around a point, for a new Moment's place. */
    val placeSearch = PlaceSearch(BuildConfig.PLACE_SEARCH_URL)
    /** The trips' routes along the roads, from the API. */
    val routes = TripRoutes({ settings.apiUrl.value }, File(context.filesDir, "routes.json"))

    /** Where shared photos wait for a Moment; copied again into its own store when it is saved. */
    private val sharedDirectory = File(context.cacheDir, "shared")

    init {
        // Shared photos no draft took within a day are left behind.
        sharedDirectory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach(File::delete)
        // Routes were once kept per trip from the site's trip pages, under routes/.
        File(context.filesDir, "routes").takeIf { it.isDirectory }?.deleteRecursively()
        scope.launch {
            repository.load()
            routes.load()
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
        scope.launch {
            repository.clear()
            routes.clear()
            refreshAll()
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
                repository.addLocalPlace(newPlaceId(), Place(name, shared.fix.latitude, shared.fix.longitude, createdAt = stamp(OffsetDateTime.now())))
            }
            val message = if (same == null) R.string.message_place_saved else R.string.message_place_known
            Toast.makeText(appContext, appContext.getString(message, name), Toast.LENGTH_SHORT).show()
        }
    }

    private val _sharedPhotos = MutableStateFlow<List<Uri>>(emptyList())

    /** Photos shared into the app from the gallery, as the app's own copies, until a new Moment takes them. */
    val sharedPhotos: StateFlow<List<Uri>> = _sharedPhotos.asStateFlow()

    /**
     * Copies photos shared from another app into the app's cache, as they are, EXIF and
     * all, while the sharing still lets them be read; those that can't be read are left out.
     */
    suspend fun receiveSharedPhotos(uris: List<Uri>) {
        val copies = uris.mapNotNull { uri ->
            runCatching { Photos.copy(appContext, uri, sharedDirectory, Ulid.next()).file }.getOrNull()?.let(Uri::fromFile)
        }
        if (copies.size < uris.size) say(appContext.getString(R.string.error_shared_photos))
        _sharedPhotos.value = _sharedPhotos.value + copies
    }

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

    /** Whether the app may write to the API: it has the owner's token. */
    val canPublish get() = settings.apiToken.isSet

    /** How far publishing the changes has got, done of total, while it runs. */
    data class Progress(val done: Int, val total: Int)

    private val _publishing = MutableStateFlow<Progress?>(null)

    /** How far publishing the changes has got, while it runs, so the pages say so whenever they're opened. */
    val publishing: StateFlow<Progress?> = _publishing.asStateFlow()

    /**
     * Publishes every change kept on the phone, past the page that asked, and says how it
     * went; the only way the app changes the site.
     */
    fun publishPending() {
        if (_publishing.value != null) return
        _publishing.value = Progress(0, repository.pending.value.size)
        scope.launch {
            try {
                repository.publishPending { done, total -> _publishing.value = Progress(done, total) }
                say(appContext.getString(R.string.message_published))
            } catch (error: Exception) {
                say(appContext.getString(R.string.error_publish, failureReason(appContext, error)))
            } finally {
                _publishing.value = null
                Widgets.update(appContext)
            }
        }
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

    /** Takes back a site's place's changed kind, not yet published. */
    fun revertPlace(id: String) {
        scope.launch { repository.revertPlace(id) }
    }

    /** What the widgets were last drawn from, and on which day, so a refresh that brought nothing new leaves them be. */
    private var widgetsDrawn: Triple<Any, Any, LocalDate>? = null

    private suspend fun refreshAll() {
        repository.refresh().onSuccess { revision -> routes.refresh(revision) }
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
