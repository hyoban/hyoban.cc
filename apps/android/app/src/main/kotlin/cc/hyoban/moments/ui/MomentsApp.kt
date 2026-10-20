package cc.hyoban.moments.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.FloatingNav
import cc.hyoban.moments.ui.components.NavItem
import cc.hyoban.moments.ui.theme.Space
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import cc.hyoban.moments.ui.components.LocalViewerShowing
import cc.hyoban.moments.ui.components.LocalBackSwipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import cc.hyoban.moments.ui.components.LocalInViewer
import cc.hyoban.moments.ui.components.LocalViewerShownKey
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.LocalNavAnimatedScope
import cc.hyoban.moments.ui.components.LocalSharedTransitionScope
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.trips.TripScreen
import cc.hyoban.moments.ui.trips.TripsScreen
import cc.hyoban.moments.ui.viewer.MediaViewer
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

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

    val navItems = Tab.entries.map { NavItem(stringResource(it.label), it.icon, it.selectedIcon) }
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
    // The tabs step aside while a list is scrolled down to read on, and come back on any
    // scroll up, a change of tab or a page with nothing to scroll.
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
    val clearance = if (current != null) 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp

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

    // A Surface rather than a bare background, so text without a colour of its own takes the page's on-colour in both modes.
    Surface(Modifier.fillMaxSize(), color = AppColors.page) {
    Box(Modifier.fillMaxSize().nestedScroll(hideOnScroll)) {
        CompositionLocalProvider(LocalBottomClearance provides clearance) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this,
                LocalViewerShownKey provides viewerKey.takeIf { viewer != null },
                LocalBackSwipe provides backSwipe,
                LocalViewerShowing provides viewerShowing,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Feed,
                    // A back swipe plays going back as it came, under the finger: a Moment
                    // shrinks into its card, a pushed page slides back, a tab slides over.
                    predictivePopEnterTransition = { _ -> popEnter() },
                    predictivePopExitTransition = { _ -> popExit() },
                    // From tab to tab the pages slide the way the tabs' highlight does;
                    // anything else fades as before.
                    enterTransition = {
                        tabSlide()?.let { slideIntoContainer(it, TAB_SLIDE) + fadeIn(tween(TAB_SLIDE_MS)) }
                            ?: if (opensCard()) CARD_HOLD_IN else if (sharesPhoto()) fadeIn(tween(PAGE_MS)) else pageIn(forward = true)
                    },
                    exitTransition = {
                        tabSlide()?.let { slideOutOfContainer(it, TAB_SLIDE) + fadeOut(tween(TAB_SLIDE_MS)) }
                            ?: if (opensCard()) CARD_HOLD_OUT else if (sharesPhoto()) fadeOut(tween(PAGE_MS)) else pageOut(forward = true)
                    },
                    popEnterTransition = { popEnter() },
                    popExitTransition = { popExit() },
                ) {
                    screen<Feed> {
                        FeedScreen(container, onMoment = openMoment, onMedia = openMedia, onDay = { navController.navigate(Day(it)) })
                    }
                    screen<Atlas> {
                        MapScreen(container, onMoment = openMoment, onPlace = openPlace, onTrip = openTrip)
                    }
                    screen<Trips> {
                        TripsScreen(container, onTrip = openTrip)
                    }
                    screen<Moment> { entry ->
                        MomentScreen(container, entry.toRoute<Moment>().uid, onMedia = openMedia, onPlace = openPlace, onTrip = openTrip, onBack = back)
                    }
                    screen<Trip> { entry ->
                        TripScreen(container, entry.toRoute<Trip>().id, onMoment = openMoment, onMedia = openMedia, onBack = back)
                    }
                    screen<Day> { entry ->
                        DayScreen(container, entry.toRoute<Day>().date, onMoment = openMoment, onMedia = openMedia, onBack = back)
                    }
                    screen<PlaceMoments> { entry ->
                        PlaceScreen(container, entry.toRoute<PlaceMoments>().id, onMoment = openMoment, onMedia = openMedia, onBack = back)
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
            current != null && tabsShown && viewer == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.m),
        ) {
            FloatingNav(navItems, selected = current?.ordinal ?: 0, onSelect = selectTab)
        }
    }
    }
}

private const val TAB_SLIDE_MS = 320
private val TAB_SLIDE = tween<IntOffset>(TAB_SLIDE_MS, easing = FastOutSlowInEasing)

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

/** Going back, as the page came: into its card, or sliding back, or across tabs. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    tabSlide()?.let { slideIntoContainer(it, TAB_SLIDE) + fadeIn(tween(TAB_SLIDE_MS)) }
        ?: if (closesCard()) CARD_HOLD_IN else if (sharesPhoto()) fadeIn(tween(PAGE_MS)) else pageIn(forward = false)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    tabSlide()?.let { slideOutOfContainer(it, TAB_SLIDE) + fadeOut(tween(TAB_SLIDE_MS)) }
        ?: if (closesCard()) CARD_HOLD_OUT else if (sharesPhoto()) fadeOut(tween(PAGE_MS)) else pageOut(forward = false)

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

/** How much of the bottom the floating tabs cover on a tab screen, to leave clear under a list's last item. */
val LocalBottomClearance = staticCompositionLocalOf { 0.dp }

/** A destination whose screen knows its transition, so its photos can share their place with the next screen's. */
private inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
