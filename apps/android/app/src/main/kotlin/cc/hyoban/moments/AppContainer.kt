package cc.hyoban.moments

import android.content.Context
import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.trips.TripRoutes
import cc.hyoban.moments.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** What the app shares: built once by the Application, handed to the screens. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = Settings(context)

    // A directory of its own: `data/` may still hold what the app recorded before it
    // only showed the site's records, and that is left as it was.
    val repository = MomentsRepository(DataStore(File(context.filesDir, "api")), MomentsApi(baseUrl = { settings.apiUrl.value }))
    val mediaUrls = MediaUrls { settings.assetOrigin.value }
    /** The trips' routes along the roads, from the API. */
    val routes = TripRoutes({ settings.apiUrl.value }, File(context.filesDir, "routes.json"))

    init {
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

    private suspend fun refreshAll() {
        repository.refresh()
        routes.refresh()
        // The widgets show what the sync brought, or what was on the phone if it failed.
        Widgets.update(appContext)
    }
}
