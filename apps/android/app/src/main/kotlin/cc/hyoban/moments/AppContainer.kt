package cc.hyoban.moments

import android.content.Context
import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.trips.TripRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** What the app shares: built once by the Application, handed to the screens. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // A directory of its own: `data/` may still hold what the app recorded before it
    // only showed the site's records, and that is left as it was.
    val repository = MomentsRepository(DataStore(File(context.filesDir, "api")), MomentsApi(BuildConfig.API_URL))
    val mediaUrls = MediaUrls(BuildConfig.ASSET_ORIGIN)
    /** The trips' routes along the roads, from the API. */
    val routes = TripRoutes(BuildConfig.API_URL, File(context.filesDir, "routes.json"))

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

    private suspend fun refreshAll() {
        repository.refresh()
        routes.refresh()
    }
}
