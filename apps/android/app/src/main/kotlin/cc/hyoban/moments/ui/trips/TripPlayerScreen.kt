package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.ui.design.emphasized

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import cc.hyoban.moments.ui.design.FloatingBar
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.ui.graphics.StrokeCap
import cc.hyoban.moments.ui.design.LinearProgressIndicator
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import cc.hyoban.moments.ui.design.ButtonDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.ui.moment.MomentMenu
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.Role
import cc.hyoban.moments.ui.LocalSnackbarLift
import cc.hyoban.moments.ui.components.kindLabel
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import cc.hyoban.moments.ui.design.FilledIconButton
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.PlaybackSlide
import cc.hyoban.moments.data.trips.TripPlayback
import cc.hyoban.moments.data.trips.coversOf
import cc.hyoban.moments.data.trips.momentsOf
import cc.hyoban.moments.data.trips.tripPlayback
import cc.hyoban.moments.ui.components.FLOATING_BAR
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.map.PlaybackMap
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import kotlin.math.roundToInt

/** Keeps the playback while a Moment opened from it is read, and drops it with the page. */
class PlaybackHolder : ViewModel() {
    var controller: PlaybackController? = null
}

/**
 * A trip played back as the site plays it: its route drawn on over the whole screen, never
 * still while it plays, and a card over the map, the trip's opening, each day's opener, each
 * Moment and a look back, with a floating toolbar to pause and step from card to card. A
 * Moment's card opens it when tapped. The screen stays on while it plays.
 */
@Composable
fun TripPlayerScreen(container: AppContainer, id: String, onMoment: (String) -> Unit, onBack: () -> Unit) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val routes by container.tripRoutes.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    val moments = remember(trip, all) { momentsOf(trip, all, trips) }
    val segments = routes[trip.id].orEmpty()
    val playback = remember(trip, moments, places, segments) { tripPlayback(trip, moments, places, segments) }
    val reduced = LocalReducedMotion.current
    val holder = viewModel(key = "play/$id") { PlaybackHolder() }
    val controller = remember(playback, holder) {
        holder.controller?.takeIf { it.playback === playback } ?: PlaybackController(playback, reduced).also {
            it.begin()
            holder.controller = it
        }
    }
    val slide = controller.slide
    val index = controller.index
    // Open, the card shows the photos; folded down, only the words and the progress, the map the rest.
    var expanded by rememberSaveable(id) { mutableStateOf(true) }

    val density = LocalDensity.current
    val landscape = isLandscape()
    var card by remember { mutableStateOf(0.dp to 0.dp) }
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + FLOATING_BAR + Space.l
    // The site's camera padding on a narrow screen: 40 at the sides, clear of the card below.
    val clear = if (landscape) listOf(40.dp, top, card.first + 40.dp, 40.dp + TOOLBAR_ROOM) else listOf(40.dp, top, 40.dp, card.second + 16.dp)

    // From a keyboard or a remote: play and pause on Space or the media keys, the cards on
    // left and right and the photos on up and down, as on the site, and the media keys' next
    // and previous; focus, once a key moves it in, starts on play, the screen's main action,
    // rather than taken at once, which would hold play's tooltip up under a finger.
    val playFocus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().then(if (controller.playing) Modifier.keepScreenOn() else Modifier)
            // Focus coming into the screen, as the first key is pressed, lands on play.
            .focusRestorer(playFocus).focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.MediaPlayPause -> controller.togglePlay()
                    Key.MediaPlay -> if (!controller.playing) controller.togglePlay()
                    Key.MediaPause -> if (controller.playing) controller.togglePlay()
                    // Right is next, as the controls run left to right whatever the language.
                    Key.DirectionRight -> controller.next()
                    Key.DirectionLeft -> controller.previous()
                    Key.MediaNext -> controller.next()
                    Key.MediaPrevious -> controller.previous()
                    Key.DirectionDown -> controller.stepPhoto(1)
                    Key.DirectionUp -> controller.stepPhoto(-1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        // Described as a map, by what it shows now: the trip's route and where it has got to.
        val here = when (slide) {
            is PlaybackSlide.Stop -> slide.stop.places.lastOrNull()?.name
            is PlaybackSlide.Day -> slide.places.firstOrNull()
            else -> null
        }
        val routeMap = if (here != null) stringResource(R.string.playback_map_at, trip.title, here) else stringResource(R.string.playback_map, trip.title)
        PlaybackMap(controller, clear, Modifier.fillMaxSize().semantics { contentDescription = routeMap })
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))

        // Snackbars stand above the card and its toolbar while it is at the foot of the screen.
        val lift = LocalSnackbarLift.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        DisposableEffect(lift, card, landscape) {
            lift.value = if (landscape) 0.dp else (card.second - navBottom).coerceAtLeast(0.dp)
            onDispose { lift.value = 0.dp }
        }

        // The whole playback's progress: the cards before at their full length, this one as far as it has played.
        val lengths = remember(playback) { playback.slides.map { TripPlayback.duration(it) ?: 0L } }
        val total = remember(lengths) { lengths.sum().coerceAtLeast(1) }
        val before = remember(lengths, index) { lengths.take(index).sum() }
        PlayerCard(
            playFocus = playFocus,
            trip = trip,
            controller = controller,
            covers = remember(trip, all) { coversOf(trip, all, trips) },
            urls = container.mediaUrls,
            progress = {
                val duration = controller.duration() ?: 0L
                val share = if (duration > 0) controller.elapsed.toFloat() / duration else 1f
                ((before + lengths[index] * share.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
            },
            onMoment = { uid ->
                controller.pause()
                onMoment(uid)
            },
            modifier = Modifier
                .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                // The room it takes from the map, its margin and the navigation bar's included.
                .onSizeChanged { with(density) { card = it.width.toDp() to it.height.toDp() } }
                .navigationBarsPadding()
                // On its side, the card and its toolbar share the height under the status bar.
                .then(if (landscape) Modifier.statusBarsPadding() else Modifier)
                .padding(Space.gutter)
                // Beside the map on its side, as Material 3 has a supporting pane at 360dp.
                // Across the foot no wider than Material 3 has a bottom sheet, 640dp.
                .then(if (landscape) Modifier.width(360.dp + Space.gutter * 2) else Modifier.widthIn(max = 640.dp).fillMaxWidth()),
            photoHeight = if (landscape) 100.dp else 220.dp,
            slide = slide,
            index = index,
            expanded = expanded,
            onExpanded = { expanded = it },
            // On its side, the toolbar stands under the map instead, so the card has the height.
            toolbar = !landscape,
        )
        if (landscape) {
            Box(
                Modifier.fillMaxSize().navigationBarsPadding().padding(end = card.first, bottom = Space.gutter),
                contentAlignment = Alignment.BottomCenter,
            ) { PlaybackControls(controller, playFocus) }
        }
    }
}

/** The playback's toolbar for [controller]: a media player's controls, left to right in any language. */
@Composable
private fun PlaybackControls(controller: PlaybackController, playFocus: FocusRequester) {
    val index = controller.index
    val count = controller.playback.slides.size
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        PlayerToolbar(
            controller.playing,
            atEnd = controller.duration() == null,
            onPlay = controller::togglePlay,
            onPrevious = controller::previous,
            onNext = controller::next,
            first = index == 0,
            last = index == count - 1,
            playFocus = playFocus,
        )
    }
}

/**
 * The card over the map, what is playing with the playback's progress at its foot, and the
 * floating toolbar of its controls under it.
 */
@Composable
private fun PlayerCard(
    playFocus: FocusRequester,
    trip: TripRecord,
    controller: PlaybackController,
    covers: List<MomentMedia>,
    urls: MediaUrls,
    progress: () -> Float,
    onMoment: (String) -> Unit,
    modifier: Modifier,
    photoHeight: Dp,
    slide: PlaybackSlide,
    index: Int,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    toolbar: Boolean,
) {
    val count = controller.playback.slides.size
    val playing = controller.playing
    val atEnd = controller.duration() == null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        val stop = slide as? PlaybackSlide.Stop
        val swipe = with(LocalDensity.current) { SWIPE_TO_STEP.toPx() }
        val forward = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
        // The card holds its actions, the handle, the photos and the words, rather than being one,
        // so no action sits on an actionable surface; a Moment's still grows into its page as its
        // cards do. Swiped sideways it steps through the stop's photos and past them to the card
        // before or after, as the site's photo swipe does; swiped down it folds to its words, and
        // up it opens again, as a sheet over a map does. It follows the finger as it is dragged,
        // lifted to the dragged elevation, and springs back as it is let go.
        val scope = rememberCoroutineScope()
        val reduced = LocalReducedMotion.current
        val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        val settle = AppTheme.motionScheme.fastSpatialSpec<Offset>()
        val lift by animateDpAsState(if (dragging) Elevation.level4 else Elevation.level2, AppTheme.motionScheme.fastSpatialSpec(), label = "lift")
        // Dragged, Material 3's low-emphasis state layer over it too, the content's colour at 16%.
        val layer by animateFloatAsState(if (dragging) DRAGGED_LAYER else 0f, AppTheme.motionScheme.fastEffectsSpec(), label = "dragged")
        val layerColor = tokens.tx
        val cardShape = AppTheme.shapes.large
        val letGo = {
            dragging = false
            scope.launch { if (reduced) drag.snapTo(Offset.Zero) else drag.animateTo(Offset.Zero, settle) }
        }
        Surface(
            shape = cardShape,
            color = SurfaceLevel.Floating.fill(),
            shadowElevation = lift,
            // No taller than the room the toolbar leaves, so it is always in sight; what doesn't fit scrolls.
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                .graphicsLayer {
                    translationX = drag.value.x
                    translationY = drag.value.y
                }
                .then(if (stop != null) Modifier.sharedCard(stop.stop.moment.uid, cardCorner = Radius.large) else Modifier)
                .pointerInput(swipe, forward) {
                    var dragged = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            dragged = Offset.Zero
                            dragging = true
                        },
                        onDragEnd = {
                            val (x, y) = dragged
                            when {
                                abs(x) >= abs(y) && x * forward > swipe -> controller.stepPhoto(1)
                                abs(x) >= abs(y) && -x * forward > swipe -> controller.stepPhoto(-1)
                                abs(y) > abs(x) && y > swipe -> onExpanded(false)
                                abs(y) > abs(x) && -y > swipe -> onExpanded(true)
                            }
                            letGo()
                        },
                        onDragCancel = { letGo() },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                        // Held back, so the card leans the way it's pulled rather than leaving its place.
                        scope.launch { drag.snapTo(dragged * DRAG_FOLLOW) }
                    }
                }
                // Over the card's own shape, not past its corners.
                .drawWithContent {
                    drawContent()
                    if (layer > 0f) drawOutline(cardShape.createOutline(size, layoutDirection, this), layerColor.copy(alpha = layer))
                }
                .testTag("trip_player"),
        ) {
            val motion = AppTheme.motionScheme
            Column(Modifier.animateContentSize(motion.defaultSpatialSpec())) {
            // The handle across the card's top 48dp, as a sheet's.
            DragHandle(expanded, onExpanded)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = Space.m, end = Space.m, bottom = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                AnimatedContent(
                    index to slide,
                    transitionSpec = lateral { it.first },
                    label = "player card",
                    // Playing on its own it stays quiet, as a carousel does; stepped through by hand, each card is read.
                    modifier = Modifier.semantics { if (!playing) liveRegion = LiveRegionMode.Polite },
                ) { (_, shown) ->
                    when (shown) {
                        PlaybackSlide.Intro -> IntroCard(trip, controller.playback, covers, urls, photoHeight, expanded)
                        is PlaybackSlide.Day -> DayCard(shown)
                        is PlaybackSlide.Stop -> StopCard(shown, controller, urls, photoHeight, expanded, onMoment)
                        PlaybackSlide.Summary -> SummaryCard(trip, controller.playback, covers, urls, expanded)
                    }
                }
                // The whole playback's progress along the card's foot, a thin line in the text
                // colour on the pressed grey; left to right
                // whichever way the words run, as a media player's timeline is.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Row(Modifier.padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    // Named, with which card it is on, as the count beside it says to the eye.
                    val label = stringResource(R.string.playback_progress, trip.title)
                    val position = stringResource(R.string.playback_card_of, index + 1, count)
                    LinearProgressIndicator(
                        progress = progress,
                        color = tokens.tx,
                        trackColor = tokens.pressed,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = label
                            stateDescription = position
                        },
                    )
                    Text(
                        "${index + 1} / $count",
                        style = AppTheme.typography.caption.copy(fontFeatureSettings = "tnum"),
                        color = tokens.tx2,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                } }
            }
            }
        }
        if (toolbar) PlaybackControls(controller, playFocus)
    }
}

/** The trip's title, its dates, days and Moments, and its covers. */
@Composable
private fun IntroCard(trip: TripRecord, playback: TripPlayback, covers: List<MomentMedia>, urls: MediaUrls, photoHeight: Dp, expanded: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.padding(horizontal = Space.xs)) {
            Text(trip.title, style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
            TripSubhead(trip, playback.stops.size)
        }
        // A mosaic of the covers, the first larger, as the site's intro has.
        if (covers.isNotEmpty() && expanded) {
            // Decorative: the same photos show, described, on their Moments' cards.
            Row(Modifier.fillMaxWidth().height(photoHeight).clip(AppTheme.shapes.large).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
                MediaStill(covers[0], urls, Modifier.weight(2f).fillMaxHeight())
                if (covers.size > 1) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                        covers.drop(1).forEach { MediaStill(it, urls, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

/** A day's opener: which day, its date, and the places ahead in order. */
@Composable
private fun DayCard(slide: PlaybackSlide.Day) {
    Column(Modifier.padding(horizontal = Space.xs, vertical = Space.xs)) {
        Text(stringResource(R.string.trip_day, slide.day), style = AppTheme.typography.label, color = tokens.accent)
        Text(formatDay(slide.dateKey), style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
        if (slide.places.isNotEmpty()) {
            Text(slide.places.joinToString(" → "), style = AppTheme.typography.body, color = tokens.tx2)
        }
    }
}

/**
 * A Moment as the route reaches it: its photos in a row, the one showing large and the next
 * peeking at its side, moving on as it plays; one scrolled to or tapped becomes the
 * one showing, the single tap standing in for the swipe, and the one showing, tapped, opens
 * the Moment, as its words do. As on the site, its day and time ride beside the route's head
 * and its places are named on the map, so the card is only read out a heading of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopCard(slide: PlaybackSlide.Stop, controller: PlaybackController, urls: MediaUrls, photoHeight: Dp, expanded: Boolean, onMoment: (String) -> Unit) {
    val stop = slide.stop
    val moment = stop.moment
    val photos = stop.photos
    val photo by remember(controller, slide) { derivedStateOf { controller.photo() } }
    val heading = listOfNotNull(
        stringResource(R.string.trip_day, stop.day),
        formatDay(moment.dateKey),
        formatTime(moment.occurredAt),
    ).joinToString("\u2003") + "\u2003" + stop.places.joinToString("、") { it.name }
    val open = stringResource(R.string.action_open_moment)
    Column(
        Modifier.fillMaxWidth().semantics {
            contentDescription = heading
            heading()
        }.testTag("player_moment_${moment.uid}"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (photos.isNotEmpty() && expanded) {
            val reduced = LocalReducedMotion.current
            val state = rememberLazyListState(initialFirstVisibleItemIndex = photo)
            // Following the playback's photo...
            LaunchedEffect(state, photo) {
                if (state.firstVisibleItemIndex != photo) if (reduced) state.scrollToItem(photo) else state.animateScrollToItem(photo)
            }
            // ...and making the one scrolled to by hand the playback's.
            LaunchedEffect(state) {
                snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
                    if (!scrolling && state.firstVisibleItemIndex != controller.photo()) controller.selectPhoto(state.firstVisibleItemIndex)
                }
            }
            val view = stringResource(R.string.action_view_photo)
            val clause = stringResource(R.string.separator_clause)
            BoxWithConstraints(Modifier.fillMaxWidth().height(photoHeight)) {
                val width = if (photos.size == 1) maxWidth else maxWidth - PEEK - Space.s
                LazyRow(
                    Modifier.fillMaxSize(),
                    state = state,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    flingBehavior = rememberSnapFlingBehavior(state),
                ) {
                    items(photos.size) { n ->
                        val label = stringResource(R.string.media_position, stringResource(photos[n].kindLabel()), n + 1, photos.size)
                        MediaStill(
                            photos[n],
                            urls,
                            Modifier.width(width).fillMaxHeight().clip(AppTheme.shapes.large)
                                .clickable(onClickLabel = if (n == photo) open else view) { if (n == photo) onMoment(moment.uid) else controller.selectPhoto(n) }
                                .semantics { contentDescription = listOf(label, photos[n].alt).filter { it.isNotBlank() }.joinToString(clause) },
                        )
                    }
                }
            }
        }
        if (moment.text.isNotBlank()) {
            // Held, or right clicked, the Moment's menu opens where it was pressed, as on its cards.
            var menu by remember { mutableStateOf<DpOffset?>(null) }
            val density = LocalDensity.current
            val down = remember { floatArrayOf(0f, 0f) }
            Box {
                Text(
                    moment.text,
                    style = AppTheme.typography.body,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(AppTheme.shapes.medium)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type != PointerEventType.Press) return@awaitEachGesture
                                event.changes.firstOrNull()?.position?.let { down[0] = it.x; down[1] = it.y - size.height }
                                if (event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    menu = with(density) { DpOffset(down[0].toDp(), down[1].toDp()) }
                                }
                            }
                        }
                        .combinedClickable(
                            onClickLabel = open,
                            onLongClickLabel = stringResource(R.string.action_more),
                            onLongClick = { menu = with(density) { DpOffset(down[0].toDp(), down[1].toDp()) } },
                        ) { onMoment(moment.uid) }
                        .padding(horizontal = Space.xs),
                )
                MomentMenu(moment, urls, menu, onDismiss = { menu = null })
            }
        }
    }
}

/** How much of the next photo peeks at the side. */
private val PEEK = 56.dp

/**
 * The trip looked back on, as the site's summary: its title, what it came to as tiles, each
 * number in a display style, as Material 3 sets short important numerals, counting up from
 * nothing as the card shows, and, while the card is open, its covers cut to Material 3's
 * shapes, the trip's last aesthetic moment.
 */
@Composable
private fun SummaryCard(trip: TripRecord, playback: TripPlayback, covers: List<MomentMedia>, urls: MediaUrls, expanded: Boolean) {
    Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Column {
            Text(stringResource(R.string.playback_look_back), style = AppTheme.typography.label, color = tokens.accent)
            Text(trip.title, style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
        }
        val days = cc.hyoban.moments.ui.format.daysBetween(trip.start, trip.end)
        val km = remember(playback) { playback.route.drivenKm() }.roundToInt()
        // The driving ones only when there was driving, as the site's.
        val stats = listOfNotNull(
            days to R.plurals.unit_days,
            playback.stops.size to R.plurals.unit_moments,
            playback.points.distinctBy { it.id }.size to R.plurals.unit_places,
            playback.stops.sumOf { it.photos.size } to R.plurals.unit_photos,
            (km to R.plurals.unit_km_driven).takeIf { km > 0 },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            stats.forEach { (value, unit) -> Tile(value, pluralStringResource(unit, value)) }
        }
        if (expanded && covers.isNotEmpty()) {
            val shapes = List(3) { AppTheme.shapes.large }
            Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                covers.take(shapes.size).forEachIndexed { n, cover ->
                    MediaStill(cover, urls, Modifier.weight(1f).aspectRatio(1f).clip(shapes[n]))
                }
            }
        }
    }
}

/** One of what the trip came to: its number counting up, tabular so it doesn't shift as it rolls, over its unit. */
@Composable
private fun Tile(value: Int, unit: String) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) value.toFloat() else 0f) }
    val spec = AppTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(value) { if (reduced) shown.snapTo(value.toFloat()) else shown.animateTo(value.toFloat(), spec) }
    Column(Modifier.clearAndSetSemantics { contentDescription = "$value $unit" }) {
        Text(
            shown.value.roundToInt().toString(),
            style = AppTheme.typography.numDisplay.emphasized.copy(fontFeatureSettings = "tnum"),
        )
        Text(unit, style = AppTheme.typography.caption, color = tokens.tx2)
    }
}

/**
 * The playback's controls, floating on the surface over the map: a step back and on as icon
 * buttons, and playing or pausing, the one main action, a wide ink capsule between them.
 */
@Composable
private fun PlayerToolbar(
    playing: Boolean,
    atEnd: Boolean,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    first: Boolean,
    last: Boolean,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    FloatingBar(modifier) {
        // Back, play and on, side by side; play the ink, the one main action.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            val previous = stringResource(R.string.action_previous_stop)
            Tooltip(previous) {
                IconButton(onClick = onPrevious, enabled = !first, modifier = Modifier.testTag("player_previous")) {
                    Icon(AppIcons.SkipBack, contentDescription = previous)
                }
            }
            // At the look back, where playback ends, it plays the trip again.
            val play = stringResource(if (atEnd && !playing) R.string.action_replay else if (playing) R.string.action_pause else R.string.action_play)
            Tooltip(play) {
                FilledIconButton(
                    onClick = onPlay,
                    shape = Corners.full,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = tokens.ink, contentColor = tokens.onInk),
                    // Its 48dp target kept round the wide container, so the button sits on the toolbar's
                    // middle rather than its target overflowing the container upward.
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .size(IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
                        .focusRequester(playFocus).testTag("player_play"),
                ) {
                    Crossfade(if (atEnd && !playing) AppIcons.Replay else if (playing) AppIcons.Pause else AppIcons.Play, animationSpec = AppTheme.motionScheme.fastEffectsSpec(), label = "play icon") {
                        Icon(it, contentDescription = play)
                    }
                }
            }
            val next = stringResource(R.string.action_next_stop)
            Tooltip(next) {
                IconButton(onClick = onNext, enabled = !last, modifier = Modifier.testTag("player_next")) {
                    Icon(AppIcons.SkipNext, contentDescription = next)
                }
            }
        }
    }
}

/**
 * Material 3's drag handle at the card's top, 32 by 4dp in the surface's variant ink: the
 * card folds and opens by a swipe, and by a tap here too, as a sheet's handle cycles its
 * heights for those who can't drag.
 */
@Composable
private fun DragHandle(expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    val action = stringResource(if (expanded) R.string.action_collapse_card else R.string.action_expand_card)
    Box(
        Modifier.fillMaxWidth().height(HANDLE_TARGET)
            .clickable(onClickLabel = action, role = Role.Button) { onExpanded(!expanded) }
            .semantics { contentDescription = action }
            .testTag("player_handle"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = tokens.tx2.copy(alpha = 0.4f), modifier = Modifier.size(32.dp, 4.dp)) {}
    }
}

/** How tall the handle's target is: the top 48dp of the card, as a sheet's, the handle 22dp from either edge. */
private val HANDLE_TARGET = 48.dp

/** The dragged state layer's opacity. */
private const val DRAGGED_LAYER = 0.16f

/** How much of a drag the card follows. */
private const val DRAG_FOLLOW = 0.35f

/** How much the floating toolbar takes at the foot: its 64dp and the margin under it. */
private val TOOLBAR_ROOM = 64.dp + 16.dp

/** How far a card is swiped to step to the next or the one before. */
private val SWIPE_TO_STEP = 56.dp

/**
 * Material 3's lateral transition, between peers as cards or an album's photos: the one
 * coming and the one going slide in unison, without a fade, from the side [order] says it
 * lies on; at once with animations removed.
 */
@Composable
private fun <T> lateral(order: (T) -> Int = { it as Int }): AnimatedContentTransitionScope<T>.() -> ContentTransform {
    val spec = AppTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val size = AppTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val reduced = LocalReducedMotion.current
    return {
        if (reduced) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            val direction = if (order(targetState) >= order(initialState)) SlideDirection.Start else SlideDirection.End
            (slideIntoContainer(direction, spec) togetherWith slideOutOfContainer(direction, spec)) using
                SizeTransform(clip = true) { _, _ -> size }
        }
    }
}
