package cc.hyoban.moments

import android.content.Context
import android.net.Uri
import android.widget.Toast
import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.local.AmapShare
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.SAME_PLACE_METRES
import cc.hyoban.moments.data.local.SharedPlace
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
import cc.hyoban.moments.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.OffsetDateTime

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
                Toast.makeText(appContext, R.string.error_shared_place, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Keeps the place in a text shared from a map as a place of the phone's own, and says
     * so; one of the same name close by is already kept, and isn't kept twice.
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
        if (copies.size < uris.size) Toast.makeText(appContext, R.string.error_shared_photos, Toast.LENGTH_LONG).show()
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

    /** Keeps a Moment written on the phone as changed, and draws the widgets again with it. */
    suspend fun updateLocal(moment: MomentRecord, place: Pair<String, Place>?) {
        repository.updateLocal(moment, place)
        Widgets.update(appContext)
    }

    /** Whether the app may write to the API: it has the owner's token. */
    val canPublish get() = settings.apiToken.isSet

    /**
     * Sends a Moment to the API and publishes it, a Moment of the phone's or one of the
     * site's as changed, and draws the widgets again with it.
     */
    suspend fun push(moment: MomentRecord, place: Pair<String, Place>? = null): MomentRecord =
        repository.push(moment, place).also { Widgets.update(appContext) }

    private val _publishing = MutableStateFlow<Set<String>>(emptySet())

    /** The Moments of the phone's being published, by uid, so their pages say so whenever they're opened. */
    val publishing: StateFlow<Set<String>> = _publishing.asStateFlow()

    /** Publishes a Moment of the phone's, past the page that asked, and says how it went. */
    fun publish(moment: MomentRecord) {
        if (moment.uid in _publishing.value) return
        _publishing.update { it + moment.uid }
        scope.launch {
            try {
                push(moment)
                Toast.makeText(appContext, R.string.message_published, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(appContext, appContext.getString(R.string.error_publish, error.message ?: error.toString()), Toast.LENGTH_LONG).show()
            } finally {
                _publishing.update { it - moment.uid }
            }
        }
    }

    /** Takes one of the site's Moments off it, past the page that asked, and says so if it couldn't. */
    fun deletePublished(uid: String) {
        scope.launch {
            try {
                repository.deletePublished(uid)
                Widgets.update(appContext)
            } catch (error: Exception) {
                Toast.makeText(appContext, appContext.getString(R.string.error_delete, error.message ?: error.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteLocal(uid: String) {
        scope.launch {
            repository.deleteLocal(uid)
            Widgets.update(appContext)
        }
    }

    private suspend fun refreshAll() {
        repository.refresh()
        routes.refresh()
        // The widgets show what the sync brought, or what was on the phone if it failed.
        Widgets.update(appContext)
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
