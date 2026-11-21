package cc.hyoban.moments.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import org.jetbrains.compose.resources.stringResource

/**
 * The viewer's one zoomable photo: [still] fitted to the screen, zoomed with two fingers or a double
 * tap up to [MAX_ZOOM] and panned once zoomed, stopping at the photo's edges, with telephoto's flings.
 * One finger at full size is left to the pager and the swipes, so they still turn and close. [onZoom]
 * says whether it is zoomed in, for the pager to stop turning and the source to read it sharper;
 * [onHold] whether it is held down at full size, until the finger lifts; [onTap] a tap, for the
 * viewer to hide its bars. [overlay] lies on the photo at its size and zooms and pans with it: a live
 * clip, a video, the patches being drawn. Its touches go on to the photo under it.
 */
@Composable
expect fun ZoomableMedia(
    still: ViewedStill,
    contentDescription: String?,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
)

/** As far as a photo zooms, and how far a double tap takes it. */
const val MAX_ZOOM = 6f
const val DOUBLE_TAP_ZOOM = 2.5f

/** A still that couldn't be read: said in the middle, on the black, with the way to try again. */
@Composable
internal fun StillFailed(retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(Res.string.media_not_loaded), style = AppTheme.typography.body, color = MediaOverlay.content)
        TextButton(onClick = retry, modifier = Modifier.testTag("media_retry")) {
            Text(stringResource(Res.string.action_retry), color = MediaOverlay.content)
        }
    }
}
