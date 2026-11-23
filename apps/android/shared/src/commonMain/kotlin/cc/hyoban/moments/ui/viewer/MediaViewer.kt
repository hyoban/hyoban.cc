package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.plugins.nas
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Spinner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.components.rememberPredictiveBack
import cc.hyoban.moments.ui.components.LocalInPictureInPicture
import cc.hyoban.moments.ui.components.ViewerSystemBars
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.NasLink
import cc.hyoban.moments.nas.Nas
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import cc.hyoban.moments.ui.components.DrawnStills
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.format.shareText
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * A Moment's media in the viewer ([MediaViewerFrame]): pinch or double-tap to zoom, a live photo's
 * motion in the bar over it and a video's controls under it, with when and where, which opens
 * the day, and sharing. What else there is about it is on the Moment's page under the viewer.
 */
@Composable
fun MediaViewer(
    container: AppContainer,
    uid: String,
    index: Int,
    onBack: () -> Unit,
    onDay: (String) -> Unit = {},
) {
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val trip = remember(moment, trips) { tripOf(moment, trips) }
    val media = moment.media
    if (media.isEmpty()) return
    MomentMediaViewer(
        container,
        media,
        index,
        onBack,
        caption = {
            // When and where, tapped for the rest of its day.
            val words = listOf(listOfNotNull(formatDay(moment.occurredAt), formatTime(moment.occurredAt)).joinToString(" ")) + moment.places.map { places[it]?.name ?: it }
            Text(
                words.joinToString(" "),
                style = AppTheme.typography.caption,
                color = MediaOverlay.contentVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(AppTheme.shapes.medium)
                    .clickable(onClickLabel = stringResource(Res.string.label_whole_day)) { onDay(moment.dateKey) }
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = Space.s).testTag("info_day"),
            )
        },
        actions = { shown -> ShareShown(container, shown, shareText(moment, moment.places.firstOrNull()?.let { places[it]?.name }, trip?.title)) },
        nasOriginals = true,
        occurredAt = moment.occurredAt,
    )
}

/**
 * [media] in the viewer ([MediaViewerFrame]), from [index]: a Moment's, or those picked for one being
 * written, each drawn as the Moment will show it ([MomentViewed]), a live photo's clip played from the
 * bar or held down and a video with its controls. [caption] and [actions] fill the bar under it, [top]
 * the bar over it beside a live photo's button, for the one shown; [over] lies on each photo, at its
 * size, zooming and panning with it. With [nasOriginals], a photo linked to the NAS is looked at as
 * the NAS keeps it, live if the NAS's is.
 */
@Composable
fun MomentMediaViewer(
    container: AppContainer,
    media: List<MomentMedia>,
    index: Int,
    onBack: () -> Unit,
    caption: @Composable RowScope.(MomentMedia) -> Unit,
    actions: @Composable RowScope.(MomentMedia) -> Unit = {},
    top: @Composable RowScope.(MomentMedia) -> Unit = {},
    over: @Composable (MomentMedia) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Where each one's motion is read: what is published or saved, or a motion photo picked and not yet saved. */
    motionSource: (MomentMedia) -> MotionSource? = { container.mediaUrls.motion(it)?.let(::MotionSource) },
    nasOriginals: Boolean = false,
    /** Its Moment's time, for a photo that doesn't say its own. */
    occurredAt: String? = null,
) {
    if (media.isEmpty()) return
    val pager = rememberPagerState(initialPage = index.coerceIn(0, media.lastIndex)) { media.size }
    // The NAS a photo linked to it is looked at from, once it is set up.
    val nas = container.nas.takeIf { nasOriginals && it.configured }
    val motion by rememberUpdatedState(motionSource)
    val items = remember(media, nas) { media.map { MomentViewed(it, container, nas, { item -> motion(item) }, occurredAt) } }
    MediaViewerFrame(
        container,
        items,
        pager,
        onClose = onBack,
        modifier = modifier,
        caption = { caption((it as MomentViewed).media) },
        actions = { actions((it as MomentViewed).media) },
        top = { top((it as MomentViewed).media) },
        over = { over((it as MomentViewed).media) },
    )
}

/** The one shown with its Moment's words, fetched first when it's the site's, the spinner in its place meanwhile. */
@Composable
private fun ShareShown(container: AppContainer, shown: MomentMedia, text: String) {
    val device = LocalDevice.current
    val show = rememberShowMessage()
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Tooltip(stringResource(Res.string.action_share)) {
        IconButton(
            onClick = {
                sharing = true
                scope.launch {
                    try {
                        device.shareMedia(shown, container.mediaUrls, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        show(textOf(Res.string.error_share))
                    } finally {
                        sharing = false
                    }
                }
            },
            enabled = !sharing,
            modifier = Modifier.testTag("share"),
        ) {
            Crossfade(sharing, animationSpec = AppTheme.motionScheme.fastEffectsSpec(), label = "sharing") { busy ->
                if (busy) {
                    Spinner(Modifier.size(24.dp), color = MediaOverlay.content)
                } else {
                    Icon(AppIcons.Share, contentDescription = stringResource(Res.string.action_share), tint = MediaOverlay.content)
                }
            }
        }
    }
}
