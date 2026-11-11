package cc.hyoban.moments.desktop

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.FilePrefs
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.store.Outside
import cc.hyoban.moments.data.store.OutsideChanges
import java.io.File
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Mac's container: what every device shares, kept under [root], the app's folder in the
 * user's Application Support. Its ledger and footprints are the phone's, read from the phone's
 * latest WebDAV backup and only shown, so nothing publishes beside the Moments.
 */
class DesktopContainer(private val root: File) : AppContainer(
    settings = Settings(FilePrefs(File(root, "settings.json")), File(root, "private").apply { mkdirs() }),
    files = root,
    private = File(root, "private"),
    driver = BundledSQLiteDriver(),
    outside = NothingOutside,
) {
    /** The phone's latest backup, read from its WebDAV directory: the ledger and footprints shown here. */
    val phoneBackup by lazy {
        cc.hyoban.moments.backup.PhoneBackup(
            FilePrefs(File(root, "phone-backup.json")),
            File(root, "private"),
            File(root, "private/phone-backup"),
            BundledSQLiteDriver(),
        )
    }

    // Read lazily: the shared container asks for them while it is being made.
    override val ledger: cc.hyoban.moments.ledger.LedgerSource get() = phoneBackup.ledger
    override val placeSearch = cc.hyoban.moments.data.local.PlaceSearch(
        cc.hyoban.moments.data.local.PHOTON_URL,
        tencentKey = { settings.tencentMapKey.value },
        amapKey = { settings.amapKey.value },
        tencentFirst = { settings.chinaPlaces.value == cc.hyoban.moments.CHINA_PLACES_TENCENT },
    )
    override val footprints: cc.hyoban.moments.footprints.Footprints get() = phoneBackup.footprints

    private var publish: Job? = null

    init {
        start()
        // The backup read again each time the app opens, as the phone backs up once a day.
        scope.launch { phoneBackup.refresh() }
    }

    override fun openCities(): Reader =
        checkNotNull(DesktopContainer::class.java.getResourceAsStream("/cities.json")) { "The cities are missing from the app" }.bufferedReader()

    // A computer's app stays open while it publishes, so the work is a coroutine of the app's own.
    override fun enqueuePublish() {
        publish = scope.launch(Dispatchers.IO) {
            try {
                runPublish {}
                reportPublished()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                reportPublishFailed(error)
            }
        }
    }

    override fun cancelPublishWork() {
        publish?.cancel()
    }

    companion object {
        /** The app's version, as its packaged jar says; none in a trial run. */
        fun version(): String? = DesktopContainer::class.java.`package`?.implementationVersion

        /** `~/Library/Application Support/Here`, or `HERE_HOME` when set, as a test's own folder. */
        fun home(): File = System.getenv("HERE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "Library/Application Support/Here")
    }
}

private object NothingOutside : OutsideChanges {
    override fun pending() = Outside()
    override suspend fun published(sent: Outside) {}
}
