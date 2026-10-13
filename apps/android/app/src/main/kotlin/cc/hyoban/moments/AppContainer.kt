package cc.hyoban.moments

import android.content.Context
import cc.hyoban.moments.data.exchange.Exchange
import cc.hyoban.moments.data.settings.Settings
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.platform.AndroidMediaFiles
import cc.hyoban.moments.platform.CurrentLocation
import cc.hyoban.moments.platform.PhotoImport
import cc.hyoban.moments.platform.PlaceNames
import cc.hyoban.moments.platform.RecentPhotos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** What the app shares: built once by the Application, handed to the screens. */
class AppContainer(context: Context) {
    private val generator = "moments-android/${BuildConfig.VERSION_NAME}"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val dataStore = DataStore(File(context.filesDir, "data"))
    val repository = MomentsRepository(dataStore, generator)
    val settings = Settings(context)
    val mediaFiles = AndroidMediaFiles(context)
    val photoImport = PhotoImport(context)
    val recentPhotos = RecentPhotos(context)
    val location = CurrentLocation(context)
    val placeNames = PlaceNames(context.getString(R.string.mapbox_access_token))
    val exchange = Exchange(repository, dataStore, mediaFiles, File(context.cacheDir, "exports"), generator)

    init {
        scope.launch { repository.load() }
    }
}
