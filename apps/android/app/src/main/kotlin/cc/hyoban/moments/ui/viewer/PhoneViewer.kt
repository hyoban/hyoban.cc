package cc.hyoban.moments.ui.viewer

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.theme.MediaOverlay
import org.jetbrains.compose.resources.stringResource

// The phone's side of the shared viewer: its live photos and videos played by Media3, and what a
// photo says of itself read from its EXIF.

/** A live photo's clip on the phone: it plays once on arriving and again from the bar or held down. */
class PhoneLiveMotion : ViewerMotion {
    private val state = LiveMotionState()

    @Composable
    override fun Over(source: MotionSource, active: Boolean, modifier: Modifier) =
        LiveMotion(source.url, active, state, modifier, start = source.start, headers = source.headers, cacheKey = source.cacheKey)

    @Composable
    override fun LiveButton(modifier: Modifier) = ViewerLiveButton(state, modifier)

    override fun toggle() {
        if (state.ready) if (state.playing) state.stop() else state.play()
    }

    override val hold: (Boolean) -> Unit = { held -> if (held) state.play(sound = true) else state.stop() }
}

/** A video on the phone, with its controls in the bar under it. */
class PhoneVideoMotion : ViewerMotion {
    private val state = VideoState()

    @Composable
    override fun Over(source: MotionSource, active: Boolean, modifier: Modifier) = VideoPlayback(source.url, active, state, modifier, headers = source.headers)

    @Composable
    override fun Waiting() = VideoWaiting(state)

    @Composable
    override fun Controls(modifier: Modifier) = VideoControls(state, modifier)

    override fun toggle() {
        val player = state.player ?: return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
    }
}

/** The phone's motion for an item of [type]: its clip, its video, or none for a still. */
fun phoneViewerMotion(type: MediaType): ViewerMotion? = when (type) {
    MediaType.IMAGE -> null
    MediaType.LIVE_PHOTO -> PhoneLiveMotion()
    MediaType.VIDEO -> PhoneVideoMotion()
}

/** A live photo's motion in the bar over it: a ring while it loads, then a tap plays it again or stops it. */
@Composable
private fun ViewerLiveButton(state: LiveMotionState, modifier: Modifier = Modifier) {
    val label = stringResource(if (state.playing) Res.string.action_pause else Res.string.action_play)
    Tooltip(stringResource(Res.string.label_live), below = true, modifier = modifier) {
        IconButton(onClick = { if (state.playing) state.stop() else state.play() }, enabled = state.ready, modifier = Modifier.testTag("live")) {
            if (state.ready) {
                Icon(AppIcons.Live, contentDescription = label, tint = MediaOverlay.content)
            } else {
                Spinner(Modifier.size(20.dp), color = MediaOverlay.content)
            }
        }
    }
}
