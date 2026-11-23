package cc.hyoban.moments

import cc.hyoban.moments.res.*
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import cc.hyoban.moments.data.local.Photos
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.Ulid
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.store.BillChange
import cc.hyoban.moments.data.store.PhoneOutside
import cc.hyoban.moments.data.store.readoptions
import cc.hyoban.moments.data.store.undo
import cc.hyoban.moments.plugins.footprints
import cc.hyoban.moments.plugins.ledger
import cc.hyoban.moments.publish.PublishWorker
import cc.hyoban.moments.ui.viewer.MotionCache
import cc.hyoban.moments.widget.Widgets
import java.io.File
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.nativeffi.loadNativeLibrary

/**
 * The phone's container: what every device shares, and over it what only the phone has, the
 * widgets and the work it leaves running; its ledger and footprints are its plugins' services
 * ([ledger], [footprints]). Built once by the Application.
 */
class PhoneContainer private constructor(
    context: Context,
    outside: PhoneOutside,
) : AppContainer(
    settings = Settings(SharedPrefs(context.getSharedPreferences("settings", Context.MODE_PRIVATE)), context.noBackupFilesDir),
    files = context.filesDir,
    private = context.noBackupFilesDir,
    driver = androidx.sqlite.driver.AndroidSQLiteDriver(),
    outside = outside,
) {
    constructor(context: Context) : this(context, PhoneOutside())

    /** The application's context, which the phone's plugins make their services with. */
    val appContext: Context = context.applicationContext

    init {
        // The public bills publish beside the Moments from the ledger the ledger's plugin gives.
        outside.ledger = { ledger }
    }

    override fun openCities(): Reader = appContext.assets.open(cc.hyoban.moments.ui.map.FOG_REGIONS_ASSET).bufferedReader()

    val footprintDiagnostics = cc.hyoban.moments.footprints.FootprintDiagnostics(
        File(context.noBackupFilesDir, "footprint-diagnostics"), scope,
    )
    val connectionRules = cc.hyoban.moments.footprints.ConnectionRulesStore(context)
    /** The databases backed up to the owner's WebDAV directory, when one is set. */
    val backup = cc.hyoban.moments.backup.Backup(context)

    /** Where the gallery's photos were taken, read once each for 地图's photos and kept on the phone. */
    val galleryLocations by lazy { cc.hyoban.moments.ui.picker.GalleryLocations(context) }

    /** The app's own updates, from its GitHub releases. */
    val updates = cc.hyoban.moments.update.Updates(context)

    override fun openSource(source: String): java.io.InputStream =
        appContext.contentResolver.openInputStream(android.net.Uri.parse(source)) ?: error("Can't read $source")

    /** Places by name and around a point, for a new Moment's place. */
    override val placeSearch = PlaceSearch(BuildConfig.PLACE_SEARCH_URL, tencentKey = { settings.tencentMapKey.value },
        amapKey = { settings.amapKey.value },
        tencentFirst = { settings.chinaPlaces.value == CHINA_PLACES_TENCENT },
        townAt = { cc.hyoban.moments.data.local.placeName(context.applicationContext, it) },
    )
    /** Where each bill was paid, by the footprints. */
    val billPlaces = cc.hyoban.moments.footprints.BillPlaces(
        footprints, placeSearch, repository.places, repository.checkins,
        checkinsOn = { settings.isOn(Module.CHECKIN) },
    ) { settings.isOn(Module.FOOTPRINTS) }
    /** Where shared photos wait for a Moment; copied again into its own store when it is saved. */
    private val sharedDirectory = File(context.cacheDir, "shared")

    init {
        // Shared photos no draft took within a day are left behind.
        sharedDirectory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach(File::delete)
        // Routes were once kept per trip from the site's trip pages, under routes/.
        File(context.filesDir, "routes").takeIf { it.isDirectory }?.deleteRecursively()
        scope.launch { ledger.load() }
        // Bills an import in place of the ledger once gave new ids take the site's back, so what
        // was published isn't published again.
        scope.launch {
            ledger.loaded.first { it }
            repository.loaded.first { it }
            val found = ledger.ledger.value.readoptions(repository.siteBills())
            if (found.isNotEmpty()) ledger.readopt(found)
        }
        // The widgets show only what the public sees in demo mode, so they are drawn again as it turns.
        scope.launch { snapshotFlow { settings.demo }.drop(1).collect { Widgets.update(appContext) } }
        // A bill or a span going public, or private again, is a change to publish too.
        scope.launch {
            kotlinx.coroutines.flow.combine(ledger.ledger, footprints.revision) { _, _ -> }.collect {
                withContext(Dispatchers.IO) { repository.noteOutside() }
            }
        }
        start()
        // The videos' disk cache opens its index and database off the main thread, before a
        // video is first opened, rather than in the frame that opens it.
        scope.launch(Dispatchers.IO) { MotionCache.of(appContext) }
        // Load the FFI JNI entry point and its native core off main without opening a runtime.
        // The old MapView used libmaplibre.so; the Compose runtime packages different library
        // names, so System.loadLibrary("maplibre") silently did no work after the migration.
        scope.launch(Dispatchers.IO) {
            runCatching { loadNativeLibrary() }
                .onFailure { android.util.Log.w("MapLibre", "Could not preload the native library", it) }
        }
    }

    override suspend fun billPointAt(time: Long) = billPlaces.pointAt(time)

    override suspend fun recordsChanged() = Widgets.update(appContext)

    override fun enqueuePublish() = PublishWorker.enqueue(appContext)

    override fun cancelPublishWork() = PublishWorker.cancel(appContext)

    override suspend fun revertOutside(bills: List<BillChange>) {
        for (change in bills) {
            val visibility = change.undo() ?: continue
            ledger.saveTxn(change.txn.copy(visibility = visibility))
        }
    }

    /**
     * Offers [place], a check-in's just kept from a stay, to the bills paid in that stay from
     * [start] to [end] that have no place of their own nor a Moment's; nothing is said when
     * there are none or the ledger is off.
     */
    override fun offerPlaceToBills(place: String, start: Long, end: Long) {
        if (!settings.isOn(Module.LEDGER)) return
        scope.launch {
            val bills = ledger.ledger.value.txns.filter {
                it.kind != cc.hyoban.moments.ledger.TxnKind.TRANSFER && it.place == null && it.moment == null && it.time in start..end
            }
            if (bills.isEmpty()) return@launch
            _messages.send(
                Message(
                    pluralTextOf(Res.plurals.message_checkin_bills, bills.size, bills.size),
                    action = textOf(Res.string.ledger_use_place),
                    onAction = { scope.launch { ledger.saveTxns(bills.map { it.copy(place = place) }) } },
                ),
            )
        }
    }

    /**
     * Copies photos and videos shared from another app into the app's cache, as they are, EXIF and
     * all, while the sharing still lets them be read; those that can't be read are left out.
     */
    suspend fun receiveSharedPhotos(uris: List<Uri>) {
        val copies = uris.mapNotNull { uri ->
            runCatching { Photos.copy(appContext, uri, sharedDirectory, Ulid.next()).file }.getOrNull()?.let(Uri::fromFile)
        }
        if (copies.size < uris.size) say(textOf(Res.string.error_shared_photos))
        offerPhotos(copies)
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
        if (copy == null) say(textOf(Res.string.ledger_error_import)) else _sharedExport.value = copy
    }

    /** The iCost export shared into the app, taken so it is imported once. */
    fun takeSharedExport(): Uri? = _sharedExport.getAndUpdate { null }

}

private const val DAY_MS = 24 * 60 * 60 * 1000L
