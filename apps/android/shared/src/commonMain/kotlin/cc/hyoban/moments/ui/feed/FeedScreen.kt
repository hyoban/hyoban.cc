package cc.hyoban.moments.ui.feed

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isMetaPressed
import cc.hyoban.moments.ui.components.BackHandler
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.compose.icon
import cc.hyoban.moments.ui.compose.label
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.components.MediaBeside
import cc.hyoban.moments.ui.components.sideInsetsPadding
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.layout.navigationBarsPadding
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.cardBorder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.design.edgeBlur
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.LocalTabsHidden
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.wrapContentHeight
import cc.hyoban.moments.ui.components.FloatingButton
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.components.restingElevation
import cc.hyoban.moments.ui.components.floatingElevation
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import cc.hyoban.moments.ui.components.FLOATING_BAR
import cc.hyoban.moments.ui.components.FloatingBack
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import cc.hyoban.moments.ui.design.FloatingActionButton
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.RailSpace
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.isExpandedWidth
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlin.math.roundToInt

/**
 * The timeline tab: every Moment the site shows and those written on the phone, newest first,
 * month by month, with the ledger's bills among them and a banner where each trip begins; its
 * button records a Moment or a bill. Pull down to bring them up to date.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onDay: (String) -> Unit,
    onNew: () -> Unit,
    onChanges: () -> Unit,
    onSearch: () -> Unit = {},
    onMedia: (String, Int) -> Unit = { _, _ -> },
    onPlace: (String) -> Unit = {},
    onTrip: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
) {
    // From Material 3's expanded breakpoint, its list and detail: a Moment chosen opens in a
    // pane beside the Moments rather than as a page over them. Turned upright with one chosen,
    // it goes on as its page; back clears the pane first.
    val twoPane = isExpandedWidth()
    // On a phone on its side, the conversation a column beside the tabs and the day it has
    // scrolled to on a map filling the rest (H01).
    val dayMap = isPhoneLandscape() && !twoPane
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val details = rememberSaveableStateHolder()
    val open: (String) -> Unit = { uid -> if (twoPane) chosen = uid else onMoment(uid) }
    LaunchedEffect(twoPane) {
        if (!twoPane) chosen?.let { uid ->
            chosen = null
            onMoment(uid)
        }
    }
    BackHandler(twoPane && chosen != null) { chosen = null }
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    // The changes kept on the phone, to look over and publish, head the Moments while there are any.
    val publishable = container.canPublish && pending.size > 0
    // Recording is the tab's one main action: a round ink button beside the floating tabs, or at
    // the top of the rail down the side.
    val accessory = LocalNavAccessory.current
    val currentOnNew by rememberUpdatedState(onNew)
    val newBeside = remember<@Composable () -> Unit> {
        { NewMomentButton(onClick = { currentOnNew() }, inRail = isMediumWidth()) }
    }
    DisposableEffect(accessory) {
        accessory.value = newBeside
        onDispose { if (accessory.value === newBeside) accessory.value = null }
    }
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    // On a computer, the arrows go from one Moment to the next in the pane beside the list, and
    // Return opens the one chosen as its page (D01).
    cc.hyoban.moments.ui.components.OnDesktopKey(enabled = twoPane && cc.hyoban.moments.ui.design.LocalDesktop.current) { event ->
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || event.isMetaPressed) return@OnDesktopKey false
        val index = moments.indexOfFirst { it.uid == chosen }
        when (event.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> { moments.getOrNull(index + 1)?.let { chosen = it.uid }; true }
            androidx.compose.ui.input.key.Key.DirectionUp -> { moments.getOrNull((index - 1).coerceAtLeast(0))?.let { chosen = it.uid }; true }
            androidx.compose.ui.input.key.Key.Enter -> chosen?.let { onMoment(it); true } ?: false
            else -> false
        }
    }
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    val tracks = rememberTimelineTracks(container)
    // The app counts as started once the Moments on the phone are read, as the system measures
    // a start to the screen people came for. The splash stays up until then.
    cc.hyoban.moments.ui.LocalDevice.current.TimelineReady(loaded && tracks.ready)
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val pull = rememberPulledRefresh(container)
    ShowRefreshErrors(container)
    // Everything, unfiltered: narrowing by kind and time is the search page's.
    val shown = moments
    val bills = rememberTimelineBills(container, shown, TimeFilter())
    val ledgerNav = LocalLedgerNav.current
    // A screen ahead and half one behind composed while the list is idle between frames, so a
    // Moment coming into view on a fling is ready rather than composed within the frame it shows.
    val feedState = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f))
    val gridState = rememberLazyStaggeredGridState()
    // The header over the views folds away as they scroll on and opens again as they scroll back,
    // or as the tab tapped again takes them to their top. How far it is folded is kept, so a page
    // made again (the theme switched) doesn't open it mid-list.
    val headerScroll = rememberSaveable(saver = HeaderScroll.Saver) { HeaderScroll() }
    // Opening out with the list as it goes back to its top, on the same spring.
    val headerSpec = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    OnTabReselect(Feed) { animate(headerScroll.offset, 0f, animationSpec = headerSpec) { value, _ -> headerScroll.offset = value } }
    // The rows open out as they come and fold away as they go, the Moments under them moving
    // with them, rather than the whole list jumping.
    val rowMotion = AppTheme.motionScheme
    val rowIn = expandVertically(rowMotion.defaultSpatialSpec()) + fadeIn(rowMotion.defaultEffectsSpec())
    val rowOut = shrinkVertically(rowMotion.defaultSpatialSpec()) + fadeOut(rowMotion.defaultEffectsSpec())
    // The tabs fold away down the screen as the timeline is scrolled on, and come back as it is
    // scrolled back, or when it is left.
    val tabsHidden = LocalTabsHidden.current
    DisposableEffect(tabsHidden) { onDispose { tabsHidden.value = false } }
    val tabsFold = remember(tabsHidden) {
        object : NestedScrollConnection {
            // Seen before the header and the list take any of it, so the tabs follow the finger
            // whichever of them moves.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -TABS_FOLD_STEP) tabsHidden.value = true
                else if (available.y > TABS_FOLD_STEP) tabsHidden.value = false
                return Offset.Zero
            }
        }
    }
    val header: FeedLeading.() -> Unit = {
        item(key = "title") {
            // The title as a folded top bar has it from the start: small in the middle, search a
            // circle at the end, the two going up with the Moments as they scroll on.
            Box(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.title_moments),
                    style = AppTheme.typography.body.strong,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)
                        .height(FLOATING_BAR).wrapContentHeight().semantics { heading() },
                )
                // Beside the day's map, search is that map's, in its corner.
                if (!dayMap) Box(Modifier.align(Alignment.TopEnd)) {
                    FloatingButton(AppIcons.Search, stringResource(Res.string.action_search), onSearch, tag = "search")
                }
            }
        }
        item(key = "changes") {
            AnimatedVisibility(publishable, enter = rowIn, exit = rowOut) {
                cc.hyoban.moments.ui.publish.PendingChangesRow(container, onClick = onChanges, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
            }
        }
    }

    // On a phone on its side the day's map takes the camera's side, the conversation beside the tabs
    // across from it; without a cutout, the map at the right.
    val mapFirst = dayMap && !cc.hyoban.moments.ui.components.railOnLeft()
    Row(Modifier.fillMaxSize()) {
        // Beside a detail pane, the list is a pane of its own, as wide as Material 3's list
        // pane, starting past the cutout and the rail, which it then needn't keep clear of.
        val rail = LocalRailSpace.current
        if (mapFirst) {
            TimelineDayMap(container, feedState, moments, places, bills, onDay, onSearch, Modifier.weight(1f).fillMaxHeight(), railBeside = false)
        }
        val listPane = if (twoPane) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)).padding(start = rail.left).width(LIST_PANE_WIDTH + Space.gutter * 2)
        } else if (mapFirst) {
            Modifier.padding(end = rail.right).width(CHAT_COLUMN)
        } else if (dayMap) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)).padding(start = rail.left).width(CHAT_COLUMN)
        } else {
            Modifier.weight(1f)
        }
        Box(listPane) {
            CompositionLocalProvider(LocalRailSpace provides if (twoPane || dayMap) RailSpace(0.dp, 0.dp, clearOfInsets = true) else rail) {
                // No bar: the title, its counts and search head the timeline and scroll away with
                // it, the page running up under the status bar, which a short fade of the page
                // keeps the system's icons clear on.
                val haze = rememberHazeState()
                Box(Modifier.fillMaxSize().background(AppColors.page)) {
                    Box(Modifier.fillMaxSize().hazeSource(haze).background(AppColors.page)) {
                    TabRefreshBox(pull) {
                        LoadedContent(moments.isEmpty() || !tracks.ready, loading = !loaded || syncing || !tracks.ready, message = Res.string.empty_moments) {
                            // The rows over the Moments, the changes, list or calendar and the
                            // filter, stand once above both views rather than heading each, so
                            // switching cross-fades only what changes and leaves them still.
                            // They scroll away with either and come back as it is scrolled back.
                            Column(Modifier.fillMaxSize().nestedScroll(tabsFold).nestedScroll(headerScroll.connection)) {
                                // As wide as the Moments under them and as far in, clear of the rail
                                // and the cutout as they are.
                                CollapsingHeader(headerScroll) {
                                    // The title and search as a top bar has them, across the whole width beside the
                                    // tabs, search in the corner (it keeps clear of the cutout itself); the rows under
                                    // them as wide as the Moments.
                                    val rows = FeedLeading().apply(header).rows
                                    Column {
                                        rows.filter { it.first == "title" }.forEach { (_, row) ->
                                            Box(Modifier.padding(start = LocalRailSpace.current.left, end = LocalRailSpace.current.right)) { row() }
                                        }
                                        PageWidth(maxColumns = 1) { columns ->
                                            Column(Modifier.padding(horizontal = columns.margin)) { rows.filter { it.first != "title" }.forEach { (_, row) -> row() } }
                                        }
                                    }
                                }
                                MomentFeed(
                                    shown,
                                    places,
                                    trips,
                                    container.mediaUrls,
                                    open,
                                    Modifier.weight(1f),
                                    state = feedState,
                                    gridState = gridState,
                                    tab = Feed,
                                    selected = chosen.takeIf { twoPane },
                                    onDay = onDay,
                                    bills = bills,
                                    onBill = { ledgerNav?.bill?.invoke(it) },
                                    onTrip = onTrip,
                                    tracks = tracks,
                                    chat = true,
                                )
                            }
                        }
                    }
                    }
                    // Immersive: the Moments run up under the status bar, softened there as a
                    // scroll edge, keeping its icons clear of what passes under.
                    StatusBarFade(haze)
                }
            }
        }
        if (dayMap && !mapFirst) {
            TimelineDayMap(container, feedState, moments, places, bills, onDay, onSearch, Modifier.weight(1f).fillMaxHeight(), railBeside = true)
        }
        if (twoPane) {
            // The detail pane, a container of its own beside the list, clear of the bars.
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    .padding(top = Space.s, end = rail.right + Space.gutter, bottom = Space.s)
                    .clip(AppTheme.shapes.large),
            ) {
                // One Moment giving way to another, or to the placeholder, as a fade through: the
                // pane's content changes while the pane itself stays.
                val shownUid = chosen?.takeIf { uid -> moments.any { it.uid == uid } }
                AnimatedContent(
                    shownUid,
                    Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(tween(VIEW_FADE_IN_MS, delayMillis = VIEW_FADE_OUT_MS, easing = LinearOutSlowInEasing)) togetherWith
                            fadeOut(tween(VIEW_FADE_OUT_MS, easing = FastOutLinearInEasing))
                    },
                    label = "detail",
                ) { uid ->
                    if (uid != null) {
                        // Each Moment keeps where it was scrolled to while another is shown.
                        details.SaveableStateProvider(uid) {
                            MomentScreen(
                                container,
                                uid,
                                onMedia = onMedia,
                                onPlace = onPlace,
                                onTrip = onTrip,
                                onBack = { chosen = null },
                                onEdit = onEdit,
                                onDay = onDay,
                                pane = true,
                                onChanges = onChanges,
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(LocalSurfaceLevel.current.raised.fill()).testTag("detail_placeholder"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(Res.string.label_pick_moment), color = tokens.tx2, style = AppTheme.typography.bodyReading)
                        }
                    }
                }
            }
        }
    }
}

/**
 * How wide the Moments stand beside a detail pane, Material 3's fixed pane; the margins each
 * side of them make the window's margin before it and the spacer between the panes after it.
 */
private val LIST_PANE_WIDTH = 360.dp

/**
 * How far the header over the Moments has folded away, as the views under it scroll: it goes
 * first as they scroll up, and comes back only once the view is at its top and pulled further,
 * as an app bar that exits until collapsed does.
 */
class HeaderScroll {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        // Folding away as the list is scrolled on and coming back as soon as it is scrolled back,
        // wherever it is, as the floating tabs do, rather than only at the list's top.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, move(available.y))
    }

    /** Moves it by [delta] within its height, saying how much of that it took. */
    private fun move(delta: Float): Float {
        val next = (offset + delta).coerceIn(-height, 0f)
        val taken = next - offset
        offset = next
        return taken
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<HeaderScroll, Float>(save = { it.offset }, restore = { HeaderScroll().apply { offset = it } })
    }
}

/** The scroll edge from behind the status bar to a little below it, softening the Moments that pass under. */
@Composable
private fun StatusBarFade(haze: HazeState) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = Space.l)))
            .edgeBlur(haze, top = true),
    )
}

/** [content] laid out at its own height, less as much as [scroll] has folded it away. */
@Composable
private fun CollapsingHeader(scroll: HeaderScroll, content: @Composable () -> Unit) {
    Box(
        // Not clipped: what it has folded away goes up off the top of the screen, and the search
        // circle's shadow may fall over the Moments under it.
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            scroll.height = placeable.height.toFloat()
            val shown = (placeable.height + scroll.offset.roundToInt()).coerceIn(0, placeable.height)
            layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
        },
    ) { content() }
}

/** How far a scroll has to move in one go, in pixels, to fold the tabs away or bring them back. */
private const val TABS_FOLD_STEP = 4f

// Material's fade-through timings, as switching tabs has.
private const val VIEW_FADE_OUT_MS = 90
private const val VIEW_FADE_IN_MS = 210

/**
 * Records a Moment or a bill: a round ink button, named by a tooltip and for a screen reader;
 * beside the floating tabs it has their soft shadow, [inRail] none, level with the rail.
 */
@Composable
private fun NewMomentButton(onClick: () -> Unit, inRail: Boolean = false) {
    val label = stringResource(Res.string.action_new_moment)
    Tooltip(label) {
        FloatingActionButton(
            onClick = onClick,
            elevation = if (inRail) restingElevation() else floatingElevation(),
            shape = Corners.full,
            containerColor = tokens.tx,
            contentColor = tokens.bg,
            modifier = Modifier.testTag("new_moment"),
        ) {
            // A plus, as the design's every new thing is; the pen is for changing what's there.
            Icon(AppIcons.Plus, contentDescription = label)
        }
    }
}

/**
 * One day, `YYYY-MM-DD`: its footprints on a map across the top, under the status bar, its
 * Moments as dots and its bills as rings where the footprints were when they were paid; the day
 * and its figures under it; then the day as the timeline lists it, the trip it was on first,
 * its Moments, bills and charges from the morning on. Only back floats over it.
 */
@Composable
fun DayScreen(
    container: AppContainer,
    date: String,
    onMoment: (String) -> Unit,
    onBack: () -> Unit,
    onTrip: (String) -> Unit = {},
) {
    val all by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val moments = remember(all, date) { all.filter { it.occurredAt.startsWith(date) } }
    val trip = remember(moments, trips, date) { moments.firstNotNullOfOrNull { tripOf(it, trips) } ?: trips.firstOrNull { date >= it.start && date <= it.end } }
    val ledgerNav = LocalLedgerNav.current
    val footprintsOn = LocalSettings.current.isOn(Module.FOOTPRINTS)
    // The day's own footprints, private and the phone's, read once for the page; null until read,
    // and none with the footprints turned off.
    val track by androidx.compose.runtime.produceState<List<cc.hyoban.moments.footprints.TrackPoint>?>(null, date, footprintsOn) {
        value = if (!footprintsOn) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.footprints.points(cc.hyoban.moments.footprints.TrackSelection(day = date))
        }
    }
    // Whether today is still being recorded, said after its figures; read with the footprints.
    val recording by androidx.compose.runtime.produceState(false, date, footprintsOn) {
        if (footprintsOn && date == java.time.LocalDate.now().toString()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.footprints.state().recording }
        }
    }
    // The day's bills, as the timeline has them for its month, kept to this day.
    val month = remember(date) { TimeFilter(date.take(4).toInt(), date.substring(5, 7).toInt()) }
    val monthBills = rememberTimelineBills(container, moments, month)
    val bills = remember(monthBills, date) { monthBills.onDay(date) }
    // The trip goes unsaid on the cards when all of them were on it: its row heads the day.
    val oneTrip = trip != null && moments.all { tripOf(it, trips)?.id == trip.id }

    // On a phone on its side, a day that moved has its map down the half on the camera's side, the
    // whole height, and the day beside it (H05).
    val points = track
    val sideways = isPhoneLandscape() && points != null && points.size > 1
    val feed: @Composable (Modifier) -> Unit = { feedModifier ->
        MomentFeed(
            moments, places, trips, container.mediaUrls, onMoment,
            feedModifier,
            months = false,
            showTrip = !oneTrip,
            dayPills = false,
            bills = bills,
            onBill = { ledgerNav?.bill?.invoke(it) },
            onTrip = onTrip.takeIf { trip != null },
            oldestFirst = true,
            allMoments = all,
        ) {
            item("head") { DayHead(date, track, moments, places, bills, recording, withMap = !sideways) }
        }
    }
    Box(Modifier.fillMaxSize().background(AppColors.page)) {
        if (sideways && points != null) {
            MediaBeside(media = { half -> DayMap(date, points, moments, places, bills, half, topInset = FLOATING_BAR) }) { half ->
                Box(half) {
                    CompositionLocalProvider(LocalRailSpace provides RailSpace(0.dp, 0.dp, clearOfInsets = true)) { feed(Modifier.fillMaxSize()) }
                }
            }
        } else {
            feed(Modifier)
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}

/**
 * The head of a day's page: its footprints on a map across the top, or room for back where it
 * has none, then the day and, in one line under it, how far it went, how long it moved and
 * what it spent.
 */
@Composable
private fun DayHead(
    date: String,
    /** The day's footprints; null while they are read. */
    track: List<cc.hyoban.moments.footprints.TrackPoint>?,
    moments: List<cc.hyoban.moments.data.spec.MomentRecord>,
    places: Map<String, cc.hyoban.moments.data.spec.Place>,
    bills: TimelineBills,
    /** Today, with the footprints still recording: its line ends where the phone is now. */
    recording: Boolean = false,
    /** Whether the map heads it; on a phone on its side the map stands beside the day instead. */
    withMap: Boolean = true,
) {
    val points = track.orEmpty()
    // While they are read, the timeline's word on them keeps room for the map and gives the
    // figures, so the page grows out of its card whole and nothing moves when they come.
    val known = remember(date) { knownDayTrack(date) }
    val moving = if (track == null) known != null || knownFootprints(date) else points.size > 1
    // No more than half the screen, so a phone on its side still shows the day under it.
    val height = minOf(DAY_MAP, cc.hyoban.moments.ui.components.windowHeight() / 2)
    Column(Modifier.fillMaxWidth()) {
        if (!withMap) {
            Spacer(Modifier.height(FLOATING_BAR))
        } else if (moving && track == null) {
            Spacer(Modifier.fillMaxWidth().height(height))
        } else if (moving) {
            DayMap(
                date, points, moments, places, bills,
                Modifier.fillMaxWidth().height(height),
                // Clear of the status bar and back over its top, as far as a short map can spare.
                topInset = minOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FLOATING_BAR, height / 4),
            )
        } else {
            Spacer(Modifier.statusBarsPadding().height(FLOATING_BAR))
        }
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(formatDay(date), style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
            val stats = remember(points) { cc.hyoban.moments.footprints.trackStats(points) }
            val metres = if (track == null) known?.metres else stats.metres
            val millis = if (track == null) known?.movingMillis else stats.recordedMillis
            val line = listOfNotNull(
                if (moving && metres != null && millis != null) stringResource(Res.string.day_track_stats, decimals(metres / 1000, 1), millis / 60_000) else null,
                daySpent(bills, date)?.let { stringResource(Res.string.ledger_expense_short, it) },
            ).joinToString(" ")
            // Still recording after the figures it keeps growing, in the line's own colour, as the word says it.
            val still = if (recording) stringResource(Res.string.day_still_recording) else null
            if (line.isNotEmpty() || still != null) {
                Text(
                    listOfNotNull(line.ifEmpty { null }, still).joinToString(" "),
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                )
            }
        }
    }
}

/**
 * The day's footprints on a map, and on them its Moments and check-ins by their place's kind and
 * its bills by their category, each a badge without words where it was: its place, a bill's Moment's or the
 * check-in it was paid in; else where the footprints were then, within a few minutes.
 */
@Composable
private fun DayMap(
    date: String,
    points: List<cc.hyoban.moments.footprints.TrackPoint>,
    moments: List<cc.hyoban.moments.data.spec.MomentRecord>,
    places: Map<String, cc.hyoban.moments.data.spec.Place>,
    bills: TimelineBills,
    modifier: Modifier,
    topInset: Dp,
) {
    val stretches = remember(points) {
        cc.hyoban.moments.footprints.trackSegments(points, budget = 2000).filter { it.size > 1 }.map { stretch -> stretch.map { it.longitude to it.latitude } }
    }
    val dayMoments = remember(moments, date) { moments.filter { it.occurredAt.startsWith(date) } }
    val checkins = bills.checkins[date].orEmpty()
    val dayBills = remember(bills, dayMoments, date) {
        (bills.loose[date].orEmpty() + dayMoments.flatMap { bills.linked[it.uid].orEmpty() }).distinctBy { it.id }
    }
    val marks = remember(points, dayMoments, places, checkins, dayBills) {
        fun placed(id: String?) = id?.let(places::get)?.let { cc.hyoban.moments.ui.MapPoint(it.latitude, it.longitude) }
        fun tracked(time: Long?) = time?.let { t ->
            points.minByOrNull { kotlin.math.abs(it.time - t) }?.takeIf { kotlin.math.abs(it.time - t) <= BILL_NEAR_MS }
                ?.let { cc.hyoban.moments.ui.MapPoint(it.latitude, it.longitude) }
        }
        fun millis(at: String) = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
        val byUid = dayMoments.associateBy { it.uid }
        // A Moment and a check-in show their place's kind, a bill its category, as their rows do.
        fun kindIcon(id: String?) = id?.let(places::get)?.let { cc.hyoban.moments.data.spec.PlaceKind.of(it.kind) }?.icon
        dayMoments.mapNotNull { moment ->
            val at = placed(moment.places.firstOrNull()) ?: tracked(millis(moment.occurredAt)) ?: return@mapNotNull null
            cc.hyoban.moments.ui.DayMark(at, cc.hyoban.moments.ui.DayMark.Kind.MOMENT, kindIcon(moment.places.firstOrNull()) ?: AppIcons.Pin)
        } + checkins.mapNotNull { checkin ->
            val at = placed(checkin.place) ?: checkin.span?.let { tracked((it.first + it.last) / 2) } ?: return@mapNotNull null
            cc.hyoban.moments.ui.DayMark(at, cc.hyoban.moments.ui.DayMark.Kind.CHECKIN, kindIcon(checkin.place) ?: AppIcons.Flag)
        } + dayBills.mapNotNull { txn ->
            val at = placed(txn.place)
                ?: placed(txn.moment?.let(byUid::get)?.places?.firstOrNull())
                ?: placed(checkins.filter { it.place != null && it.span?.contains(txn.time) == true }.minByOrNull { it.span!!.last - it.span!!.first }?.place)
                ?: tracked(txn.time)
                ?: return@mapNotNull null
            cc.hyoban.moments.ui.DayMark(at, cc.hyoban.moments.ui.DayMark.Kind.BILL, cc.hyoban.moments.ui.ledger.txnIcon(txn, bills.ledger))
        }
    }
    cc.hyoban.moments.ui.LocalDevice.current.DayTrackMap(stretches, marks, modifier.testTag("day_track"), topInset)
}

/** How wide the conversation stands beside the tabs on a phone on its side, the day's map beside it. */
private val CHAT_COLUMN = 360.dp

/** What the timeline's map shows: a day that has somewhere to show, its footprints and its Moments. */
private class MappedDay(val day: String, val points: List<cc.hyoban.moments.footprints.TrackPoint>, val moments: List<cc.hyoban.moments.data.spec.MomentRecord>)

/**
 * Beside the conversation on a phone on its side, the day it has scrolled to on a map: the day
 * whose date stands at or above the top of the list, its footprints, places and bills as its own
 * page has them, and a card saying when, how far and what it spent; tapped, that day's page. A day
 * with nowhere to show keeps the last one that had, faded, the card saying so. Search in its corner.
 */
@Composable
private fun TimelineDayMap(
    container: AppContainer,
    state: androidx.compose.foundation.lazy.LazyListState,
    moments: List<cc.hyoban.moments.data.spec.MomentRecord>,
    places: Map<String, cc.hyoban.moments.data.spec.Place>,
    bills: TimelineBills,
    onDay: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
    /** Whether the tabs stand down this map's far side, its card and search keeping clear of them. */
    railBeside: Boolean,
) {
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    val clearance = with(LocalDensity.current) { LocalTopClearance.current.roundToPx() }
    LaunchedEffect(state) {
        // Where each day's capsule stands in the list, as they come into view; a list that changes
        // length is numbered again.
        val heads = mutableMapOf<Int, String>()
        var count = -1
        snapshotFlow { state.layoutInfo }.collect { info ->
            if (info.totalItemsCount != count) {
                heads.clear()
                count = info.totalItemsCount
            }
            info.visibleItemsInfo.forEach { item ->
                (item.key as? String)?.takeIf { it.startsWith(DAY_HEAD) }?.let { heads[item.index] = it.removePrefix(DAY_HEAD) }
            }
            // The first thing showing under the bar, and the last capsule at or above it.
            val top = info.visibleItemsInfo.firstOrNull { it.offset + it.size > info.viewportStartOffset + clearance } ?: return@collect
            val above = heads.keys.filter { it <= top.index }.maxOrNull()
            (above?.let(heads::get) ?: heads.entries.minByOrNull { it.key }?.value)?.let { day = it }
        }
    }
    var mapped by remember { mutableStateOf<MappedDay?>(null) }
    var nowhere by remember { mutableStateOf(false) }
    val footprintsOn = LocalSettings.current.isOn(Module.FOOTPRINTS)
    LaunchedEffect(day, moments, footprintsOn) {
        val shown = day ?: return@LaunchedEffect
        val points = if (!footprintsOn) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.footprints.points(cc.hyoban.moments.footprints.TrackSelection(day = shown))
        }
        val dayMoments = moments.filter { it.occurredAt.startsWith(shown) }
        val somewhere = points.size > 1 || dayMoments.any { moment -> moment.places.any { it in places } }
        nowhere = !somewhere
        if (somewhere) mapped = MappedDay(shown, points, dayMoments)
    }
    val openDay = stringResource(Res.string.label_whole_day)
    Box(modifier.background(AppColors.page)) {
        mapped?.let { shown ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (nowhere) 0.4f else 1f }) {
                // One map that moves from day to day, framed on each day's footprints as its page frames
                // them, its lines and dots changed in place rather than a new map drawn each time.
                DayMap(shown.day, shown.points, shown.moments, places, bills, Modifier.fillMaxSize(), topInset = FLOATING_BAR)
            }
        }
        // The whole map opens the day, as its card does.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = openDay) { day?.let(onDay) }.testTag("timeline_day_map"))
        // Its card and search clear of the cutout and of the tabs when they stand down this side, the
        // map running under both.
        val controls = Modifier.matchParentSize().sideInsetsPadding().padding(end = if (railBeside) LocalRailSpace.current.right else 0.dp)
        day?.let { shown ->
            val stats = remember(mapped, nowhere) { mapped?.takeIf { !nowhere }?.let { cc.hyoban.moments.footprints.trackStats(it.points) } }
            val line = if (nowhere) {
                stringResource(Res.string.timeline_day_no_places)
            } else {
                listOfNotNull(
                    stats?.takeIf { (mapped?.points?.size ?: 0) > 1 }?.let { stringResource(Res.string.day_track_stats, decimals(it.metres / 1000, 1), (it.recordedMillis / 60_000).toInt()) },
                    daySpent(bills, shown)?.let { stringResource(Res.string.ledger_expense_short, it) },
                ).joinToString(" ")
            }
            Box(controls) { Surface(
                onClick = { onDay(shown) },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(Space.m).widthIn(max = 280.dp),
                shape = AppTheme.shapes.large,
                color = SurfaceLevel.Floating.fill(),
                border = cardBorder(),
                shadowElevation = ShadowElevation,
            ) {
                Column(Modifier.padding(horizontal = Space.m, vertical = Space.s)) {
                    Text(formatDay(shown), style = AppTheme.typography.label)
                    if (line.isNotEmpty()) Text(line, style = AppTheme.typography.caption, color = tokens.tx2)
                }
            } }
        }
        Box(controls, contentAlignment = Alignment.TopEnd) {
            FloatingButton(AppIcons.Search, stringResource(Res.string.action_search), onSearch, tag = "search")
        }
    }
}

/** How the list keys a day's capsule. */
private const val DAY_HEAD = "dayhead-"

/** How tall a day's map is, its top under the status bar. */
private val DAY_MAP = 320.dp

/** How far from a bill's time the footprints may be to put it on the map. */
private const val BILL_NEAR_MS = 10 * 60_000L

/** Something a place's page does from its top: a button over its map, or in its bar on a phone on its side. */
private class PlaceAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

/** The Moments at one place, newest first. */
@Composable
fun PlaceScreen(
    container: AppContainer,
    id: String,
    onMoment: (String) -> Unit,
    onBack: () -> Unit,
    onDay: (String) -> Unit = {},
    onMap: (String) -> Unit = {},
) {
    val all by container.shownMoments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val moments = remember(all, id) { all.filter { id in it.places } }
    // Its check-ins alone, among its Moments by their days.
    val allCheckins by container.repository.checkins.collectAsStateWithLifecycle()
    val checkins = if (LocalSettings.current.isOn(Module.CHECKIN)) allCheckins.byPlace[id].orEmpty() else emptyList()
    // Its bills among them, as the timeline has them: a Moment's on its card, the rest by their
    // days; what was spent there heads the page beside its counts.
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val ledgerOn = LocalSettings.current.isOn(Module.LEDGER)
    val currency = ledger.book?.currency ?: "CNY"
    val placeBills = remember(ledger, all, id, ledgerOn) {
        if (!ledgerOn) emptyList() else {
            val shown = ledger.books.filterNot { it.archived }.mapTo(HashSet()) { it.id }
            cc.hyoban.moments.ledger.txnsOfPlace(ledger, id, all.associateBy { it.uid }).filter { it.book in shown && it.kind in SHOWN_KINDS }
        }
    }
    val bills = remember(checkins, placeBills, moments, ledger, currency) {
        val uids = moments.mapTo(HashSet()) { it.uid }
        val (withMoment, without) = placeBills.partition { it.moment != null && it.moment in uids }
        TimelineBills(
            ledger,
            linked = withMoment.groupBy { it.moment!! }.mapValues { (_, txns) -> txns.sortedBy { it.time } },
            loose = without.groupBy { it.dateKey },
            spent = placeBills.groupBy { it.dateKey }.mapValues { (_, txns) -> cc.hyoban.moments.ledger.spendingOf(ledger, txns, currency).expense },
            currency = currency,
            checkins = checkins.groupBy { it.at.take(10) },
        )
    }
    val spent = remember(placeBills, ledger, currency) {
        placeBills.takeIf { it.isNotEmpty() }?.let { cc.hyoban.moments.ledger.spendingOf(ledger, it, currency).expense }?.takeIf { it > 0 }
    }

    val place = places[id]
    // Its name, point and kind changed in the place picker, every Moment at it staying there.
    var editing by rememberSaveable { mutableStateOf(false) }
    // On a phone on its side, the place on a map down the start half and its Moments beside it,
    // its name heading them rather than standing over the map's edge (H10).
    val sideways = isPhoneLandscape() && place != null
    // What sort of place, where it is, then how many Moments are there.
    val about = listOfNotNull(
        PlaceKind.of(place?.kind)?.let { stringResource(it.label) },
        place?.division?.label(),
    ).plus(placeCounts(moments.size, checkins.size))
        .plus(listOfNotNull(spent?.let { stringResource(Res.string.ledger_spent, cc.hyoban.moments.ui.ledger.money(it, currency)) }))
        .joinToString(" ")
    val ledgerNav = LocalLedgerNav.current
    val device = cc.hyoban.moments.ui.LocalDevice.current
    val show = rememberShowMessage()
    // On the map tab, its panel up; the way there, as from that panel; and its name, point and kind.
    val actions = if (place == null) emptyList() else listOf(
        PlaceAction(AppIcons.Map, stringResource(Res.string.action_show_on_map), "show_on_map") { onMap(id) },
        PlaceAction(AppIcons.Directions, stringResource(Res.string.action_navigate), "navigate") {
            if (!device.navigateTo(place)) show(textOf(Res.string.no_map_app))
        },
        PlaceAction(AppIcons.Pen, stringResource(Res.string.action_edit_place), "edit_place") { editing = true },
    )
    if (editing) device.EditPlace(container, id, onDismiss = { editing = false })
    // The place is the page's title, so its cards name only the other places they were at.
    val feed: @Composable (Modifier, FeedLeading.() -> Unit) -> Unit = { feedModifier, head ->
        MomentFeed(
            moments, places, trips, container.mediaUrls, onMoment, feedModifier,
            onDay = onDay, hidePlace = id, bills = bills, onBill = { ledgerNav?.bill?.invoke(it) },
            leading = head,
        )
    }
    if (sideways && place != null) {
        // On a phone on its side, the place on a map down the start half and its Moments beside it,
        // its name heading them rather than standing over the map's edge (H10).
        SubPage(
            place.name,
            onBack,
            Modifier.background(AppColors.page),
            centre = {},
            actions = {
                actions.forEach { action ->
                    Tooltip(action.label, below = true) {
                        IconButton(onClick = action.onClick, modifier = Modifier.testTag(action.tag)) { Icon(action.icon, contentDescription = action.label) }
                    }
                }
            },
        ) {
            MediaBeside(media = { half -> device.PlaceMap(place, half, FLOATING_BAR) }) { half ->
                Box(half) {
                    CompositionLocalProvider(LocalRailSpace provides RailSpace(0.dp, 0.dp, clearOfInsets = true)) {
                        feed(Modifier.fillMaxSize()) {
                            item("name") {
                                Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    Text(place.name, style = AppTheme.typography.title, modifier = Modifier.semantics { heading() })
                                    Text(about, style = AppTheme.typography.caption, color = tokens.tx2)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Where it is across the top, under the status bar, as a day's page opens on its footprints
        // (10); back and its actions float over the map, its name and counts under it.
        val height = minOf(DAY_MAP, cc.hyoban.moments.ui.components.windowHeight() / 2)
        Box(Modifier.fillMaxSize().background(AppColors.page)) {
            feed(Modifier) {
                item("head") {
                    Column(Modifier.fillMaxWidth()) {
                        if (place != null) {
                            device.PlaceMap(
                                place,
                                Modifier.fillMaxWidth().height(height).testTag("place_map"),
                                minOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FLOATING_BAR, height / 4),
                            )
                        } else {
                            Spacer(Modifier.statusBarsPadding().height(FLOATING_BAR))
                        }
                        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(place?.name ?: id, style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
                            if (about.isNotEmpty()) Text(about, style = AppTheme.typography.caption, color = tokens.tx2)
                        }
                    }
                }
            }
            FloatingBack(onBack, Modifier.align(Alignment.TopStart))
            Row(Modifier.align(Alignment.TopEnd)) {
                actions.forEach { action -> FloatingButton(action.icon, action.label, action.onClick, tag = action.tag) }
            }
        }
    }
}
