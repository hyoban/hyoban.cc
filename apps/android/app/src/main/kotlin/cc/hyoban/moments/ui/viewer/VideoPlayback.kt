package cc.hyoban.moments.ui.viewer

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cc.hyoban.moments.R
import kotlinx.coroutines.delay

// A video in the viewer, played as a phone's gallery plays it: its poster shows at once
// while the video loads behind it, then it plays over the poster, silent until asked
// for sound, with a ring while it waits for more. Its controls come and go with the
// viewer's. Videos are kept on disk with the live photos' clips, so one seen before
// starts at once; the sound, once turned on, stays on for the next.

/** Whether videos play with their sound; on for the rest of the viewer's videos once turned on. */
private object VideoSound {
    var on by mutableStateOf(false)
}

/** What a video is doing, for its controls and the poster under it. */
@Stable
class VideoState {
    internal var player by mutableStateOf<ExoPlayer?>(null)

    /** A first frame is drawn, so the video can cover its poster. */
    var shown by mutableStateOf(false)
        internal set
    var playing by mutableStateOf(false)
        internal set

    /** Waiting for more of the video, after asking it to play. */
    var waiting by mutableStateOf(true)
        internal set
    var ended by mutableStateOf(false)
        internal set
    var position by mutableLongStateOf(0L)
        internal set
    var duration by mutableLongStateOf(0L)
        internal set

    val sound: Boolean get() = VideoSound.on

    fun toggle() {
        val player = player ?: return
        if (ended) {
            player.seekTo(0)
            ended = false
        }
        player.playWhenReady = !player.playWhenReady
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
        this.position = position
        ended = false
    }

    fun toggleSound() {
        VideoSound.on = !VideoSound.on
        player?.volume = if (VideoSound.on) 1f else 0f
    }
}

@Composable
fun rememberVideoState(key: Any): VideoState = remember(key) { VideoState() }

/**
 * The video at [url] over its poster while its page is [active]: it loads on arriving
 * at the page and plays when it can; leaving the page lets it go.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayback(url: String, active: Boolean, state: VideoState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    DisposableEffect(url, active) {
        if (!active) return@DisposableEffect onDispose { }
        val source = CacheDataSource.Factory()
            .setCache(MotionCache.of(context))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(source))
            .build()
            .apply {
                volume = if (VideoSound.on) 1f else 0f
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                state.shown = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state.waiting = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) state.duration = player.duration.coerceAtLeast(0L)
                if (playbackState == Player.STATE_ENDED) {
                    state.ended = true
                    player.playWhenReady = false
                }
            }
        }
        player.addListener(listener)
        state.player = player
        onDispose {
            player.removeListener(listener)
            player.release()
            state.player = null
            state.shown = false
            state.playing = false
            state.waiting = true
            state.ended = false
            state.position = 0L
        }
    }

    // Where it is, a few times a second while it plays, for the time and the bar.
    LaunchedEffect(state.player, state.playing) {
        val player = state.player ?: return@LaunchedEffect
        while (state.playing) {
            state.position = player.currentPosition
            delay(250)
        }
        state.position = player.currentPosition
    }

    val alpha by animateFloatAsState(if (state.shown) 1f else 0f, tween(180), label = "video")
    if (!active) return
    AndroidView(
        factory = { TextureView(it) },
        update = { view -> state.player?.setVideoTextureView(view) },
        onRelease = { view -> state.player?.clearVideoTextureView(view) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/** A ring in the middle while the video waits for more of itself. */
@Composable
fun VideoWaiting(state: VideoState, modifier: Modifier = Modifier) {
    AnimatedVisibility(state.waiting && !state.ended, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}

/** Play or pause, where it is in the video, and its sound, along the bottom while the viewer's chrome shows. */
@Composable
fun VideoControls(state: VideoState, visible: Boolean, modifier: Modifier = Modifier) {
    // The bar follows a drag on its own rather than the video, until let go.
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val duration = state.duration.coerceAtLeast(1L)

    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = state::toggle) {
                Icon(
                    when {
                        state.ended -> Icons.Rounded.Replay
                        state.playing -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                    tint = Color.White,
                )
            }
            Text(
                formatClock(if (dragging) (dragged * duration).toLong() else state.position),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Slider(
                value = if (dragging) dragged else state.position.toFloat() / duration,
                onValueChange = {
                    dragging = true
                    dragged = it
                },
                onValueChangeFinished = {
                    state.seekTo((dragged * duration).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(formatClock(state.duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            IconButton(onClick = state::toggleSound) {
                Icon(
                    if (state.sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = stringResource(if (state.sound) R.string.action_mute else R.string.action_unmute),
                    tint = Color.White,
                )
            }
        }
    }
}

/** `0:42`, or `1:02:03` past an hour. */
private fun formatClock(millis: Long): String {
    val seconds = millis / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
