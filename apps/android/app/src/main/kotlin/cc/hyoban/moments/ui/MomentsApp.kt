package cc.hyoban.moments.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.R
import cc.hyoban.moments.Settings
import cc.hyoban.moments.ui.components.AdaptiveStatusBarIcons
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalBackSwipe
import cc.hyoban.moments.ui.components.LocalInViewer
import cc.hyoban.moments.ui.components.LocalMediaMaxHeight
import cc.hyoban.moments.ui.components.LocalNavAnimatedScope
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.LocalSharedTransitionScope
import cc.hyoban.moments.ui.components.LocalViewerShowing
import cc.hyoban.moments.ui.components.LocalViewerShownKey
import cc.hyoban.moments.ui.components.Symbols
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.components.rememberReducedMotion
import cc.hyoban.moments.ui.compose.NewMomentScreen
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.publish.ChangesScreen
import cc.hyoban.moments.ui.settings.SettingsScreen
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.TransitionMotion
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.trips.TripPlayerScreen
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
import kotlinx.coroutines.launch
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

/** A trip played back along its route. */
@Serializable
data class TripPlay(val id: String)

/** One day's Moments, by its `YYYY-MM-DD`. */
@Serializable
data class Day(val date: String)

@Serializable
data class PlaceMoments(val id: String)

@Serializable
object Preferences

/** A Moment to write, kept on the phone. */
@Serializable
object NewMoment

/** One of the phone's Moments, to change. */
@Serializable
data class EditMoment(val uid: String)

/** The changes kept on the phone, to look over and publish. */
@Serializable
object Changes

/** Searching the Moments. */
@Serializable
object Search

/**
 * A tab's icon, filled once chosen; the Moments' with a badge of how many changes wait to be
 * published while another tab is shown, as Material 3 badges a navigation item with a count
 * and hides it once its destination is open.
 */
@Composable
private fun TabIcon(tab: Tab, selected: Boolean, pending: Int) {
    val icon = if (selected) tab.selectedIcon else tab.icon
    if (tab == Tab.MOMENTS && !selected && pending > 0) {
        val waiting = pluralStringResource(R.plurals.count_changes, pending, pending)
        BadgedBox(
            badge = { Badge { Text(if (pending > 999) "999+" else pending.toString()) } },
            modifier = Modifier.semantics { contentDescription = waiting },
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

/** The three tabs at the bottom; everything else opens over them. */
private enum class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    MOMENTS(Feed, Feed::class, R.string.tab_moments, Symbols.PhotoLibraryOutlined, Symbols.PhotoLibraryFilled),
    MAP(Atlas, Atlas::class, R.string.tab_map, Symbols.MapOutlined, Symbols.MapFilled),
    TRIPS(Trips, Trips::class, R.string.tab_trips, Symbols.LuggageOutlined, Symbols.LuggageFilled),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MomentsApp(container: AppContainer, start: Any = Feed) {
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
    // Editing from a card's menu, where its page would offer it: the phone's own Moments, the
    // site's with the API's token, and none while publishing.
    val momentActions = remember(container, navController) {
        MomentActions(
            canEdit = { uid -> container.publishing.value == null && (container.repository.isLocal(uid) || container.canPublish) },
            onEdit = { navController.navigate(EditMoment(it)) },
        )
    }
    val openPlace: (String) -> Unit = { navController.navigate(PlaceMoments(it)) }

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
    // The navigation bar's height and the rail's width, as they lay themselves out, and the
    // tab's floating action button's height above the bar, for the pages to keep clear of.
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    var railWidth by remember { mutableStateOf(0.dp) }
    var fabHeight by remember { mutableStateOf(0.dp) }
    // From Material 3's medium breakpoint, a phone on its side, a tablet or an unfolded
    // foldable, the tabs stand down a side instead, as a navigation rail; a narrower window,
    // turned or not, keeps the navigation bar.
    val landscape = isLandscape()
    val railed = isMediumWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // What a tab's page keeps clear of: the system's navigation beside a rail, else the
    // navigation bar, the system's navigation under it included. A page keeps its own while it
    // comes and goes, so a tab's list doesn't shift under a card growing out of it.
    val tabClearance = if (railed) navBottom else barHeight
    val clearance = if (current == null) 0.dp else tabClearance
    // Down the leading side, as Material 3 places a navigation rail, whichever way the phone
    // is turned; the system's insets keep it clear of the camera's cutout there.
    val railLeft = LocalLayoutDirection.current == LayoutDirection.Ltr
    val rail = when {
        current == null || !railed -> RailSpace(0.dp, 0.dp)
        railLeft -> RailSpace(left = railWidth, right = 0.dp)
        else -> RailSpace(left = 0.dp, right = railWidth)
    }
    // A tab tapped twice, told to its page: back to its top, or the map to every place.
    val tabReselects = remember { MutableSharedFlow<Any>(extraBufferCapacity = 1) }
    // A tab's floating action button, which the app places: above the navigation bar at its
    // end, or at the top of the rail.
    val accessorySlot = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // Brief messages from any page, one at a time, at the foot of the screen above the tabs.
    val messages = remember { SnackbarHostState() }
    // How much a page's own controls at its foot, a floating toolbar, lift the snackbar above them.
    val snackbarLift = remember { mutableStateOf(0.dp) }
    // The theme's springs for what moves on a page; the pages themselves change on the standard ones.
    val motion = MaterialTheme.motionScheme
    val transitions = TransitionMotion
    LaunchedEffect(container, messages) {
        // Each new one takes the place of the one shown. One with an action stays, with a way to
        // close it, until it is acted on or closed, as Material 3 has an actionable snackbar
        // wait for its reader; one without goes by itself.
        container.messages.collect { message ->
            messages.currentSnackbarData?.dismiss()
            launch {
                val result = messages.showSnackbar(
                    message.text,
                    actionLabel = message.action,
                    withDismissAction = message.action != null,
                    duration = if (message.action != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.onAction() else message.onDismiss()
            }
        }
    }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // The tab's button's room above the bar. Not animated: the pages lay their lists out with
    // it, and a value changing every frame would lay out every list on the tab every frame; the
    // snackbar, which moves with it, eases on its own.
    val tabFab = if (!railed && accessorySlot.value != null) fabHeight else 0.dp

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
            if (intent.action == MainActivity.ACTION_NEW_MOMENT) {
                // A place shared in goes to the Moment being written, or a new one.
                viewer = null
                if (navController.currentDestination?.hasRoute(NewMoment::class) != true) navController.navigate(NewMoment)
            } else if (intent.data != null) {
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
        // A traversal group, so the tabs below can come first to a screen reader.
        Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
            CompositionLocalProvider(
                LocalBottomClearance provides clearance,
                LocalFabClearance provides if (current != null) tabFab else 0.dp,
                LocalTabClearance provides TabClearance(tabClearance, tabFab),
                LocalRailSpace provides rail,
                LocalNavAccessory provides accessorySlot,
                LocalMessages provides messages,
                LocalSnackbarLift provides snackbarLift,
                LocalReducedMotion provides rememberReducedMotion(),
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
                        LocalMomentActions provides momentActions,
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = start,
                            // A back swipe plays going back as it came, under the finger: a Moment
                            // shrinks into its card, a pushed page slides back.
                            predictivePopEnterTransition = { _ -> enter(forward = false, transitions) },
                            predictivePopExitTransition = { _ -> exit(forward = false, transitions) },
                            // From tab to tab the pages fade through; anything else as [enter] and [exit] say.
                            enterTransition = { enter(forward = true, transitions) },
                            exitTransition = { exit(forward = true, transitions) },
                            popEnterTransition = { enter(forward = false, transitions) },
                            popExitTransition = { exit(forward = false, transitions) },
                        ) {
                            screen<Feed>(link = "moments") {
                                FeedScreen(
                                    container,
                                    onMoment = openMoment,
                                    onDay = { navController.navigate(Day(it)) },
                                    onSettings = { navController.navigate(Preferences) },
                                    onNew = { navController.navigate(NewMoment) },
                                    onChanges = { navController.navigate(Changes) },
                                    onSearch = { navController.navigate(Search) },
                                    onMedia = openMedia,
                                    onPlace = openPlace,
                                    onTrip = openTrip,
                                    onEdit = { navController.navigate(EditMoment(it)) },
                                )
                            }
                            screen<Atlas>(link = "map") {
                                MapScreen(container, onMoment = openMoment, onPlace = openPlace, onTrip = openTrip)
                            }
                            screen<Trips>(link = "trips") {
                                TripsScreen(container, onTrip = openTrip)
                            }
                            screen<Moment>(link = "moment/{uid}") { entry ->
                                MomentScreen(
                                    container,
                                    entry.toRoute<Moment>().uid,
                                    onMedia = openMedia,
                                    onPlace = openPlace,
                                    onTrip = openTrip,
                                    onBack = back,
                                    onEdit = { navController.navigate(EditMoment(it)) },
                                    onDay = { navController.navigate(Day(it)) },
                                    onChanges = { navController.navigate(Changes) },
                                )
                            }
                            screen<Trip>(link = "trip/{id}") { entry ->
                                TripScreen(
                                    container,
                                    entry.toRoute<Trip>().id,
                                    onMoment = openMoment,
                                    onPlay = { navController.navigate(TripPlay(it)) },
                                    onBack = back,
                                )
                            }
                            screen<TripPlay>(link = "play/{id}") { entry ->
                                TripPlayerScreen(container, entry.toRoute<TripPlay>().id, onMoment = openMoment, onBack = back)
                            }
                            screen<Day>(link = "day/{date}") { entry ->
                                DayScreen(container, entry.toRoute<Day>().date, onMoment = openMoment, onBack = back)
                            }
                            screen<PlaceMoments>(link = "place/{id}") { entry ->
                                PlaceScreen(container, entry.toRoute<PlaceMoments>().id, onMoment = openMoment, onBack = back)
                            }
                            screen<Preferences>(link = "settings") {
                                SettingsScreen(container, onBack = back)
                            }
                            screen<NewMoment>(link = "new") {
                                NewMomentScreen(container, onBack = back, onSaved = back)
                            }
                            screen<EditMoment>(link = "edit/{uid}") { entry ->
                                NewMomentScreen(
                                    container,
                                    onBack = back,
                                    onSaved = back,
                                    edit = entry.toRoute<EditMoment>().uid,
                                    // Deleted, the Moment's page under the edit page goes too, when it is there.
                                    onDeleted = { if (!navController.popBackStack<Moment>(inclusive = true)) back() },
                                )
                            }
                            screen<Search>(link = "search") {
                                SearchScreen(container, onMoment = openMoment, onBack = back)
                            }
                            screen<Changes>(link = "changes") {
                                ChangesScreen(container, onMoment = openMoment, onBack = back)
                            }
                        }
                        // Kept while it leaves, so the photo has somewhere to fly back from.
                        var shown by remember { mutableStateOf(viewer) }
                        if (viewer != null) shown = viewer
                        AnimatedVisibility(viewer != null, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.defaultEffectsSpec())) {
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
            // Material 3's navigation: a navigation bar along the bottom, docked and filled, the
            // tab's floating action button above it at its end; on a phone on its side, a
            // navigation rail down the leading edge, the button at its top. Read before the
            // page, as Material 3 has assistive technology reach navigation and the FAB first.
            val selectedTab = current?.ordinal ?: 0
            val pending by container.repository.pending.collectAsStateWithLifecycle()
            val onTab: (Int) -> Unit = { index -> if (index == selectedTab) tabReselects.tryEmit(Tab.entries[index].route) else selectTab(index) }
            // A tab's button grows in from its middle, as Material 3 has a FAB enter, and from tab
            // to tab the one going shrinks away as the other grows in, rather than one becoming the other.
            val fabTransition: AnimatedContentTransitionScope<(@Composable () -> Unit)?>.() -> ContentTransform = {
                (scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec())) togetherWith
                    (scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())) using SizeTransform(clip = false)
            }
            val navigation = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            }
            AnimatedVisibility(
                current != null && viewer == null && railed,
                enter = slideInHorizontally(transitions.defaultSpatialSpec()) { if (railLeft) -it else it },
                exit = slideOutHorizontally(transitions.defaultSpatialSpec()) { if (railLeft) -it else it },
                modifier = navigation.align(if (railLeft) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight).fillMaxHeight(),
            ) {
                // The compact rail, its button at the top and the tabs centred under it, so all of
                // it fits the little height a phone on its side has.
                NavigationRail(
                    containerColor = SurfaceLevel.Page.raised.fill(),
                    modifier = Modifier.fillMaxHeight().onSizeChanged { railWidth = with(density) { it.width.toDp() } },
                    header = {
                        AnimatedContent(accessorySlot.value, transitionSpec = fabTransition, label = "rail button") { button -> button?.invoke() }
                    },
                ) {
                    Spacer(Modifier.weight(1f))
                    Tab.entries.forEachIndexed { index, tab ->
                        NavigationRailItem(
                            selected = index == selectedTab,
                            onClick = { onTab(index) },
                            icon = { TabIcon(tab, selected = index == selectedTab, pending = pending.size) },
                            label = { Text(stringResource(tab.label)) },
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            AnimatedVisibility(
                current != null && viewer == null && !railed,
                enter = slideInVertically(transitions.defaultSpatialSpec()) { it },
                exit = slideOutVertically(transitions.defaultSpatialSpec()) { it },
                modifier = navigation.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedContent(accessorySlot.value, transitionSpec = fabTransition, contentAlignment = Alignment.BottomEnd, label = "tab button") { button ->
                        if (button != null) {
                            val gutter = Space.gutter
                            Box(
                                Modifier.padding(end = gutter, bottom = gutter)
                                    .onSizeChanged { fabHeight = with(density) { it.height.toDp() } + gutter },
                            ) { button() }
                        }
                    }
                    // A level above the page, as Material 3 has the bar a container step above it.
                    ShortNavigationBar(
                        containerColor = SurfaceLevel.Page.raised.fill(),
                        modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    ) {
                        Tab.entries.forEachIndexed { index, tab ->
                            ShortNavigationBarItem(
                                selected = index == selectedTab,
                                onClick = { onTab(index) },
                                icon = { TabIcon(tab, selected = index == selectedTab, pending = pending.size) },
                                label = { Text(stringResource(tab.label)) },
                                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            // Above the navigation bar and the button over it on a tab, the system's navigation
            // elsewhere, and the keyboard while it is up.
            // Moving with the bar as it comes and goes, on its spring, rather than ahead of it;
            // read as it is placed, so it moves without the app being composed again.
            val snackbarBottom = animateDpAsState(
                if (viewer == null && current != null) clearance + tabFab else navBottom + if (viewer == null) snackbarLift.value else 0.dp,
                transitions.defaultSpatialSpec(),
                label = "snackbar",
            )
            SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).imePadding().offset { IntOffset(0, -snackbarBottom.value.roundToPx()) })
        }
    }
}

// A page pushed on, a Moment, a trip, a place, a day, comes in from the side it goes
// towards and fades in as the one under it moves off a little and fades out, Android
// 14's forward motion as FlClash plays it, on the transitions' springs: the default spatial
// one for the slide, as a page is the larger thing moving, and the effects ones for the
// fades. Going back plays it the other way. The viewer still fades, as its photo flies to
// and from its tile; between pages only cards move, so a page without one slides.
private fun pageIn(forward: Boolean, motion: MotionScheme): EnterTransition =
    slideInHorizontally(motion.defaultSpatialSpec()) { width -> if (forward) width / 4 else -width / 4 } +
        fadeIn(motion.defaultEffectsSpec())

private fun pageOut(forward: Boolean, motion: MotionScheme): ExitTransition =
    slideOutHorizontally(motion.defaultSpatialSpec()) { width -> if (forward) -width / 8 else width / 4 } +
        fadeOut(motion.fastEffectsSpec())

// While a card grows into its page or the page shrinks back, both pages stay as they are
// for as long as it takes: the card's own bounds do all the moving. Not motion of its own,
// only how long the pages wait, so a duration rather than a spring.
private const val CARD_HOLD_MS = 600
private val CARD_HOLD_IN = fadeIn(tween(CARD_HOLD_MS), initialAlpha = 1f)
private val CARD_HOLD_OUT = fadeOut(tween(CARD_HOLD_MS), targetAlpha = 1f)

/**
 * A page coming in: across tabs, out of its card or, going back, into it, else sliding;
 * going back plays it as the page came.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(forward: Boolean, motion: MotionScheme): EnterTransition =
    if (switchesTab()) FADE_THROUGH_IN
    else if (if (forward) opensCard() else closesCard()) CARD_HOLD_IN else pageIn(forward, motion)

/** The page going out, as [enter] brings the other in. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(forward: Boolean, motion: MotionScheme): ExitTransition =
    if (switchesTab()) FADE_THROUGH_OUT
    else if (if (forward) opensCard() else closesCard()) CARD_HOLD_OUT else pageOut(forward, motion)

/**
 * Where a page grows out of a card, by the page and the pages that hold its card: a
 * Moment's from a list of Moments, the calendar, the map's place panel or a trip's playback; a trip's from
 * the trips, a Moment's trip card or the map's route card; a day's from the calendar or
 * a Moment's row for the rest of its day.
 */
private val CARD_OPENINGS: Map<KClass<*>, List<KClass<*>>> = mapOf(
    Moment::class to listOf(Feed::class, Day::class, PlaceMoments::class, Trip::class, TripPlay::class, Atlas::class, Search::class),
    Trip::class to listOf(Trips::class, Moment::class, Atlas::class),
    Day::class to listOf(Feed::class, Moment::class),
)

private fun opens(page: NavBackStackEntry, from: NavBackStackEntry): Boolean =
    CARD_OPENINGS.any { (target, origins) -> page.destination.hasRoute(target) && origins.any { from.destination.hasRoute(it) } }

/** Whether a page opens from a card on the one before: the card grows into it while that page stays put. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.opensCard(): Boolean = opens(targetState, initialState)

/** Whether a page closes back into its card on the page under it. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.closesCard(): Boolean = opens(initialState, targetState)

/** Whether the page changes from one tab to another. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.switchesTab(): Boolean {
    val from = Tab.entries.indexOfFirst { initialState.destination.hasRoute(it.type) }
    val to = Tab.entries.indexOfFirst { targetState.destination.hasRoute(it.type) }
    return from >= 0 && to >= 0 && from != to
}

// From tab to tab, Material 3's top level transition, a fade through: the tabs' pages are
// unrelated, so the one going fades out quickly and only then the other fades in, growing
// a little into place, rather than sliding as if they lay side by side. Its timings are
// Material's own, as its transitions still have durations rather than springs.
private const val FADE_THROUGH_OUT_MS = 90
private const val FADE_THROUGH_IN_MS = 210
private val FADE_THROUGH_OUT = fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))
private val FADE_THROUGH_IN = fadeIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing)) +
    scaleIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing), initialScale = 0.92f)

/** Where the app reads from, for what draws without the container at hand, as the map. */
val LocalSettings = staticCompositionLocalOf<Settings> { error("No settings provided") }

/** What a Moment's card offers from its menu beyond what it holds itself: editing, when the Moment can be. */
class MomentActions(val canEdit: (String) -> Boolean, val onEdit: (String) -> Unit)

/** The app's [MomentActions], for the cards of any list; none where the app doesn't give them. */
val LocalMomentActions = staticCompositionLocalOf<MomentActions?> { null }

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

/** How much of the bottom the navigation bar covers on a tab screen, to leave clear under a list's last item. */
val LocalBottomClearance = compositionLocalOf { 0.dp }

/** How much more the tab's floating action button covers above the bar, for a list or a card to keep clear of. */
val LocalFabClearance = compositionLocalOf { 0.dp }

/** What a tab's page keeps clear of, the bar and the button over it, whichever page is shown. */
data class TabClearance(val bottom: Dp, val fab: Dp)

// Not static: they change as the bar and the button come and go, often as a page changes,
// and only what reads them need be drawn again then, not every page under them.
private val LocalTabClearance = compositionLocalOf { TabClearance(0.dp, 0.dp) }

/**
 * How much of the left and the right the tabs cover on a tab screen on a phone on its
 * side, one of them standing down one side or the other, to keep a page's content clear of.
 * A pane that has kept itself clear of the system's insets says so with [clearOfInsets].
 */
data class RailSpace(val left: Dp, val right: Dp, val clearOfInsets: Boolean = false)

val LocalRailSpace = staticCompositionLocalOf { RailSpace(0.dp, 0.dp) }

/**
 * How far a page's own controls at its foot lift the snackbar, which a page sets while it is
 * shown, as Material 3 nudges a snackbar above a toolbar rather than over it.
 */
val LocalSnackbarLift = staticCompositionLocalOf<MutableState<Dp>> { mutableStateOf(0.dp) }

/** Material 3's snackbars for the whole app, rather than toasts: one host, above the tabs. */
val LocalMessages = staticCompositionLocalOf { SnackbarHostState() }

/** Shows a message as a snackbar from a tap or a callback, the one before it making way. */
@Composable
fun rememberShowMessage(): (String) -> Unit {
    val host = LocalMessages.current
    val scope = rememberCoroutineScope()
    return remember(host, scope) {
        { text ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                host.showSnackbar(text)
            }
        }
    }
}

/** A tab screen's floating action button, which that screen fills in and clears, and the app places. */
val LocalNavAccessory = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>> { mutableStateOf(null) }


/** A destination whose screen knows its transition, so its photos can share their place with the next screen's. */
/**
 * The scheme of the app's links, `moments://moment/{uid}` and the like, opened with
 * `adb shell am start -d` as much as from anywhere else.
 */
const val DEEP_LINK_SCHEME = "moments"

/** The page a link opens, `moments://moment/{uid}` and the rest; null for one the app has no page for. */
fun routeOf(link: Uri): Any? {
    if (link.scheme != DEEP_LINK_SCHEME) return null
    val id = link.pathSegments.firstOrNull()
    return when (link.host) {
        "moments" -> Feed
        "map" -> Atlas
        "trips" -> Trips
        "settings" -> Preferences
        "new" -> NewMoment
        "edit" -> id?.let(::EditMoment)
        "changes" -> Changes
        "moment" -> id?.let(::Moment)
        "trip" -> id?.let(::Trip)
        "play" -> id?.let(::TripPlay)
        "day" -> id?.let(::Day)
        "place" -> id?.let(::PlaceMoments)
        else -> null
    }
}

private inline fun <reified T : Any> NavGraphBuilder.screen(
    link: String? = null,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(deepLinks = listOfNotNull(link?.let { navDeepLink { uriPattern = "$DEEP_LINK_SCHEME://$it" } })) { entry ->
        val tab = Tab.entries.any { entry.destination.hasRoute(it.type) }
        val room = LocalTabClearance.current
        CompositionLocalProvider(
            LocalNavAnimatedScope provides this,
            LocalBottomClearance provides if (tab) room.bottom else 0.dp,
            LocalFabClearance provides if (tab) room.fab else 0.dp,
        ) { content(entry) }
    }
}
