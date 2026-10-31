package cc.hyoban.moments

import android.app.Application
import android.content.res.Configuration
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.widget.Widgets
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.launch

class MomentsApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        cc.hyoban.moments.sidekey.SideKeys.initialize(this)
        lastLocales = resources.configuration.locales.toLanguageTags()
    }

    /** The widgets drawn again in the system's language once it changes. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val locales = newConfig.locales.toLanguageTags()
        if (locales != lastLocales) {
            lastLocales = locales
            container.scope.launch { Widgets.update(this@MomentsApplication) }
        }
    }

    private var lastLocales: String? = null

    /**
     * Coil fetches the photos through the app's one HTTP client, beside the records and the
     * videos, and draws a frame of a video picked for a Moment, before it is saved with a poster.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                add(VideoFrameDecoder.Factory())
            }
            .build()
}
