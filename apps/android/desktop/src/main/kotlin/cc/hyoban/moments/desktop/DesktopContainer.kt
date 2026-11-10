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
 * user's Application Support. It has no ledger or footprints of its own, so nothing publishes
 * beside the Moments.
 */
class DesktopContainer(root: File) : AppContainer(
    settings = Settings(FilePrefs(File(root, "settings.json")), File(root, "private").apply { mkdirs() }),
    files = root,
    private = File(root, "private"),
    driver = BundledSQLiteDriver(),
    outside = NothingOutside,
) {
    private var publish: Job? = null

    init {
        start()
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
        /** `~/Library/Application Support/Here`, or `HERE_HOME` when set, as a test's own folder. */
        fun home(): File = System.getenv("HERE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "Library/Application Support/Here")
    }
}

private object NothingOutside : OutsideChanges {
    override fun pending() = Outside()
    override suspend fun published(sent: Outside) {}
}
