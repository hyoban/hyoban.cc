package cc.hyoban.moments.ui.viewer

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
import androidx.compose.runtime.rememberUpdatedState
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
import cc.hyoban.moments.ui.components.DrawnStills
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
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
 * A Moment's media in the viewer ([ViewerFrame]): pinch or double-tap to zoom, a live photo's
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
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
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
        actions = { shown -> ShareShown(container, shown, shareText(moment, moment.places.firstOrNull()?.let { places[it]?.name })) },
    )
}

/**
 * [media] in the viewer ([ViewerFrame]), from [index]: a Moment's, or those picked for one being
 * written, each drawn as the Moment will show it, zoomed in for a wider file, a live photo's clip
 * played from the bar or held down and a video with its controls. [caption] and [actions] fill the
 * bar under it, [top] the bar over it beside a live photo's button, for the one shown; [over]
 * lies on each photo, at its size, zooming and panning with it.
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
) {
    if (media.isEmpty()) return
    val pager = rememberPagerState(initialPage = index.coerceIn(0, media.lastIndex)) { media.size }
    // Each one's motion and video, held here so the bars over the pages work the one shown.
    val device = LocalDevice.current
    val motions = remember(media.map { it.uid }) { media.associate { it.uid to device.viewerMotion(it) } }
    val shown = media[pager.currentPage.coerceIn(0, media.lastIndex)]
    // Space pressed, for the page shown to play or pause what it holds.
    val playKeys = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    ViewerFrame(
        pager = pager,
        key = { media[it].uid },
        onClose = onBack,
        modifier = modifier,
        thumb = { MediaStill(media[it], container.mediaUrls, Modifier.fillMaxSize()) },
        thumbLabel = { media[it].alt.ifEmpty { null } },
        info = if (device.hasPhotoInfo) {
            { panel -> device.PhotoInfo(container, shown, panel) }
        } else {
            null
        },
        onSpace = { playKeys.tryEmit(Unit) },
        top = { at ->
            Row(Modifier.align(at), verticalAlignment = Alignment.CenterVertically) {
                top(shown)
                if (shown.type == MediaType.LIVE_PHOTO && container.mediaUrls.motion(shown) != null) {
                    motions[shown.uid]?.LiveButton(Modifier)
                }
            }
        },
        controls = {
            if (shown.type == MediaType.VIDEO && container.mediaUrls.motion(shown) != null) {
                motions[shown.uid]?.Controls(Modifier.padding(horizontal = Space.xs))
            }
        },
        caption = { caption(shown) },
        actions = { actions(shown) },
    ) { page, onZoom, onTap ->
        ViewerPage(
            media = media[page],
            urls = container.mediaUrls,
            active = pager.currentPage == page,
            motion = motions[media[page].uid],
            onZoom = onZoom,
            onTap = onTap,
            playKeys = playKeys,
            over = { over(media[page]) },
        )
    }
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

@Composable
private fun ViewerPage(
    media: MomentMedia,
    urls: MediaUrls,
    active: Boolean,
    motion: ViewerMotion?,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    playKeys: SharedFlow<Unit>,
    over: @Composable () -> Unit,
) {
    val url = urls.motion(media)?.takeIf { motion != null }
    // Space, while this page is the one shown: a video plays or pauses, a live photo plays or stops.
    LaunchedEffect(active, playKeys) {
        if (active && url != null) playKeys.collect { motion?.toggle() }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableStill(
            media,
            urls,
            onZoom,
            onTap,
            // Held down, a live photo plays with its sound until let go.
            onHold = motion?.hold?.takeIf { url != null && media.type == MediaType.LIVE_PHOTO },
        ) {
            if (url != null) motion?.Over(url, active, Modifier.fillMaxSize())
            over()
        }
        if (url != null) motion?.Waiting()
    }
}

/**
 * A still that zooms with two fingers or a double tap and pans once zoomed, with
 * telephoto's flings and edges. One finger at full size is left to the pager and the
 * swipes, so they still turn and close. Zoomed in, it asks for a wider file, so the
 * detail holds up.
 */
@Composable
private fun ZoomableStill(
    media: MomentMedia,
    urls: MediaUrls,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val scale = zoom.contentTransformation.scale.scaleX.takeIf { it.isFinite() } ?: 1f
    val zoomed = (zoom.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoom(zoomed) }
    // The still the card drew, already in memory, stands in while the larger one loads.
    val drawn = remember(media.uid) { DrawnStills.of(media.uid) }
    val shape = shapeOf(media)
    var held by remember { mutableStateOf(false) }

    // The screen takes the gestures; the photo, at its own shape and no larger than the
    // screen, is what grows out of the card's tile and back into it, so the tile's crop
    // opens out into the whole photo rather than jumping to it.
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // Held down, the finger's lifting ends what the hold started.
            .pointerInput(onHold) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()
                    if (held) {
                        held = false
                        onHold?.invoke(false)
                    }
                }
            }
            .zoomable(
                zoom,
                onClick = { onTap() },
                onLongClick = if (onHold != null) {
                    {
                        if (!zoomed) {
                            held = true
                            onHold(true)
                        }
                    }
                } else {
                    null
                },
                onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Where the photo sits inside the screen, fitted and centred as it is laid out, so
        // a pan stops at the photo's edges rather than the screen's.
        val fitted = remember(shape, constraints) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            if (width / height > shape) Size(height * shape, height) else Size(width, width / shape)
        }
        // Told before the first frame is drawn: until then the zoom keeps the photo hidden
        // and unscaled, and the first flight into the viewer measured that instead.
        SideEffect { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(fitted)) }

        // The still and the motion over it zoom and pan as one.
        Box(Modifier.aspectRatio(shape)) {
            MediaStill(
                media,
                urls,
                placeholder = Color.Transparent,
                placeholderUrl = drawn,
                detail = if (scale > 1.5f) scale else 1f,
                modifier = Modifier.fillMaxSize(),
                // Fetching it failed: said in the middle, with the way to try again, on the black.
                failed = { retry ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Text(stringResource(Res.string.media_not_loaded), style = AppTheme.typography.body, color = MediaOverlay.content)
                        TextButton(onClick = retry, modifier = Modifier.testTag("media_retry")) {
                            Text(stringResource(Res.string.action_retry), color = MediaOverlay.content)
                        }
                    }
                },
            )
            overlay()
        }
    }
}

/** As far as a photo zooms, and how far a double tap takes it. */
const val MAX_ZOOM = 6f
const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * A photo of [shape] (width over height) that zooms as the viewer's do: two fingers or a
 * double tap, up to the same limit, panning once zoomed and stopping at the photo's edges.
 * [onZoom] says whether it is zoomed in, for a pager to stop turning meanwhile; [onHold] whether
 * it is held down, at full size, until the finger lifts; [onTap] a tap, for the viewer to hide its bars.
 */
@Composable
fun ZoomablePhoto(
    shape: Float,
    onZoom: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onHold: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val zoomed = (zoom.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoom(zoomed) }
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    BoxWithConstraints(
        modifier.fillMaxSize()
            // Held down, the finger's lifting ends what the hold started.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()
                    if (held) {
                        held = false
                        hold?.invoke(false)
                    }
                }
            }
            .zoomable(
                zoom,
                onClick = onTap?.let { tap -> { tap() } },
                onLongClick = if (onHold != null) {
                    {
                        if (!zoomed) {
                            held = true
                            onHold(true)
                        }
                    }
                } else {
                    null
                },
                onDoubleClick = DoubleClickToZoomListener.cycle(DOUBLE_TAP_ZOOM),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val fitted = remember(shape, constraints) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            if (width / height > shape) Size(height * shape, height) else Size(width, width / shape)
        }
        SideEffect { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(fitted)) }
        Box(Modifier.aspectRatio(shape)) { content() }
    }
}
