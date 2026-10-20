package cc.hyoban.moments.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.util.Consumer
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ui.components.AdaptiveStatusBarIcons
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingNav
import cc.hyoban.moments.ui.components.LocalBackSwipe
import cc.hyoban.moments.ui.components.LocalInViewer
import cc.hyoban.moments.ui.components.LocalMediaMaxHeight
import cc.hyoban.moments.ui.components.LocalNavAnimatedScope
import cc.hyoban.moments.ui.components.LocalSharedTransitionScope
import cc.hyoban.moments.ui.components.LocalViewerShowing
import cc.hyoban.moments.ui.components.LocalViewerShownKey
import cc.hyoban.moments.ui.components.NAV_ACCESSORY_GAP
import cc.hyoban.moments.ui.components.NAV_FOLD
import cc.hyoban.moments.ui.components.NavItem
import cc.hyoban.moments.ui.components.RAIL_WIDTH
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.settings.SettingsScreen
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.trips.TripScreen
import cc.hyoban.moments.ui.trips.TripsScreen
import cc.hyoban.moments.ui.viewer.LocalInPictureInPicture
import cc.hyoban.moments.ui.viewer.MediaViewer
import cc.hyoban.moments.ui.viewer.rememberInPictureInPicture
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable

@Serializable
object Feed

@Serializable
object Atlas

@Serializable
object Trips

@Serializable
data class Moment(val uid: String)

@Serializable
data class Viewer(val uid: String, val index: Int)

/** Keeps the open viewer across a rotation, as its Moment and index. */
private val ViewerSaver = listSaver<Viewer?, Any>(
    save = { it?.let { viewer -> listOf(viewer.uid, viewer.index) }.orEmpty() },
    restore = { if (it.isEmpty()) null else Viewer(it[0] as String, it[1] as Int) },
)

@Serializable
data class Trip(val id: String)

/** One day's Moments, by its `YYYY-MM-DD`. */
@Serializable
data class Day(val date: String)

@Serializable
data class PlaceMoments(val id: String)

@Serializable
object Preferences

/** The three tabs at the bottom; everything else opens over them. */
private enum class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    MOMENTS(Feed, Feed::class, R.string.tab_moments, Icons.Outlined.PhotoLibrary, Icons.Rounded.PhotoLibrary),
    MAP(Atlas, Atlas::class, R.string.tab_map, Icons.Outlined.Map, Icons.Rounded.Map),
    TRIPS(Trips, Trips::class, R.string.tab_trips, Icons.Outlined.Luggage, Icons.Rounded.Luggage),
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MomentsApp(container: AppContainer) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.type) == true }
    val back: () -> Unit = { navController.popBackStack() }
    val openMoment: (String) -> Unit = { navController.navigate(Moment(it)) }
    // The viewer opens over the page it was opened from rather than as a page of its own,
    // so the page stays under it: a drag down to close shows it through the thinning black.
    var viewer by rememberSaveable(stateSaver = ViewerSaver) { mutableStateOf<Viewer?>(null) }
    var viewerKey by remember { mutableStateOf<String?>(null) }
    // The photo shown as the viewer is swiped through; its tile steps aside only as the
    // viewer closes, so a swipe sideways moves no tile and starts no flight.
    var shownKey by remember { mutableStateOf<String?>(null) }
    // Open, or still closing: the viewer's own photo flights run only then.
    val viewerShowing = remember { mutableStateOf(false) }
    val openMedia: (String, Int) -> Unit = { uid, index ->
        viewerKey = null
        shownKey = null
        viewerShowing.value = true
        viewer = Viewer(uid, index)
    }
    val closeViewer = {
        viewerKey = shownKey
        viewer = null
    }
    val openTrip: (String) -> Unit = { navController.navigate(Trip(it)) }
    val openPlace: (String) -> Unit = { navController.navigate(PlaceMoments(it)) }

    val navItems = Tab.entries.map { NavItem(stringResource(it.label), it.icon, it.selectedIcon, tag = "tab_${it.name.lowercase()}") }
    val selectTab: (Int) -> Unit = { index ->
        navController.navigate(Tab.entries[index].route) {
            // The tab takes the place of the one before rather than going on top of it, so
            // back on any tab leaves the app, as the system's back to home; each keeps its state.
            val bottom = navController.currentBackStack.value.firstOrNull { it.destination !is NavGraph }?.destination
            popUpTo((bottom ?: navController.graph.findStartDestination()).id) {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    // The tabs fold into a circle at the side while a list is scrolled down to read on,
    // and open out again on any scroll up, a tap on the circle, a change of tab or a page
    // with nothing to scroll.
    var tabsShown by remember { mutableStateOf(true) }
    LaunchedEffect(current) { tabsShown = true }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -SCROLL_SLOP) tabsShown = false else if (available.y > SCROLL_SLOP) tabsShown = true
                return Offset.Zero
            }
        }
    }
    // On a phone on its side the tabs stand down a side instead, where the screen has
    // width to spare and no height.
    val landscape = isLandscape()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val clearance = when {
        current == null -> 0.dp
        landscape -> navBottom
        else -> 88.dp + navBottom
    }
    // Down the side away from the camera's cutout, which changes sides as the phone turns
    // one way or the other; the status bar's icons stay beside the cutout.
    val density = LocalDensity.current
    val cutout = WindowInsets.displayCutout
    val railLeft = with(density) { cutout.getRight(this, LayoutDirection.Ltr) > cutout.getLeft(this, LayoutDirection.Ltr) }
    val rail = when {
        current == null || !landscape -> RailSpace(0.dp, 0.dp)
        railLeft -> RailSpace(left = RAIL_CLEARANCE, right = 0.dp)
        else -> RailSpace(left = 0.dp, right = RAIL_CLEARANCE)
    }
    // A tab tapped twice, told to its page: back to its top, or the map to every place.
    val tabReselects = remember { MutableSharedFlow<Any>(extraBufferCapacity = 1) }
    // A button the map hands the tabs to stand beside them, as its one action.
    val accessorySlot = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    // A back swipe, from its start until a moment after the finger lifts, while the page
    // change it started plays out: shared photos stay with their pages meanwhile.
    val backSwipe = remember { mutableStateOf(false) }
    val gestures = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(gestures) {
        gestures?.transitionState?.collectLatest { state ->
            // A finger dragging from the edge; a back key reports no progress.
            if (state is NavigationEventTransitionState.InProgress && state.latestEvent.progress > 0.01f) {
                backSwipe.value = true
            } else if (backSwipe.value) {
                delay(800)
                backSwipe.value = false
            }
        }
    }

    AdaptiveStatusBarIcons()
    val inPictureInPicture = rememberInPictureInPicture()

    // A link opened while the app is already open, `adb shell am start -d moments://…` among
    // them, goes to its page over the one shown; the viewer, if open, closes first. Its task
    // flags are dropped, as they would have the navigation start the whole task again.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { intent ->
            if (intent.data != null) {
                viewer = null
                navController.handleDeepLink(Intent(intent).setFlags(0))
            }
        }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    // A Surface rather than a bare background, so text without a colour of its own takes the page's on-colour in both modes.
    // Its test tags are resource ids too, so `uiautomator dump` names the buttons, tabs and cards it finds.
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = AppColors.page) {
        Box(Modifier.fillMaxSize().nestedScroll(hideOnScroll)) {
            CompositionLocalProvider(
                LocalBottomClearance provides clearance,
                LocalRailSpace provides rail,
                LocalNavAccessory provides accessorySlot,
                LocalMediaMaxHeight provides if (landscape) windowHeight * 0.6f else Dp.Infinity,
            ) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this,
                        LocalViewerShownKey provides viewerKey.takeIf { viewer != null },
                        LocalBackSwipe provides backSwipe,
                        LocalViewerShowing provides viewerShowing,
                        LocalSettings provides container.settings,
                        LocalTabReselects provides tabReselects,
                        LocalInPictureInPicture provides inPictureInPicture,
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Feed,
                            // A back swipe plays going back as it came, under the finger: a Moment
                            // shrinks into its card, a pushed page slides back, a tab slides over.
                            predictivePopEnterTransition = { _ -> enter(forward = false) },
                            predictivePopExitTransition = { _ -> exit(forward = false) },
                            // From tab to tab the pages slide the way the tabs' highlight does;
                            // anything else fades as before.
                            enterTransition = { enter(forward = true) },
                            exitTransition = { exit(forward = true) },
                            popEnterTransition = { enter(forward = false) },
                            popExitTransition = { exit(forward = false) },
                        ) {
                            screen<Feed>(link = "moments") {
                                FeedScreen(
                                    container,
                                    onMoment = openMoment,
                                    onMedia = openMedia,
                                    onDay = { navController.navigate(Day(it)) },
                                    onSettings = { navController.navigate(Preferences) },
                                )
                            }
                            screen<Atlas>(link = "map") {
                                MapScreen(container, onMoment = openMoment, onPlace = openPlace, onTrip = openTrip)
                            }
                            screen<Trips>(link = "trips") {
                                TripsScreen(container, onTrip = openTrip)
                            }
                            screen<Moment>(link = "moment/{uid}") { entry ->
                                MomentScreen(container, entry.toRoute<Moment>().uid, onMedia = openMedia, onPlace = openPlace, onTrip = openTrip, onBack = back)
                            }
                            screen<Trip>(link = "trip/{id}") { entry ->
                                TripScreen(container, entry.toRoute<Trip>().id, onMoment = openMoment, onMedia = openMedia, onBack = back)
                            }
                            screen<Day>(link = "day/{date}") { entry ->
                                DayScreen(container, entry.toRoute<Day>().date, onMoment = openMoment, onMedia = openMedia, onBack = back)
                            }
                            screen<PlaceMoments>(link = "place/{id}") { entry ->
                                PlaceScreen(container, entry.toRoute<PlaceMoments>().id, onMoment = openMoment, onMedia = openMedia, onBack = back)
                            }
                            screen<Preferences>(link = "settings") {
                                SettingsScreen(container, onBack = back)
                            }
                        }
                        // Kept while it leaves, so the photo has somewhere to fly back from.
                        var shown by remember { mutableStateOf(viewer) }
                        if (viewer != null) shown = viewer
                        AnimatedVisibility(viewer != null, enter = fadeIn(tween(350)), exit = fadeOut(tween(350))) {
                            val route = shown ?: return@AnimatedVisibility
                            // Gone once it has finished closing.
                            DisposableEffect(Unit) { onDispose { if (viewer == null) viewerShowing.value = false } }
                            // Registered after the pages' own, so back closes the viewer before it leaves a page.
                            BackHandler(enabled = viewer != null, onBack = closeViewer)
                            CompositionLocalProvider(LocalNavAnimatedScope provides this, LocalInViewer provides true) {
                                MediaViewer(
                                    container,
                                    route.uid,
                                    route.index,
                                    onMoment = { closeViewer(); openMoment(it) },
                                    onPlace = { closeViewer(); openPlace(it) },
                                    onTrip = { closeViewer(); openTrip(it) },
                                    onBack = closeViewer,
                                    onShown = {
                                        // The first photo's tile steps aside as the viewer opens.
                                        if (viewerKey == null || shownKey == null) viewerKey = it
                                        shownKey = it
                                    },
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                current != null && viewer == null,
                enter = fadeIn() + if (landscape) slideInHorizontally { if (railLeft) -it else it } else slideInVertically { it },
                exit = fadeOut() + if (landscape) slideOutHorizontally { if (railLeft) -it else it } else slideOutVertically { it },
                modifier = if (landscape) {
                    // In the middle of what the system bars leave, as the map's buttons above and
                    // below it are spaced.
                    Modifier.align(if (railLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
                        .windowInsetsPadding(WindowInsets.safeDrawing).absolutePadding(left = if (railLeft) Space.m else 0.dp, right = if (railLeft) 0.dp else Space.m)
                } else {
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                        .padding(start = Space.gutter, end = Space.gutter, bottom = Space.m)
                },
            ) {
                // Down the side, the tabs have room of their own, so they stay open as a list scrolls.
                val folded = !landscape && !tabsShown
                val side by animateFloatAsState(if (folded) -1f else 0f, NAV_FOLD, label = "tabs side")
                Box(if (landscape) Modifier else Modifier.fillMaxWidth()) {
                    Row(Modifier.align(BiasAlignment(side, 0f)), verticalAlignment = Alignment.CenterVertically) {
                        FloatingNav(
                            navItems,
                            selected = current?.ordinal ?: 0,
                            onSelect = selectTab,
                            vertical = landscape,
                            collapsed = folded,
                            onExpand = { tabsShown = true },
                            onReselect = { tabReselects.tryEmit(Tab.entries[it].route) },
                        )
                        // On the map, its button slides out from behind the tabs' end as they move over
                        // to make room, as the map's page slides in, and back under them as they
                        // leave; down the side the map keeps it itself. Kept while it slides away,
                        // after the map has let it go.
                        var accessory by remember { mutableStateOf(accessorySlot.value) }
                        accessorySlot.value?.let { accessory = it }
                        AnimatedVisibility(
                            current == Tab.MAP && !landscape && accessorySlot.value != null,
                            enter = expandHorizontally(TAB_SLIDE_SIZE, expandFrom = Alignment.Start, clip = false) +
                                slideInHorizontally(TAB_SLIDE) { -it } +
                                fadeIn(tween(TAB_SLIDE_MS / 2)),
                            exit = shrinkHorizontally(TAB_SLIDE_SIZE, shrinkTowards = Alignment.Start, clip = false) +
                                slideOutHorizontally(TAB_SLIDE) { -it } +
                                fadeOut(tween(TAB_SLIDE_MS / 2, delayMillis = TAB_SLIDE_MS / 2)),
                            // Under the tabs, so it comes out from behind them.
                            modifier = Modifier.zIndex(-1f),
                        ) {
                            Box(Modifier.padding(start = NAV_ACCESSORY_GAP)) { accessory?.invoke() }
                        }
                    }
                }
            }
        }
    }
}

private const val TAB_SLIDE_MS = 320
private val TAB_SLIDE = tween<IntOffset>(TAB_SLIDE_MS, easing = FastOutSlowInEasing)
private val TAB_SLIDE_SIZE = tween<IntSize>(TAB_SLIDE_MS, easing = FastOutSlowInEasing)

// A page pushed on, a Moment, a trip, a place, a day, comes in from the side it goes
// towards and fades in as the one under it moves off a little and fades out, Android
// 14's forward motion as FlClash plays it: 450ms on the emphasized curve. Going back
// plays it the other way. The viewer still fades, as its photo flies to and from its tile.
private const val PAGE_MS = 450
private val EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun pageIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(PAGE_MS, easing = EMPHASIZED)) { width -> if (forward) width / 4 else -width / 4 } +
        fadeIn(tween(PAGE_MS / 2, delayMillis = PAGE_MS / 8))

private fun pageOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(PAGE_MS, easing = EMPHASIZED)) { width -> if (forward) -width / 8 else width / 4 } +
        fadeOut(tween(PAGE_MS / 3))

// While a card grows into its page or the page shrinks back, both pages stay as they are
// for as long as it takes: the card's own bounds do all the moving.
private const val CARD_HOLD_MS = 600
private val CARD_HOLD_IN = fadeIn(tween(CARD_HOLD_MS), initialAlpha = 1f)
private val CARD_HOLD_OUT = fadeOut(tween(CARD_HOLD_MS), targetAlpha = 1f)

/**
 * A page coming in: across tabs, out of its card or, going back, into it, fading where a
 * photo flies between the pages, else sliding; going back plays it as the page came.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(forward: Boolean): EnterTransition =
    tabSlide()?.let { slideIntoContainer(it, TAB_SLIDE) + fadeIn(tween(TAB_SLIDE_MS)) }
        ?: if (if (forward) opensCard() else closesCard()) CARD_HOLD_IN else if (sharesPhoto()) fadeIn(tween(PAGE_MS)) else pageIn(forward)

/** The page going out, as [enter] brings the other in. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(forward: Boolean): ExitTransition =
    tabSlide()?.let { slideOutOfContainer(it, TAB_SLIDE) + fadeOut(tween(TAB_SLIDE_MS)) }
        ?: if (if (forward) opensCard() else closesCard()) CARD_HOLD_OUT else if (sharesPhoto()) fadeOut(tween(PAGE_MS)) else pageOut(forward)

/**
 * Where a page grows out of a card, by the page and the pages that hold its card: a
 * Moment's from a list of Moments, the calendar or the map's place panel; a trip's from
 * the trips, a Moment's trip card or the map's route card; a day's from the calendar.
 */
private val CARD_OPENINGS: Map<KClass<*>, List<KClass<*>>> = mapOf(
    Moment::class to listOf(Feed::class, Day::class, PlaceMoments::class, Trip::class, Atlas::class),
    Trip::class to listOf(Trips::class, Moment::class, Atlas::class),
    Day::class to listOf(Feed::class),
)

private fun opens(page: NavBackStackEntry, from: NavBackStackEntry): Boolean =
    CARD_OPENINGS.any { (target, origins) -> page.destination.hasRoute(target) && origins.any { from.destination.hasRoute(it) } }

/** Whether a page opens from a card on the one before: the card grows into it while that page stays put. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.opensCard(): Boolean = opens(targetState, initialState)

/** Whether a page closes back into its card on the page under it. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.closesCard(): Boolean = opens(initialState, targetState)

/**
 * Whether a photo flies between the two pages: a Moment's page opened from a list and
 * back. Those fade in place, as a photo chasing a tile that slides with its page
 * stutters and slips under the sheet.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.sharesPhoto(): Boolean =
    initialState.destination.hasRoute(Moment::class) || targetState.destination.hasRoute(Moment::class)

/** Which way the pages slide between two tabs: towards the tab chosen; null when either isn't a tab. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlide(): AnimatedContentTransitionScope.SlideDirection? {
    val from = Tab.entries.indexOfFirst { initialState.destination.hasRoute(it.type) }
    val to = Tab.entries.indexOfFirst { targetState.destination.hasRoute(it.type) }
    if (from < 0 || to < 0 || from == to) return null
    return if (to > from) AnimatedContentTransitionScope.SlideDirection.Start else AnimatedContentTransitionScope.SlideDirection.End
}

/** How far a scroll must go, in pixels a frame, to hide or bring back the tabs. */
private const val SCROLL_SLOP = 6f

/** Where the app reads from, for what draws without the container at hand, as the map. */
val LocalSettings = staticCompositionLocalOf<Settings> { error("No settings provided") }

/** The tabs tapped again while chosen, each as its route: [Feed], [Atlas] or [Trips]. */
val LocalTabReselects = staticCompositionLocalOf<SharedFlow<Any>> { MutableSharedFlow() }

/** Runs [action] each time the tab of [route] is tapped again while its page is shown; nothing without a [route]. */
@Composable
fun OnTabReselect(route: Any?, action: suspend () -> Unit) {
    val taps = LocalTabReselects.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(taps, route) {
        if (route != null) taps.collect { if (it == route) current() }
    }
}

/** How much of the bottom the floating tabs cover on a tab screen, to leave clear under a list's last item. */
val LocalBottomClearance = staticCompositionLocalOf { 0.dp }

/**
 * How much of the left and the right the tabs cover on a tab screen on a phone on its
 * side, one of them standing down one side or the other, to keep a page's content clear of.
 */
data class RailSpace(val left: Dp, val right: Dp)

val LocalRailSpace = staticCompositionLocalOf { RailSpace(0.dp, 0.dp) }

/** The button standing beside the tabs on a tab screen, which that screen fills in and clears. */
val LocalNavAccessory = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>> { mutableStateOf(null) }

/** The tabs standing down the side, with the room around them. */
private val RAIL_CLEARANCE = Space.m + RAIL_WIDTH + Space.s

/** A destination whose screen knows its transition, so its photos can share their place with the next screen's. */
/**
 * The scheme of the app's links, `moments://moment/{uid}` and the like, opened with
 * `adb shell am start -d` as much as from anywhere else.
 */
const val DEEP_LINK_SCHEME = "moments"

private inline fun <reified T : Any> NavGraphBuilder.screen(
    link: String? = null,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(deepLinks = listOfNotNull(link?.let { navDeepLink { uriPattern = "$DEEP_LINK_SCHEME://$it" } })) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
