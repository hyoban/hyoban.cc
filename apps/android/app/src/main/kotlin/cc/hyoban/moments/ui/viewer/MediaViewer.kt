package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Spinner
import android.app.Activity
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import kotlin.math.abs
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.media.Share
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.ui.components.DrawnStills
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.mediaKey
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sharedMedia
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
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
 * A Moment's media full screen on black. Swipe sideways between them, up or down to close;
 * tap to hide everything but the photo; pinch or double-tap to zoom. Over it a bar with
 * close, which of how many and a live photo's motion, and under it a video's controls,
 * when and where, which opens the day, and sharing. What else there is about it is on the
 * Moment's page under the viewer. With a keyboard, as Material 3 has one work alongside
 * touch: the arrows go from one to the next, Space plays and pauses, and Escape closes.
 */
@Composable
fun MediaViewer(
    container: AppContainer,
    uid: String,
    index: Int,
    onBack: () -> Unit,
    onShown: (String) -> Unit = {},
    onDay: (String) -> Unit = {},
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val media = moment.media
    if (media.isEmpty()) return

    val pager = rememberPagerState(initialPage = index.coerceIn(0, media.lastIndex)) { media.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    // How far the photo has been dragged up or down, read only where it is drawn, so a drag
    // moves pixels rather than rebuilding the screen every frame.
    var drag by remember { mutableFloatStateOf(0f) }
    val dragging by remember { derivedStateOf { drag != 0f } }
    val density = LocalDensity.current
    val closeAt = with(density) { 120.dp.toPx() }
    // In a window of its own only the video shows: no bars, no controls.
    val inPip = LocalInPictureInPicture.current
    ViewerSystemBars(hidden = !chrome || inPip)
    // The photo shown, for its tile to step aside while it is.
    LaunchedEffect(pager.currentPage, media) { onShown(mediaKey(moment.uid, media[pager.currentPage.coerceIn(0, media.lastIndex)].uid)) }
    // Each one's motion and video, held here so the bars over the pages work the one shown.
    val lives = remember(media) { media.associate { it.uid to LiveMotionState() } }
    val videos = remember(media) { media.associate { it.uid to VideoState() } }
    val shown = media[pager.currentPage.coerceIn(0, media.lastIndex)]

    // Space pressed, for the page shown to play or pause what it holds.
    val playKeys = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val keyScope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { keys.requestFocus() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (zoomed) return@onKeyEvent false
                        val next = (event.key == Key.DirectionRight) != rtl
                        val page = (pager.currentPage + if (next) 1 else -1).coerceIn(0, media.lastIndex)
                        keyScope.launch { pager.animateScrollToPage(page) }
                        true
                    }
                    Key.Spacebar -> playKeys.tryEmit(Unit)
                    Key.Escape -> {
                        onBack()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable(),
    ) {
        val fadeOver = constraints.maxHeight / 3f
        // How far a drag has gone toward closing: the photo shrinks a little and the black
        // thins out to the page under the viewer.
        fun progress() = (abs(drag) / fadeOver).coerceIn(0f, 1f)
        // The system's back gesture previews closing as a drag down does, the photo following
        // the finger; let go, the photo flies back to its tile from there, and called off it settles.
        val settleSpec = AppTheme.motionScheme.fastSpatialSpec<Float>()
        PredictiveBackHandler { events ->
            try {
                events.collect { drag = it.progress * fadeOver }
                onBack()
            } catch (e: CancellationException) {
                keyScope.launch { animate(drag, 0f, animationSpec = settleSpec) { value, _ -> drag = value } }
                throw e
            }
        }

        val backdrop = Color.Black
        Box(Modifier.fillMaxSize().drawBehind { drawRect(backdrop.copy(alpha = 1f - progress())) })
        HorizontalPager(
            pager,
            Modifier.fillMaxSize()
                // The drag moves and shrinks the photo in layout, not just in drawing, so
                // where it is when let go is where its flight back into its tile starts.
                .layout { measurable, constraints ->
                    val scale = 1f - 0.2f * progress()
                    val width = (constraints.maxWidth * scale).roundToInt()
                    val height = (constraints.maxHeight * scale).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2 + drag.roundToInt())
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !zoomed,
                    state = rememberDraggableState { delta -> drag += delta },
                    onDragStopped = { velocity ->
                        if (abs(drag) > closeAt || abs(velocity) > 2000f) onBack()
                        else animate(drag, 0f, initialVelocity = velocity) { value, _ -> drag = value }
                    },
                ),
            userScrollEnabled = !zoomed,
            key = { media[it].uid },
        ) { page ->
            ViewerPage(
                momentUid = moment.uid,
                media = media[page],
                urls = container.mediaUrls,
                active = pager.currentPage == page,
                live = lives.getValue(media[page].uid),
                video = videos.getValue(media[page].uid),
                onZoom = { zoomed = it },
                onTap = { chrome = !chrome },
                playKeys = playKeys,
            )
        }

        val motion = AppTheme.motionScheme
        val bars = chrome && !dragging && !inPip
        AnimatedVisibility(bars, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
            // Close at the start, which of how many in the middle, and a live photo's motion at the end.
            Box(Modifier.fillMaxWidth().background(MediaOverlay.bar).statusBarsPadding().sideInsetsPadding().padding(Space.xs)) {
                Tooltip(stringResource(R.string.action_close), below = true, modifier = Modifier.align(Alignment.CenterStart)) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), tint = MediaOverlay.content)
                    }
                }
                if (media.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${media.size}",
                        color = MediaOverlay.content,
                        style = AppTheme.typography.body,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (shown.type == MediaType.LIVE_PHOTO && container.mediaUrls.motion(shown) != null) {
                    ViewerLiveButton(lives.getValue(shown.uid), Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        AnimatedVisibility(
            bars,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().background(MediaOverlay.bar).navigationBarsPadding().sideInsetsPadding()) {
                if (shown.type == MediaType.VIDEO && container.mediaUrls.motion(shown) != null) {
                    VideoControls(videos.getValue(shown.uid), Modifier.padding(horizontal = Space.xs))
                }
                Row(Modifier.padding(start = Space.gutter - Space.s, end = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    // When and where, tapped for the rest of its day.
                    val words = listOfNotNull(formatDay(moment.occurredAt), formatTime(moment.occurredAt)) + moment.places.map { places[it]?.name ?: it }
                    Text(
                        words.joinToString("\u2003"),
                        style = AppTheme.typography.caption,
                        color = MediaOverlay.contentVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clip(AppTheme.shapes.medium)
                            .clickable(onClickLabel = stringResource(R.string.label_whole_day)) { onDay(moment.dateKey) }
                            .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = Space.s).testTag("info_day"),
                    )
                    ShareShown(container, shown)
                }
            }
        }
    }
}

/** The one shown, fetched first when it's the site's, the spinner in its place meanwhile. */
@Composable
private fun ShareShown(container: AppContainer, shown: MomentMedia) {
    val context = LocalContext.current
    val show = rememberShowMessage()
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Tooltip(stringResource(R.string.action_share)) {
        IconButton(
            onClick = {
                sharing = true
                scope.launch {
                    try {
                        Share.media(context, shown, container.mediaUrls)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        show(context.getString(R.string.error_share))
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
                    Icon(AppIcons.Share, contentDescription = stringResource(R.string.action_share), tint = MediaOverlay.content)
                }
            }
        }
    }
}

@Composable
private fun ViewerPage(
    momentUid: String,
    media: MomentMedia,
    urls: MediaUrls,
    active: Boolean,
    live: LiveMotionState,
    video: VideoState,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    playKeys: SharedFlow<Unit>,
) {
    val motion = urls.motion(media)
    val liveMotion = motion?.takeIf { media.type == MediaType.LIVE_PHOTO }
    val videoUrl = motion?.takeIf { media.type == MediaType.VIDEO }
    // Space, while this page is the one shown: a video plays or pauses, a live photo plays or stops.
    LaunchedEffect(active, playKeys) {
        if (active) playKeys.collect {
            val player = video.player
            when {
                videoUrl != null && player != null -> if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    player.play()
                }
                liveMotion != null && live.ready -> if (live.playing) live.stop() else live.play()
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableStill(
            momentUid,
            media,
            urls,
            onZoom,
            onTap,
            shared = active,
            // Held down, a live photo plays with its sound until let go.
            onHold = if (liveMotion != null) { held -> if (held) live.play(sound = true) else live.stop() } else null,
        ) {
            if (liveMotion != null) LiveMotion(liveMotion, active, live, Modifier.fillMaxSize())
            if (videoUrl != null) VideoPlayback(videoUrl, active, video, Modifier.fillMaxSize())
        }
        if (videoUrl != null) VideoWaiting(video)
    }
}

/** A live photo's motion in the bar over it: a ring while it loads, then a tap plays it again or stops it. */
@Composable
private fun ViewerLiveButton(state: LiveMotionState, modifier: Modifier = Modifier) {
    val label = stringResource(if (state.playing) R.string.action_pause else R.string.action_play)
    Tooltip(stringResource(R.string.label_live), below = true, modifier = modifier) {
        IconButton(onClick = { if (state.playing) state.stop() else state.play() }, enabled = state.ready, modifier = Modifier.testTag("live")) {
            if (state.ready) {
                Icon(AppIcons.Live, contentDescription = label, tint = MediaOverlay.content)
            } else {
                Spinner(Modifier.size(20.dp), color = MediaOverlay.content)
            }
        }
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
    momentUid: String,
    media: MomentMedia,
    urls: MediaUrls,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHold: ((Boolean) -> Unit)? = null,
    /** Whether this is the page shown, the one photo that flies to and from its tile. */
    shared: Boolean = true,
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
                modifier = Modifier.fillMaxSize().sharedMedia(mediaKey(momentUid, media.uid).takeIf { shared }),
            )
            overlay()
        }
    }
}

/** As far as a photo zooms, and how far a double tap takes it. */
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * White navigation icons over the black while the viewer is open, and the
 * bars themselves hidden when everything but the photo is; a swipe from the edge shows
 * them for a moment.
 */
@Composable
private fun ViewerSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(controller) {
        // The status bar's icons follow what is under them, white over the black.
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.isAppearanceLightNavigationBars = navigation
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
