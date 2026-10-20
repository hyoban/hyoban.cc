package cc.hyoban.moments.ui.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import cc.hyoban.moments.data.Http
import java.io.File

// What the viewer's live photos and videos share: a disk cache, so a clip or a video seen
// before starts at once; a player made for a page while it's the one shown and let go
// after; and a surface that stays out of sight until the player has a frame to show.

/** The clips and videos on disk, 300 MB of them; the least lately seen go first. */
@OptIn(UnstableApi::class)
internal object MotionCache {
    @Volatile private var cache: SimpleCache? = null

    fun of(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "motion"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}

/**
 * A player of [url] through the disk cache while [active], set up by [configure] and
 * prepared, or null while not; it is released when the page goes or stops being shown.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberCachedPlayer(url: String, active: Boolean, configure: ExoPlayer.() -> Unit = {}): ExoPlayer? {
    val context = LocalContext.current
    val setUp by rememberUpdatedState(configure)
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(url, active) {
        if (!active) return@DisposableEffect onDispose { }
        val source = CacheDataSource.Factory()
            .setCache(MotionCache.of(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Http.client))
        val made = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(source))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(url))
                setUp()
                prepare()
            }
        player = made
        onDispose {
            player = null
            made.release()
        }
    }

    return player
}

/**
 * Where [player] draws, over whatever is under it: hidden until its first frame, and
 * while [shown] is false, fading in and out so neither cuts to the other.
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaSurface(player: Player?, modifier: Modifier = Modifier, shown: Boolean = true) {
    val presentation = rememberPresentationState(player)
    val visible = shown && !presentation.coverSurface
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 180 else 320), label = "surface")
    // A texture rather than a surface view, so it fades, clips and zooms with the still.
    PlayerSurface(player, modifier.graphicsLayer { this.alpha = alpha }, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
}
