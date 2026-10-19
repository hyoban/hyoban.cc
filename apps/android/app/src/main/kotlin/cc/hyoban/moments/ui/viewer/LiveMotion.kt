package cc.hyoban.moments.ui.viewer

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

// A live photo's motion, played the way a phone's gallery plays it: the still shows at
// once while the clip loads behind it; once loaded the clip plays through once, silent,
// over the still and fades back to it; held down, it plays with its sound for as long as
// the finger stays. Clips are kept on disk, so one seen before starts at once.

/** The clips on disk, a few hundred of them; the least lately seen go first. */
@OptIn(UnstableApi::class)
private object MotionCache {
    @Volatile private var cache: SimpleCache? = null

    fun of(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "motion"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}

/** What a live photo's motion is doing, for the badge and the still under it. */
@Stable
class LiveMotionState {
    /** The clip is loaded and can play at once. */
    var ready by mutableStateOf(false)
        internal set

    /** The clip is on screen over the still. */
    var playing by mutableStateOf(false)
        internal set

    internal var player by mutableStateOf<ExoPlayer?>(null)
    internal var played = false

    /** Plays the clip through once from its start, silent unless [sound]. */
    fun play(sound: Boolean = false) {
        val player = player ?: return
        player.volume = if (sound) 1f else 0f
        player.seekTo(0)
        player.playWhenReady = true
        playing = true
    }

    /** Back to the still. */
    fun stop() {
        player?.playWhenReady = false
        playing = false
    }
}

/**
 * The motion of a live photo at [url], over its still, while its page is [active]: it
 * loads on arriving at the page and plays once when loaded; leaving the page lets it go.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveMotion(url: String, active: Boolean, state: LiveMotionState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    DisposableEffect(url, active) {
        if (!active) {
            state.ready = false
            state.playing = false
            state.played = false
            return@DisposableEffect onDispose { }
        }
        val source = CacheDataSource.Factory()
            .setCache(MotionCache.of(context))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(source))
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        state.ready = true
                        // Once on arriving, as a gallery does, then only when asked.
                        if (!state.played) {
                            state.played = true
                            state.play()
                        }
                    }
                    Player.STATE_ENDED -> state.stop()
                    else -> {}
                }
            }
        }
        player.addListener(listener)
        state.player = player
        onDispose {
            player.removeListener(listener)
            player.release()
            state.player = null
            state.ready = false
            state.playing = false
        }
    }

    // The clip fades in over the still and back out, so neither cuts to the other.
    val alpha by animateFloatAsState(if (state.playing) 1f else 0f, tween(if (state.playing) 180 else 320), label = "motion")
    if (!active) return
    AndroidView(
        factory = { TextureView(it) },
        update = { view -> state.player?.setVideoTextureView(view) },
        onRelease = { view -> state.player?.clearVideoTextureView(view) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/** A remembered [LiveMotionState] for one live photo. */
@Composable
fun rememberLiveMotionState(key: Any): LiveMotionState = remember(key) { LiveMotionState() }
