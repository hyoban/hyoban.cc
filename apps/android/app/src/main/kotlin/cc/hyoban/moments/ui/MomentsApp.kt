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
    val openMedia: (String, Int) -> Unit = { uid, index -> navController.navigate(Viewer(uid, index)) }
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

    // A Surface rather than a bare background, so text without a colour of its own takes the page's on-colour in both modes.
    Surface(Modifier.fillMaxSize(), color = AppColors.page) {
    Box(Modifier.fillMaxSize().nestedScroll(hideOnScroll)) {
        CompositionLocalProvider(LocalBottomClearance provides clearance) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                NavHost(
                    navController = navController,
                    startDestination = Feed,
                    // A back swipe fades the pages as a tap on back does, rather than
                    // shrinking the page it leaves: a photo on its way back to its tile
                    // isn't part of that page and would hang outside it as it shrank.
                    predictivePopEnterTransition = { _ -> fadeIn(tween(700)) },
                    predictivePopExitTransition = { _ -> fadeOut(tween(700)) },
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
                    screen<Viewer> { entry ->
                        val route = entry.toRoute<Viewer>()
                        MediaViewer(
                            container,
                            route.uid,
                            route.index,
                            onMoment = openMoment,
                            onPlace = openPlace,
                            onTrip = openTrip,
                            onBack = back,
                        )
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
            }
        }
        }
        AnimatedVisibility(
            current != null && tabsShown,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = Space.m),
        ) {
            FloatingNav(navItems, selected = current?.ordinal ?: 0, onSelect = selectTab)
        }
    }
    }
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
