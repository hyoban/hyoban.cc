package cc.hyoban.moments.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player

// A live photo's motion, played the way a phone's gallery plays it: the still shows at
// once while the clip loads behind it; once loaded the clip plays through once, silent,
// over the still and fades back to it; held down, it plays with its sound for as long as
// the finger stays. Clips are kept on disk, so one seen before starts at once.

/** What a live photo's motion is doing, for the badge and the still under it. */
@Stable
class LiveMotionState {
    /** The clip is loaded and can play at once. */
    var ready by mutableStateOf(false)
        internal set

    /** The clip is on screen over the still. */
    var playing by mutableStateOf(false)
        internal set

    internal var player: Player? = null
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
@Composable
fun LiveMotion(url: String, active: Boolean, state: LiveMotionState, modifier: Modifier = Modifier) {
    val player = rememberCachedPlayer(url, active) { volume = 0f }

    DisposableEffect(player) {
        if (player == null) {
            state.ready = false
            state.playing = false
            if (!active) state.played = false
            return@DisposableEffect onDispose { }
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
            state.player = null
        }
    }

    if (player != null) MediaSurface(player, modifier, shown = state.playing)
}

/** A remembered [LiveMotionState] for one live photo. */
@Composable
fun rememberLiveMotionState(key: Any): LiveMotionState = remember(key) { LiveMotionState() }
