package cc.hyoban.moments.ui.trips

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
    val routes by container.routes.routes.collectAsStateWithLifecycle()
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

    val density = LocalDensity.current
    val landscape = isLandscape()
    var card by remember { mutableStateOf(0.dp to 0.dp) }
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + FLOATING_BAR + Space.l
    // The site's camera padding on a narrow screen: 40 at the sides, clear of the card below.
    val clear = if (landscape) listOf(40.dp, top, card.first + 40.dp, 40.dp) else listOf(40.dp, top, 40.dp, card.second + 16.dp)

    Box(Modifier.fillMaxSize().then(if (controller.playing) Modifier.keepScreenOn() else Modifier)) {
        PlaybackMap(controller, clear, Modifier.fillMaxSize())
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))

        // The whole playback's progress: the cards before at their full length, this one as far as it has played.
        val lengths = remember(playback) { playback.slides.map { TripPlayback.duration(it) ?: 0L } }
        val total = remember(lengths) { lengths.sum().coerceAtLeast(1) }
        val before = remember(lengths, index) { lengths.take(index).sum() }
        PlayerCard(
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
                .padding(Space.gutter)
                // Beside the map on its side, as Material 3 has a supporting pane at 360dp.
                .then(if (landscape) Modifier.width(360.dp + Space.gutter * 2) else Modifier.fillMaxWidth()),
            photoHeight = if (landscape) 140.dp else 220.dp,
            slide = slide,
            index = index,
        )
    }
}

/**
 * The card over the map, what is playing with the playback's progress at its foot, and the
 * floating toolbar of its controls under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerCard(
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
) {
    val count = controller.playback.slides.size
    val playing = controller.playing
    val atEnd = controller.duration() == null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        val stop = slide as? PlaybackSlide.Stop
        val swipe = with(LocalDensity.current) { SWIPE_TO_STEP.toPx() }
        val forward = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
        // A Moment's card is one touch target, growing into the Moment's page as its cards do;
        // swiped, it steps to the card before or after, as the site's photo swipe does.
        Surface(
            onClick = { stop?.let { onMoment(it.stop.moment.uid) } },
            enabled = stop != null,
            shape = MaterialTheme.shapes.extraLarge,
            color = SurfaceLevel.Floating.fill(),
            shadowElevation = Elevation.level2,
            modifier = Modifier.fillMaxWidth()
                .then(if (stop != null) Modifier.sharedCard(stop.stop.moment.uid, cardCorner = Radius.extraLarge) else Modifier)
                .pointerInput(swipe, forward) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (dragged * forward > swipe) controller.next() else if (-dragged * forward > swipe) controller.previous()
                        },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                }
                .testTag("trip_player"),
        ) {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                AnimatedContent(
                    index to slide,
                    transitionSpec = lateral { it.first },
                    label = "player card",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) { (_, shown) ->
                    when (shown) {
                        PlaybackSlide.Intro -> IntroCard(trip, controller.playback, covers, urls, photoHeight)
                        is PlaybackSlide.Day -> DayCard(shown)
                        is PlaybackSlide.Stop -> StopCard(shown, controller, urls, photoHeight)
                        PlaybackSlide.Summary -> SummaryCard(trip, controller.playback)
                    }
                }
                // The whole playback's progress along the card's foot, wavy while it plays, as
                // Material 3 has a long process feel under way, and flat once paused.
                Row(Modifier.padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    val wave by animateFloatAsState(if (playing) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "wave")
                    LinearWavyProgressIndicator(progress = progress, amplitude = { wave }, modifier = Modifier.weight(1f))
                    Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PlayerToolbar(playing, atEnd, controller::togglePlay, controller::previous, controller::next, first = index == 0, last = index == count - 1)
    }
}

/** The trip's title, its dates, days and Moments, and its covers. */
@Composable
private fun IntroCard(trip: TripRecord, playback: TripPlayback, covers: List<MomentMedia>, urls: MediaUrls, photoHeight: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.padding(horizontal = Space.xs)) {
            Text(trip.title, style = MaterialTheme.typography.headlineSmall)
            TripSubhead(trip, playback.stops.size)
        }
        // A mosaic of the covers, the first larger, as the site's intro has.
        if (covers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(photoHeight).clip(MaterialTheme.shapes.large), horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
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
        Text(stringResource(R.string.trip_day, slide.day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(formatDay(slide.dateKey), style = MaterialTheme.typography.headlineSmall)
        if (slide.places.isNotEmpty()) {
            Text(slide.places.joinToString(" → "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A Moment as the route reaches it: its photos in turn, then its words. As on the site,
 * its day and time ride beside the route's head and its places are named on the map, so
 * the card is only read out a heading of them.
 */
@Composable
private fun StopCard(slide: PlaybackSlide.Stop, controller: PlaybackController, urls: MediaUrls, photoHeight: Dp) {
    val stop = slide.stop
    val moment = stop.moment
    val photos = stop.photos
    val photo by remember(controller, slide) { derivedStateOf { controller.photo() } }
    val heading = listOfNotNull(
        stringResource(R.string.trip_day, stop.day),
        formatDay(moment.dateKey),
        formatTime(moment.occurredAt),
    ).joinToString(" · ") + " · " + stop.places.joinToString("、") { it.name }
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = heading }.testTag("player_moment_${moment.uid}"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (photos.isNotEmpty()) {
            Box(Modifier.clip(MaterialTheme.shapes.large)) {
                AnimatedContent(photo, transitionSpec = lateral(), label = "player photo") { shown ->
                    MediaStill(photos[shown], urls, Modifier.fillMaxWidth().height(photoHeight))
                }
                if (photos.size > 1) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.align(Alignment.TopEnd).padding(Space.s),
                    ) {
                        Text("${photo + 1} / ${photos.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xxs))
                    }
                }
            }
        }
        if (moment.text.isNotBlank()) {
            Text(moment.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Space.xs))
        }
    }
}

/** The trip looked back on: the site's tiles, days, Moments, places, photos and the kilometres driven. */
@Composable
private fun SummaryCard(trip: TripRecord, playback: TripPlayback) {
    Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.playback_look_back), style = MaterialTheme.typography.headlineSmall)
        Text(trip.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val days = cc.hyoban.moments.ui.format.daysBetween(trip.start, trip.end)
        val moments = playback.stops.size
        val places = playback.points.distinctBy { it.id }.size
        val photos = playback.stops.sumOf { it.photos.size }
        val km = remember(playback) { playback.route.drivenKm() }.roundToInt()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Figure(pluralStringResource(R.plurals.count_days, days, days))
            Figure(pluralStringResource(R.plurals.count_moments, moments, moments))
            Figure(pluralStringResource(R.plurals.count_places, places, places))
            Figure(pluralStringResource(R.plurals.count_photos, photos, photos))
            if (km > 0) Figure(stringResource(R.string.playback_driven, km))
        }
    }
}

/** One of what the trip came to, in the look back. */
@Composable
private fun Figure(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * The playback's controls as Material 3's floating toolbar, the page's own actions over its
 * content: a step back and on as standard icon buttons, and playing or pausing, the one
 * action emphasised, as a wide filled one between them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerToolbar(
    playing: Boolean,
    atEnd: Boolean,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        val previous = stringResource(R.string.action_previous_stop)
        Tooltip(previous) {
            IconButton(onClick = onPrevious, enabled = !first, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag("player_previous")) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = previous)
            }
        }
        // At the look back, where playback ends, it plays the trip again.
        val play = stringResource(if (atEnd && !playing) R.string.action_replay else if (playing) R.string.action_pause else R.string.action_play)
        Tooltip(play) {
            FilledIconButton(
                onClick = onPlay,
                shapes = IconButtonDefaults.shapes(),
                // Its 48dp target kept round the wide container, so the button sits on the toolbar's
                // middle rather than its target overflowing the container upward.
                modifier = Modifier.minimumInteractiveComponentSize()
                    .size(IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)).testTag("player_play"),
            ) {
                Crossfade(if (atEnd && !playing) Icons.Rounded.Replay else if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, label = "play icon") {
                    Icon(it, contentDescription = play)
                }
            }
        }
        val next = stringResource(R.string.action_next_stop)
        Tooltip(next) {
            IconButton(onClick = onNext, enabled = !last, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag("player_next")) {
                Icon(Icons.Rounded.SkipNext, contentDescription = next)
            }
        }
    }
}

/** How far a card is swiped to step to the next or the one before. */
private val SWIPE_TO_STEP = 56.dp

/**
 * Material 3's lateral transition, between peers as cards or an album's photos: the one
 * coming and the one going slide in unison, without a fade, from the side [order] says it
 * lies on; at once with animations removed.
 */
@Composable
private fun <T> lateral(order: (T) -> Int = { it as Int }): AnimatedContentTransitionScope<T>.() -> ContentTransform {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val reduced = LocalReducedMotion.current
    return {
        if (reduced) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            val direction = if (order(targetState) >= order(initialState)) SlideDirection.Start else SlideDirection.End
            slideIntoContainer(direction, spec) togetherWith slideOutOfContainer(direction, spec)
        }
    }
}
