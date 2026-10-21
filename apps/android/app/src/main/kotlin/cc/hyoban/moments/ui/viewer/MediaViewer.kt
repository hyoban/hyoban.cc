package cc.hyoban.moments.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.DrawnStills
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.NeutralChip
import cc.hyoban.moments.ui.components.PressableCard
import cc.hyoban.moments.ui.components.SectionHeader
import cc.hyoban.moments.ui.components.Thumbnail
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.kindLabel
import cc.hyoban.moments.ui.components.landscape
import cc.hyoban.moments.ui.components.mediaKey
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.sharedMedia
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.map.PlacesStill
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.tripSummary
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
 * A Moment's media full screen on black. Swipe sideways between them, down to close and
 * up for the details; tap to hide everything but the photo; pinch or double-tap to zoom.
 * A video or a live photo plays from its own button. With a keyboard, as Material 3 has one
 * work alongside touch: the arrows go from one to the next, Space plays and pauses, and
 * Escape closes the details, then the viewer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewer(
    container: AppContainer,
    uid: String,
    index: Int,
    onMoment: (String) -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    onBack: () -> Unit,
    onShown: (String) -> Unit = {},
) {
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val moment = remember(moments, uid) { moments.firstOrNull { it.uid == uid } } ?: return
    val media = moment.media
    if (media.isEmpty()) return

    val pager = rememberPagerState(initialPage = index.coerceIn(0, media.lastIndex)) { media.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var info by rememberSaveable { mutableStateOf(false) }
    // How far the photo has been dragged down, read only where it is drawn, so a drag
    // moves pixels rather than rebuilding the screen every frame.
    var drag by remember { mutableFloatStateOf(0f) }
    val dragging by remember { derivedStateOf { drag != 0f } }
    val density = LocalDensity.current
    val closeAt = with(density) { 120.dp.toPx() }
    val infoAt = with(density) { 64.dp.toPx() }
    // In a window of its own only the video shows: no bars, no controls, no details.
    val inPip = LocalInPictureInPicture.current
    LaunchedEffect(inPip) { if (inPip) info = false }
    ViewerSystemBars(hidden = !chrome || inPip)
    // The photo shown, for its tile to step aside while it is.
    LaunchedEffect(pager.currentPage, media) { onShown(mediaKey(moment.uid, media[pager.currentPage.coerceIn(0, media.lastIndex)].uid)) }

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
                        if (info) info = false else onBack()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable(),
    ) {
        val fadeOver = constraints.maxHeight / 3f
        // How far a downward drag has gone toward closing: the photo shrinks a little and
        // the black thins out to the page under the viewer.
        fun progress() = (drag / fadeOver).coerceIn(0f, 1f)

        val backdrop = MaterialTheme.colorScheme.scrim
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
                        placeable.place(
                            (constraints.maxWidth - width) / 2,
                            (constraints.maxHeight - height) / 2 + drag.coerceAtLeast(0f).roundToInt(),
                        )
                    }
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !zoomed,
                    state = rememberDraggableState { delta -> drag += delta },
                    onDragStopped = { velocity ->
                        when {
                            drag > closeAt || velocity > 2000f -> onBack()
                            drag < -infoAt -> {
                                info = true
                                animate(drag, 0f) { value, _ -> drag = value }
                            }
                            else -> animate(drag, 0f, initialVelocity = velocity) { value, _ -> drag = value }
                        }
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
                chrome = chrome && !dragging && !inPip,
                onZoom = { zoomed = it },
                onTap = { chrome = !chrome },
                playKeys = playKeys,
            )
        }

        AnimatedVisibility(chrome && !dragging && !inPip, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(MediaOverlay.bar).statusBarsPadding().sideInsetsPadding().padding(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tooltip(stringResource(R.string.action_back), below = true) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = MediaOverlay.content)
                    }
                }
                if (media.size > 1) {
                    Text("${pager.currentPage + 1} / ${media.size}", color = MediaOverlay.content, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                // The one shown, fetched first when it's the site's, the spinner in its place meanwhile.
                val context = LocalContext.current
                val show = rememberShowMessage()
                val scope = rememberCoroutineScope()
                var sharing by remember { mutableStateOf(false) }
                Tooltip(stringResource(R.string.action_share), below = true) {
                    IconButton(
                        onClick = {
                            val shown = media[pager.currentPage]
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
                        if (sharing) {
                            LoadingIndicator(Modifier.size(24.dp), color = MediaOverlay.content)
                        } else {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share), tint = MediaOverlay.content)
                        }
                    }
                }
                Tooltip(stringResource(R.string.action_info), below = true) {
                    IconButton(onClick = { info = true }, modifier = Modifier.testTag("info")) {
                        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.action_info), tint = MediaOverlay.content)
                    }
                }
            }
        }

        val details: @Composable (Modifier) -> Unit = { modifier ->
            InfoContent(
                container = container,
                moment = moment,
                media = media[pager.currentPage],
                places = places,
                onMoment = { info = false; onMoment(moment.uid) },
                onPlace = { info = false; onPlace(it) },
                onTrip = { info = false; onTrip(it) },
                modifier = modifier,
            )
        }
        // On a phone on its side, the details are a panel down the end side, the photo
        // still showing beside it, rather than a sheet with no height to rise into.
        val side = landscape
        BackHandler(enabled = info && side) { info = false }
        AnimatedVisibility(info && side, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(MediaOverlay.bar)
                    .clickable(interactionSource = null, indication = null) { info = false },
            )
        }
        AnimatedVisibility(
            info && side,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val level = AppColors.sheetLevel
            Surface(
                shape = MaterialTheme.shapes.extraLarge.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize),
                color = level.fill(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                // Below the status bar, whose icons are white over the viewer's black.
                modifier = Modifier.statusBarsPadding().fillMaxHeight().width(INFO_PANEL_WIDTH),
            ) {
                CompositionLocalProvider(LocalSurfaceLevel provides level) {
                    details(Modifier.sideInsetsPadding(WindowInsetsSides.End).padding(top = Space.l).navigationBarsPadding())
                }
            }
        }
        if (info && !side) {
            InfoSheet(onDismiss = { info = false }) { details(Modifier) }
        }
    }
}

/** How wide the details' panel is down the side of a phone on its side. */
private val INFO_PANEL_WIDTH = 400.dp
/** How high a live photo's badge sits over the photo's bottom, clear of the system's gesture area where a thumb rests. */
private val LIVE_BADGE_LIFT = 88.dp

@Composable
private fun ViewerPage(
    momentUid: String,
    media: MomentMedia,
    urls: MediaUrls,
    active: Boolean,
    chrome: Boolean,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    playKeys: SharedFlow<Unit>,
) {
    val motion = urls.motion(media)
    val live = rememberLiveMotionState(media.uid)
    val liveMotion = motion?.takeIf { media.type == MediaType.LIVE_PHOTO }
    val video = rememberVideoState(media.uid)
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
        when (media.type) {
            MediaType.VIDEO -> if (videoUrl != null) {
                VideoWaiting(video)
                VideoControls(video, visible = chrome && active, modifier = Modifier.align(Alignment.BottomCenter))
            }
            MediaType.LIVE_PHOTO -> if (liveMotion != null) {
                ViewerLiveBadge(
                    live,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LIVE_BADGE_LIFT),
                )
            }
            MediaType.IMAGE -> Unit
        }
    }
}

/** A section of the panel: a card headed by its icon and name. */
@Composable
private fun InfoCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    val level = LocalSurfaceLevel.current.raised
    Surface(shape = MaterialTheme.shapes.large, color = level.fill(), modifier = Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalSurfaceLevel provides level) {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionHeader(icon, title)
                content()
            }
        }
    }
}

/** A tile inside a card, a level into it: some text, with its [label] over it when it has one. */
@Composable
private fun InfoTile(text: String, label: String? = null) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(LocalSurfaceLevel.current.raised.fill()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The LIVE badge under a live photo: a ring while its motion loads, then a tap plays it again. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewerLiveBadge(state: LiveMotionState, modifier: Modifier = Modifier) {
    Row(
        // A tap target of 48dp around the badge, as Material asks of anything tapped.
        modifier.minimumInteractiveComponentSize()
            .clip(Corners.full)
            .background(MediaOverlay.control)
            .clickable(
                enabled = state.ready,
                onClickLabel = stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                role = Role.Button,
            ) { if (state.playing) state.stop() else state.play() }
            .testTag("live")
            .padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (state.ready) {
            Icon(Icons.Rounded.MotionPhotosOn, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(16.dp))
        } else {
            LoadingIndicator(Modifier.size(16.dp), color = MediaOverlay.content)
        }
        Text(stringResource(R.string.label_live), color = MediaOverlay.content, style = MaterialTheme.typography.labelMedium)
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

/** What there is to know about the photo: when, where on a small map, the Moment's words, what it shows, the trip. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InfoContent(
    container: AppContainer,
    moment: MomentRecord,
    media: MomentMedia,
    places: Map<String, Place>,
    onMoment: () -> Unit,
    onPlace: (String) -> Unit,
    onTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val trip = remember(moment, trips) { tripOf(moment, trips) }
    val located = remember(moment, places) { moment.places.mapNotNull { places[it] } }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(formatDay(moment.occurredAt), style = MaterialTheme.typography.headlineSmall)
            formatTime(moment.occurredAt)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (moment.places.isNotEmpty()) {
            InfoCard(Icons.Rounded.Place, stringResource(R.string.section_places)) {
                if (located.isNotEmpty()) {
                    PlacesStill(located, Modifier.fillMaxWidth().aspectRatio(2f).clip(MaterialTheme.shapes.medium))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    moment.places.forEach { id -> NeutralChip(places[id]?.name ?: id, Icons.Rounded.Place, onClick = { onPlace(id) }, modifier = Modifier.testTag("place_$id")) }
                }
            }
        }
        if (moment.text.isNotBlank()) {
            Text(moment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Space.xs))
        }
        if (media.alt.isNotBlank() || (media.width != null && media.height != null)) {
            InfoCard(Icons.Rounded.Image, stringResource(R.string.section_photo)) {
                // What the photo is and its description, each a tile a level into the card.
                if (media.width != null && media.height != null) {
                    InfoTile(
                        stringResource(
                            R.string.label_media_size,
                            stringResource(media.kindLabel()),
                            media.width,
                            media.height,
                        ),
                    )
                }
                if (media.alt.isNotBlank()) InfoTile(media.alt, label = stringResource(R.string.label_alt))
            }
        }
        trip?.let { trip ->
            val cover = remember(trip, moments) { coversOf(trip, moments, trips, limit = 1).firstOrNull() }
            val count = remember(trip, moments) { momentsOf(trip, moments, trips).size }
            PressableCard(onClick = { onTrip(trip.id) }, modifier = Modifier.fillMaxWidth().testTag("trip_${trip.id}")) {
                ListItem(
                    headlineContent = { Text(trip.title) },
                    supportingContent = { Text(tripSummary(trip, count)) },
                    leadingContent = cover?.let { { Thumbnail(it, container.mediaUrls, size = 48) } },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        // The panel's one action, so the one thing in colour.
        Button(onClick = onMoment, modifier = Modifier.fillMaxWidth().padding(top = Space.xs).testTag("open_moment")) {
            Text(stringResource(R.string.action_open_moment))
        }
    }
}

/** The details in a sheet risen from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val level = AppColors.sheetLevel
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = level.fill()) {
        CompositionLocalProvider(LocalSurfaceLevel provides level) { content() }
    }
}

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
