package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Scrubber
import cc.hyoban.moments.ui.design.Spinner
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.state.rememberMuteButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space

// A video in the viewer, played as a phone's gallery plays it: its poster shows at once
// while the video loads behind it, then it plays over the poster, silent until asked
// for sound, with a ring while it waits for more. Its controls come and go with the
// viewer's, and follow the player through Media3's own state holders. Videos are kept
// on disk with the live photos' clips; the sound, once turned on, stays on for the next.
// With its sound on it takes the audio from other apps, and stops when the headphones
// come out; silent, it leaves their music alone. The screen stays on while it plays; it
// pauses when the app is left, unless it goes on in a window of its own.

/** Whether videos play with their sound; on for the rest of the viewer's videos once turned on. */
private object VideoSound {
    var on by mutableStateOf(false)
}

/** The video's player while its page is shown, and whether it waits for more of itself. */
@Stable
class VideoState {
    var player by mutableStateOf<Player?>(null)
        internal set

    var waiting by mutableStateOf(true)
        internal set

    var playing by mutableStateOf(false)
        internal set

    /** The video's size in pixels once known, for the shape of its picture-in-picture window. */
    var size by mutableStateOf(IntSize.Zero)
        internal set
}

@Composable
fun rememberVideoState(key: Any): VideoState = remember(key) { VideoState() }

/**
 * The video at [url] over its poster while its page is [active]: it loads on arriving
 * at the page and plays when it can; leaving the page lets it go.
 */
@Composable
fun VideoPlayback(url: String, active: Boolean, state: VideoState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = rememberCachedPlayer(url, active) {
        volume = if (VideoSound.on) 1f else 0f
        setAudioAttributes(VIDEO_AUDIO, VideoSound.on)
        playWhenReady = true
    }

    DisposableEffect(player) {
        state.player = player
        state.waiting = true
        state.playing = player?.isPlaying == true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.waiting = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) state.size = IntSize(videoSize.width, videoSize.height)
            }

            // Only a video with its sound takes the audio from other apps.
            override fun onVolumeChanged(volume: Float) {
                player?.setAudioAttributes(VIDEO_AUDIO, volume > 0f)
            }
        }
        player?.addListener(listener)
        // The system's play and pause, in the picture-in-picture window, come from its session.
        val session = player?.let { MediaSession.Builder(context, it).setId("video").build() }
        onDispose {
            session?.release()
            player?.removeListener(listener)
            state.player = null
            state.playing = false
        }
    }

    // Left for another app, it pauses; in a window of its own the app is only paused, not stopped.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player?.pause() }
    PictureInPicture.Offer(state.playing, state.size.width, state.size.height)

    if (player != null) MediaSurface(player, if (state.playing) modifier.keepScreenOn() else modifier)
}

/** A video's sound, as a film's. */
private val VIDEO_AUDIO = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

/** A loading indicator in the middle while the video waits for more of itself. */
@Composable
fun VideoWaiting(state: VideoState, modifier: Modifier = Modifier) {
    val motion = AppTheme.motionScheme
    AnimatedVisibility(state.waiting && state.player != null, modifier = modifier, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.defaultEffectsSpec())) {
        Box(Modifier.size(56.dp).background(MediaOverlay.control, CircleShape), contentAlignment = Alignment.Center) {
            Spinner(Modifier.size(44.dp), color = MediaOverlay.content)
        }
    }
}

/** Play or pause, where it is in the video, and its sound, a row of the viewer's bar along the bottom. */
@OptIn(UnstableApi::class)
@Composable
fun VideoControls(state: VideoState, modifier: Modifier = Modifier) {
    val player = state.player
    val playPause = rememberPlayPauseButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 250)
    // The bar moves a pixel at a time, as often as the video covers one, rather than in quarter-second steps.
    val bar = rememberProgressStateWithTickCount(player, totalTickCount = 1)
    val mute = rememberMuteButtonState(player)
    // Sound turned on for one video stays on for the next.
    LaunchedEffect(player, mute.showMuted) { if (player != null) VideoSound.on = !mute.showMuted }
    // The bar follows a drag on its own rather than the video, until let go.
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val duration = progress.durationMs.coerceAtLeast(1L)
    val ended = playPause.showPlay && progress.durationMs > 0 && progress.currentPositionMs >= progress.durationMs - 100

    val motion = AppTheme.motionScheme
    AnimatedVisibility(player != null, modifier = modifier, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Tooltip(stringResource(if (playPause.showPlay) R.string.action_play else R.string.action_pause)) {
                IconButton(onClick = playPause::onClick, enabled = playPause.isEnabled) {
                    Icon(
                        when {
                            ended -> AppIcons.Replay
                            playPause.showPlay -> AppIcons.Play
                            else -> AppIcons.Pause
                        },
                        contentDescription = stringResource(if (playPause.showPlay) R.string.action_play else R.string.action_pause),
                        // Faded while the player can't take it yet, as a disabled button is.
                        tint = if (playPause.isEnabled) MediaOverlay.content else MediaOverlay.contentDisabled,
                    )
                }
            }
            Text(
                formatClock(if (dragging) (dragged * duration).toLong() else progress.currentPositionMs),
                style = AppTheme.typography.caption,
                color = MediaOverlay.content,
            )
            Scrubber(
                value = if (dragging) dragged else bar.currentPositionProgress.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragged = it
                },
                onValueChangeFinished = {
                    player?.seekTo((dragged * duration).toLong())
                    dragging = false
                },
                color = MediaOverlay.content,
                trackColor = MediaOverlay.contentDisabled,
                modifier = Modifier.weight(1f).onSizeChanged { if (it.width > 0) bar.updateTotalTickCount(it.width) },
            )
            Text(formatClock(progress.durationMs.coerceAtLeast(0L)), style = AppTheme.typography.caption, color = MediaOverlay.contentVariant)
            Tooltip(stringResource(if (mute.showMuted) R.string.action_unmute else R.string.action_mute)) {
                IconButton(onClick = mute::onClick, enabled = mute.isEnabled) {
                    Icon(
                        if (mute.showMuted) AppIcons.VolumeOff else AppIcons.Volume,
                        contentDescription = stringResource(if (mute.showMuted) R.string.action_unmute else R.string.action_mute),
                        tint = if (mute.isEnabled) MediaOverlay.content else MediaOverlay.contentDisabled,
                    )
                }
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
