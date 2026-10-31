package cc.hyoban.moments.ui

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.Brush
import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.design.FloatingBar
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import cc.hyoban.moments.ui.design.AppSnackbar
import cc.hyoban.moments.ui.theme.dividerColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.animateColorAsState
import cc.hyoban.moments.ui.design.LocalContentColor
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.settings.MeRoutes
import cc.hyoban.moments.ui.settings.MeScreen
import android.net.Uri
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
import androidx.compose.foundation.layout.Row
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
import cc.hyoban.moments.ui.design.Badge
import cc.hyoban.moments.ui.design.BadgedBox
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.MotionScheme
import cc.hyoban.moments.ui.design.SnackbarDuration
import cc.hyoban.moments.ui.design.SnackbarHost
import cc.hyoban.moments.ui.design.SnackbarHostState
import cc.hyoban.moments.ui.design.SnackbarResult
import cc.hyoban.moments.ui.design.Surface
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.R
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.AdaptiveStatusBarIcons
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.DialogPane
import cc.hyoban.moments.ui.components.LocalBackSwipe
import cc.hyoban.moments.ui.components.LocalInViewer
import cc.hyoban.moments.ui.components.LocalMediaMaxHeight
import cc.hyoban.moments.ui.components.LocalNavAnimatedScope
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.LocalSharedTransitionScope
import cc.hyoban.moments.ui.components.LocalViewerShowing
import cc.hyoban.moments.ui.components.LocalViewerShownKey
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.dayRowKey
import cc.hyoban.moments.ui.components.dayTrackKey
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.components.isExpandedWidth
import cc.hyoban.moments.ui.components.rememberReducedMotion
import cc.hyoban.moments.ui.compose.NewMomentScreen
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.map.MapFocus
import cc.hyoban.moments.ui.map.MapScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.publish.ChangesScreen
import cc.hyoban.moments.ui.settings.SyncScreen
import cc.hyoban.moments.footprints.FootprintSettingsScreen
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.ledger.AccountEditScreen
import cc.hyoban.moments.ui.ledger.AccountScreen
import cc.hyoban.moments.ui.ledger.BillScreen
import cc.hyoban.moments.ui.ledger.EntryScreen
import cc.hyoban.moments.ui.ledger.LedgerEntryArgs
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LedgerListScreen
import cc.hyoban.moments.ui.ledger.LedgerNav
import cc.hyoban.moments.ui.ledger.LedgerScreen
import cc.hyoban.moments.ui.ledger.LedgerSettingsScreen
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.TransitionMotion
import cc.hyoban.moments.ui.trips.TripPlayerScreen
import cc.hyoban.moments.ui.trips.TripScreen
import cc.hyoban.moments.ui.viewer.LocalInPictureInPicture
import cc.hyoban.moments.ui.viewer.MediaViewer
import cc.hyoban.moments.ui.viewer.rememberInPictureInPicture
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object Feed

@Serializable
object Atlas


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

/** A trip's name, days and who sees it, to change. */
@Serializable
data class TripEdit(val id: String)

/** A trip played back along its route. */
@Serializable
data class TripPlay(val id: String)

/**
 * One day's Moments, by its `YYYY-MM-DD`, and the key of the card it grows out of: a calendar
 * day, a day's pill or a Moment's row for it. Opened from elsewhere, a menu, it slides in.
 */
@Serializable
data class Day(val date: String, val card: String? = null)

@Serializable
data class PlaceMoments(val id: String)

/** The 我 tab: the changes waiting to be published, the ledger's month and the settings, each a page under it. */
@Serializable
object Preferences

/** 同步与来源: the API, its token, the photos' origin and the map's styles. */
@Serializable
object SyncSettings

/** How footprints are recorded, a page under 我. */
@Serializable
object FootprintSettings

/** The physical side key and the action assigned to each gesture. */
@Serializable
object SideKeySettings

/** Who sees what by default: the Moments about tech, the books' new bills and the public spans of the footprints. */
@Serializable
object Items

/** A Moment to write, kept on the phone, with a bill or the [bill] alone. */
@Serializable
data class NewMoment(val bill: Boolean = false)

/** One of the phone's Moments, to change. */
@Serializable
data class EditMoment(val uid: String)

/**
 * A Moment to write, or one of [uid] to change, opened from the medium breakpoint on: a basic
 * dialog over the page it was opened from, as Material 3 keeps full-screen dialogs for compact
 * windows, where [NewMoment] and [EditMoment] are pages of their own.
 */
@Serializable
data class EditorDialog(val uid: String? = null, val bill: Boolean = false)

/** The changes kept on the phone, to look over and publish. */
@Serializable
object Changes

/** Searching the Moments. */
@Serializable
object Search

/** The ledger, opened from 我 on its [tab]: 0 the bills, 1 the sums, 2 the accounts. */
@Serializable
data class LedgerHome(val tab: Int = 0)

/** A bill to write, or one of [edit] to change; what a new one starts with, as [LedgerEntryArgs] has it. */
@Serializable
data class LedgerEntry(
    val edit: String? = null,
    val kind: String? = null,
    val related: String? = null,
    val moment: String? = null,
    val date: String? = null,
    val account: String? = null,
    val trip: String? = null,
    /** A payment's, read by 自动记账: its cents, epoch milliseconds, note and category. */
    val amount: String? = null,
    val time: String? = null,
    val note: String? = null,
    val category: String? = null,
)

/** One bill of the ledger. */
@Serializable
data class LedgerBill(val id: String)

/** One account of the ledger and its bills. */
@Serializable
data class LedgerAccount(val id: String)

/** An account to add, or one of [id] to change. */
@Serializable
data class LedgerAccountEdit(val id: String? = null)

/** The ledger's settings; with [import], its iCost import's file picker opened at once. */
@Serializable
data class LedgerSettings(val import: Boolean = false)

/** Bills picked as [LedgerFilter] has it. */
@Serializable
data class LedgerList(
    val title: String? = null,
    val book: String? = null,
    val account: String? = null,
    val category: String? = null,
    val categoryName: String? = null,
    val trip: String? = null,
    val place: String? = null,
    val day: String? = null,
    val period: String? = null,
    val search: Boolean = false,
)

/**
 * A tab's icon, filled once chosen; 我's with a dot in the accent while changes wait to be
 * published and another tab is shown, read out as how many.
 */
@Composable
private fun TabIcon(tab: Tab, selected: Boolean, pending: Int) {
    val icon = if (selected) tab.selectedIcon else tab.icon
    if (tab == Tab.ME && !selected && pending > 0) {
        val waiting = pluralStringResource(R.plurals.count_changes, pending, pending)
        BadgedBox(
            badge = { Badge(containerColor = tokens.accent) },
            modifier = Modifier.semantics { contentDescription = waiting },
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

/**
 * The tabs, floating over the page at its foot: a capsule on the surface with its hairline and
 * a soft shadow, each tab its icon alone, named for a screen reader, the chosen one filled and
 * in the text colour, the rest outlined in the faintest.
 */
@Composable
private fun TabBar(selected: Int, pending: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    FloatingBar(modifier.selectableGroup()) {
        Tab.entries.forEachIndexed { index, tab -> TabItem(tab, index == selected, pending, Modifier.size(64.dp, 48.dp)) { onTab(index) } }
    }
}

/** One tab, on the bar or the rail: its icon alone, named, filled and in the text colour once chosen, outlined in the faintest otherwise. */
@Composable
private fun TabItem(tab: Tab, chosen: Boolean, pending: Int, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (chosen) tokens.tx else tokens.tx3, AppTheme.motionScheme.fastEffectsSpec(), label = "tab")
    val name = stringResource(tab.label)
    Box(
        modifier
            .clip(Corners.full)
            .selectable(chosen, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = name }
            .testTag("tab_${tab.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { TabIcon(tab, selected = chosen, pending = pending) }
    }
}

/** How wide the rail is, down the side of a wide window. */
private val RAIL_WIDTH = 80.dp

/** The tabs at the bottom; everything else opens over them. */
private enum class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    MOMENTS(Feed, Feed::class, R.string.tab_moments, AppIcons.Timeline, AppIcons.TimelineFill),
    MAP(Atlas, Atlas::class, R.string.tab_map, AppIcons.Map, AppIcons.MapFill),
    ME(Preferences, Preferences::class, R.string.tab_me, AppIcons.Me, AppIcons.MeFill),
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MomentsApp(container: AppContainer, start: Any = Feed) {
    val navController = rememberNavController()
    // The page shown, under a dialog over it, as the editor is on a wide window: the tabs stay
    // with that page rather than going as the dialog opens.
    val stack by navController.backStack.collectAsStateWithLifecycle()
    val entry = stack.lastOrNull { it.destination !is NavGraph && it.destination.navigatorName != DIALOG_NAVIGATOR }
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.type) == true }
    // Writing or changing a Moment: a page on a compact window, a dialog from the medium breakpoint on.
    val wide by rememberUpdatedState(isMediumWidth())
    val openEditor: (String?, Boolean) -> Unit = { uid, bill ->
        navController.navigate(
            when {
                wide -> EditorDialog(uid, bill)
                uid != null -> EditMoment(uid)
                else -> NewMoment(bill)
            },
        )
    }
    val back: () -> Unit = { navController.popBackStack() }
    val openMoment: (String) -> Unit = { navController.goTo(Moment(it)) }
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
    val openTrip: (String) -> Unit = { navController.goTo(Trip(it)) }
    // Editing from a card's menu, where its page would offer it: the phone's own Moments, the
    // site's with the API's token, and none while publishing;
    // and where it leads, its places, its trip and its day, from any card's menu.
    val momentActions = remember(container, navController) {
        MomentActions(
            canEdit = { uid -> container.publishing.value == null && (container.repository.isLocal(uid) || container.canPublish) },
            onEdit = { openEditor(it, false) },
            places = { container.repository.places.value },
            tripOf = { tripOf(it, container.repository.trips.value) },
            onPlace = { navController.goTo(PlaceMoments(it)) },
            onTrip = { navController.goTo(Trip(it)) },
            onDay = { navController.goTo(Day(it)) },
            isHere = { route -> navController.currentBackStackEntry?.isOf(route) == true },
        )
    }
    val openPlace: (String) -> Unit = { navController.goTo(PlaceMoments(it)) }
    // A day out of its calendar day or its pill, or out of a Moment's row for it.
    val openDay: (String) -> Unit = { navController.goTo(Day(it, dayCardKey(it))) }
    val openDayRow: (String) -> Unit = { navController.goTo(Day(it, dayRowKey(it))) }
    val openDayTrack: (String) -> Unit = { navController.goTo(Day(it, dayTrackKey(it))) }

    // Where the ledger's pages lead, to each other and to the Moments' own.
    val ledgerNav = remember(navController) {
        LedgerNav(
            bill = { navController.goTo(LedgerBill(it)) },
            entry = { args ->
                // A bill of its own is written where a Moment can be written with it.
                if (args == LedgerEntryArgs()) openEditor(null, true) else navController.navigate(args.route())
            },
            account = { navController.goTo(LedgerAccount(it)) },
            editAccount = { navController.navigate(LedgerAccountEdit(it)) },
            list = { f -> navController.navigate(LedgerList(f.title, f.book, f.account, f.category, f.categoryName, f.trip, f.place, f.day, f.period, f.search)) },
            moment = { navController.goTo(Moment(it)) },
            place = { navController.goTo(PlaceMoments(it)) },
            trip = { navController.goTo(Trip(it)) },
            day = { navController.goTo(Day(it)) },
            back = { navController.popBackStack() },
        )
    }

    // A place or a trip shown on the map tab from its page, until the map has flown to it.
    var mapFocus by remember { mutableStateOf<MapFocus?>(null) }
    val selectTab: (Int) -> Unit = { index ->
        navController.navigate(Tab.entries[index].route) {
            // The tab takes the place of the one before rather than going on top of it, so
            // back on any tab leaves the app, as the system's back to home; each keeps its state.
            val bottom = navController.backStack.value.firstOrNull { it.destination !is NavGraph }?.destination
            popUpTo((bottom ?: navController.graph.findStartDestination()).id) {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    val showOnMap: (MapFocus) -> Unit = { focus ->
        mapFocus = focus
        // From a page pushed on the map tab itself, back down to the map: switching to the tab
        // would only bring its pages back as they were, this one on top.
        val bottom = navController.backStack.value.firstOrNull { it.destination !is NavGraph }
        if (bottom?.destination?.hasRoute(Atlas::class) == true) {
            navController.popBackStack<Atlas>(inclusive = false)
        } else {
            selectTab(Tab.MAP.ordinal)
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
    // The tabs stand down a rail from the expanded breakpoint on, as the design has it (L01), and
    // on a phone on its side, too short for the floating bar; otherwise they float at the foot.
    val railed = isExpandedWidth() || cc.hyoban.moments.ui.components.isLandscape()
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
    val motion = AppTheme.motionScheme
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
    val tabsHidden = remember { mutableStateOf(false) }

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
    // them, goes to its page over the one shown, so back leads to that one; the viewer, if
    // open, closes first. Not through the navigation's own deep links, which leave the page
    // alone on the back stack, with nothing for its back button to go to.
    val activity = LocalActivity.current as? MainActivity
    LaunchedEffect(activity, navController) {
        activity?.newIntents?.receiveAsFlow()?.collect { intent ->
            if (intent.action == MainActivity.ACTION_NEW_MOMENT) {
                // A place shared in goes to the Moment being written, or a new one.
                viewer = null
                val writing = navController.currentBackStackEntry?.let { shown ->
                    shown.destination.hasRoute(NewMoment::class) || (shown.destination.hasRoute(EditorDialog::class) && shown.toRoute<EditorDialog>().uid == null)
                } == true
                if (!writing) openEditor(null, false)
            } else if (intent.action == MainActivity.ACTION_LEDGER_IMPORT) {
                // An iCost export shared in, imported from the ledger's settings, which take it from the container.
                viewer = null
                if (navController.currentBackStackEntry?.destination?.hasRoute(LedgerSettings::class) != true) navController.navigate(LedgerSettings())
            } else {
                val route = intent.data?.let(::routeOf) ?: return@collect
                viewer = null
                val tab = Tab.entries.indexOfFirst { it.route == route }
                when {
                    tab >= 0 -> selectTab(tab)
                    route is NewMoment -> openEditor(null, route.bill)
                    route is EditMoment -> openEditor(route.uid, false)
                    else -> navController.goTo(route)
                }
            }
        }
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
                LocalTabsHidden provides tabsHidden,
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
                        cc.hyoban.moments.footprints.LocalBillPlaces provides container.billPlaces,
                        LocalTabReselects provides tabReselects,
                        LocalInPictureInPicture provides inPictureInPicture,
                        LocalMomentActions provides momentActions,
                        LocalNavController provides navController,
                        LocalLedgerNav provides ledgerNav,
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
                                    onDay = openDay,
                                    onDayRow = openDayRow,
                                    onTrackDay = openDayTrack,
                                    onNew = { openEditor(null, false) },
                                    onChanges = { navController.navigate(Changes) },
                                    onSearch = { navController.navigate(Search) },
                                    onMedia = openMedia,
                                    onPlace = openPlace,
                                    onTrip = openTrip,
                                    onEdit = { openEditor(it, false) },
                                )
                            }
                            screen<Atlas>(link = "map", aliases = listOf("footprints")) {
                                MapScreen(
                                    container,
                                    onMoment = openMoment,
                                    onPlace = openPlace,
                                    onTrip = openTrip,
                                    focus = mapFocus,
                                    onFocused = { mapFocus = null },
                                    onFootprintSettings = { navController.navigate(FootprintSettings) },
                                )
                            }
                            screen<Moment>(link = "moment/{uid}") { entry ->
                                MomentScreen(
                                    container,
                                    entry.toRoute<Moment>().uid,
                                    onMedia = openMedia,
                                    onPlace = openPlace,
                                    onTrip = openTrip,
                                    onBack = back,
                                    onEdit = { openEditor(it, false) },
                                    onDay = openDayRow,
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
                                    onDay = openDay,
                                    onMap = { showOnMap(MapFocus.Trip(it)) },
                                    onEdit = { navController.navigate(TripEdit(it)) },
                                )
                            }
                            screen<TripEdit>(link = "trip-edit/{id}") { entry ->
                                cc.hyoban.moments.ui.trips.TripEditScreen(container, entry.toRoute<TripEdit>().id, onBack = back)
                            }
                            screen<TripPlay>(link = "play/{id}") { entry ->
                                TripPlayerScreen(container, entry.toRoute<TripPlay>().id, onMoment = openMoment, onBack = back)
                            }
                            screen<Day>(link = "day/{date}") { entry ->
                                val day = entry.toRoute<Day>()
                                DayScreen(container, day.date, onMoment = openMoment, onBack = back, onTrip = openTrip, card = day.card)
                            }
                            screen<PlaceMoments>(link = "place/{id}") { entry ->
                                PlaceScreen(
                                    container,
                                    entry.toRoute<PlaceMoments>().id,
                                    onMoment = openMoment,
                                    onBack = back,
                                    onDay = openDay,
                                    onMap = { showOnMap(MapFocus.Place(it)) },
                                )
                            }
                            screen<Preferences>(link = "settings") {
                                MeScreen(
                                    container,
                                    MeRoutes(
                                        changes = { navController.navigate(Changes) },
                                        ledger = { navController.goTo(LedgerHome(it)) },
                                        footprints = { showOnMap(MapFocus.Footprints) },
                                        items = { navController.navigate(Items) },
                                        sync = { navController.navigate(SyncSettings) },
                                        ledgerSettings = { navController.navigate(LedgerSettings()) },
                                        footprintSettings = { navController.navigate(FootprintSettings) },
                                        sideKey = { navController.navigate(SideKeySettings) },
                                    ),
                                )
                            }
                            screen<SyncSettings>(link = "sync") {
                                SyncScreen(container, onBack = back)
                            }
                            screen<SideKeySettings>(link = "side-key") {
                                cc.hyoban.moments.sidekey.SideKeySettingsScreen(onBack = back)
                            }
                            screen<Items>(link = "items") {
                                cc.hyoban.moments.ui.settings.ItemsScreen(container, onBack = back)
                            }
                            screen<FootprintSettings>(link = "footprint-settings") {
                                FootprintSettingsScreen(container, onBack = back)
                            }
                            screen<NewMoment>(link = "new") { entry ->
                                NewMomentScreen(container, onBack = back, onSaved = back, startWithBill = entry.toRoute<NewMoment>().bill)
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
                            dialog<EditorDialog>(
                                // Its window edge to edge, as the pages are; a tap on the scrim doesn't throw away
                                // what is written, which only its X or back, with their question, do.
                                dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
                            ) { entry ->
                                val route = entry.toRoute<EditorDialog>()
                                val edit = route.uid
                                CompositionLocalProvider(LocalRailSpace provides RailSpace(0.dp, 0.dp, clearOfInsets = isMediumWidth())) {
                                    DialogPane {
                                        NewMomentScreen(
                                            container,
                                            onBack = back,
                                            onSaved = back,
                                            edit = edit,
                                            onDeleted = { if (!navController.popBackStack<Moment>(inclusive = true)) back() },
                                            startWithBill = route.bill,
                                        )
                                    }
                                }
                            }
                            screen<Search>(link = "search") {
                                SearchScreen(container, onMoment = openMoment, onBack = back, onBill = { ledgerNav.bill(it) })
                            }
                            screen<Changes>(link = "changes") {
                                ChangesScreen(container, onMoment = openMoment, onBack = back)
                            }
                            screen<LedgerHome>(link = "ledger") { entry ->
                                LedgerScreen(container, ledgerNav, startTab = entry.toRoute<LedgerHome>().tab)
                            }
                            // A payment's from 自动记账's notification opening the app, with what it read.
                            screen<LedgerEntry>(link = "new-bill", aliases = listOf(PAYMENT_LINK)) { entry ->
                                val route = entry.toRoute<LedgerEntry>()
                                // The link opening the app: a bill of its own, written where a Moment can be too.
                                if (route == LedgerEntry()) {
                                    NewMomentScreen(container, onBack = back, onSaved = back, startWithBill = true)
                                    return@screen
                                }
                                EntryScreen(
                                    container,
                                    LedgerEntryArgs(
                                        route.edit, route.kind?.let(TxnKind::of), route.related, route.moment, route.date, route.account, route.trip,
                                        route.amount?.toLongOrNull(), route.time?.toLongOrNull(), route.note, route.category,
                                    ),
                                    ledgerNav,
                                )
                            }
                            screen<LedgerBill>(link = "bill/{id}") { entry ->
                                BillScreen(container, entry.toRoute<LedgerBill>().id, ledgerNav)
                            }
                            screen<LedgerAccount>(link = "account/{id}") { entry ->
                                AccountScreen(container, entry.toRoute<LedgerAccount>().id, ledgerNav)
                            }
                            screen<LedgerAccountEdit> { entry ->
                                AccountEditScreen(container, entry.toRoute<LedgerAccountEdit>().id, ledgerNav)
                            }
                            screen<LedgerSettings>(link = "ledger-settings") { entry ->
                                LedgerSettingsScreen(container, ledgerNav, startImport = entry.toRoute<LedgerSettings>().import)
                            }
                            screen<LedgerList> { entry ->
                                val route = entry.toRoute<LedgerList>()
                                LedgerListScreen(
                                    container,
                                    LedgerFilter(route.title, route.book, route.account, route.category, route.categoryName, route.trip, route.place, route.day, route.period, route.search),
                                    ledgerNav,
                                )
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
                                    onDay = { closeViewer(); navController.goTo(Day(it)) },
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
            val railLine = dividerColor()
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
                // it fits the little height a phone on its side has; on the page, a hairline along
                // its inner edge.
                Column(
                    Modifier.fillMaxHeight().width(RAIL_WIDTH).background(AppColors.page)
                        .onSizeChanged { railWidth = with(density) { it.width.toDp() } }
                        .drawBehind {
                            val x = if (railLeft) size.width - 0.5.dp.toPx() else 0.5.dp.toPx()
                            drawLine(railLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                        .padding(top = Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedContent(accessorySlot.value, transitionSpec = fabTransition, label = "rail button") { button -> button?.invoke() }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.selectableGroup()) {
                        Tab.entries.forEachIndexed { index, tab -> TabItem(tab, index == selectedTab, pending.size, Modifier.width(RAIL_WIDTH).height(56.dp)) { onTab(index) } }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            AnimatedVisibility(
                current != null && viewer == null && !railed && !tabsHidden.value,
                enter = slideInVertically(transitions.defaultSpatialSpec()) { it },
                exit = slideOutVertically(transitions.defaultSpatialSpec()) { it },
                modifier = navigation.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                // The tabs and the tab's button beside them, floating over the page together in the
                // middle, over a fade of the page from clear to whole, which the page passes under and
                // which comes and goes with them.
                val page = AppColors.page
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.7f to page))
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                        .navigationBarsPadding()
                        .padding(start = Space.gutter, end = Space.gutter, bottom = Space.s, top = Space.s),
                ) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        TabBar(selectedTab, pending.size, onTab)
                        AnimatedContent(accessorySlot.value, transitionSpec = fabTransition, contentAlignment = Alignment.CenterStart, label = "tab button") { button ->
                            if (button != null) Box(Modifier.padding(start = Space.m)) { button() }
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
            SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).imePadding().offset { IntOffset(0, -snackbarBottom.value.roundToPx()) }) { AppSnackbar(it) }
        }
    }
}

// A page pushed on, a Moment, a trip, a place, a day, comes in from the side it goes
// towards and fades in as the one under it moves off a little and fades out, Android
// 14's forward motion, on the transitions' springs: the default spatial
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
    if (holdsForCard(forward)) CARD_HOLD_IN
    else if (switchesTab(forward)) FADE_THROUGH_IN
    else pageIn(forward, motion)

/** The page going out, as [enter] brings the other in. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(forward: Boolean, motion: MotionScheme): ExitTransition =
    if (holdsForCard(forward)) CARD_HOLD_OUT
    else if (switchesTab(forward)) FADE_THROUGH_OUT
    else pageOut(forward, motion)

/**
 * Whether both pages hold while a card grows into its page or the page shrinks back: as
 * the page opens or goes back, or as a page goes on to another tab holding its card, a
 * trip's page to the map with the trip's route card up, closing into it as going back would.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.holdsForCard(forward: Boolean): Boolean =
    if (forward) opensCard() || (switchesTab(forward) && closesCard()) else closesCard()

/**
 * Where a page grows out of a card, by the page and the pages that hold its card: a
 * Moment's from a list of Moments, the calendar, the map's place panel or a trip's playback; a trip's from
 * the timeline's trip banner, the map's sheet of trips, a Moment's trip card or the map's route card. A day's grows out of the card its
 * route names, wherever that is: a calendar day, a day's pill or a Moment's row for it.
 */
private val CARD_OPENINGS: Map<KClass<*>, List<KClass<*>>> = mapOf(
    Moment::class to listOf(Feed::class, Day::class, PlaceMoments::class, Trip::class, TripPlay::class, Atlas::class, Search::class),
    Trip::class to listOf(Feed::class, Moment::class, Atlas::class),
)

private fun opens(page: NavBackStackEntry, from: NavBackStackEntry): Boolean =
    if (page.destination.hasRoute(Day::class)) {
        page.toRoute<Day>().card != null
    } else {
        CARD_OPENINGS.any { (target, origins) -> page.destination.hasRoute(target) && origins.any { from.destination.hasRoute(it) } }
    }

/** Whether a page opens from a card on the one before: the card grows into it while that page stays put. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.opensCard(): Boolean = opens(targetState, initialState)

/** Whether a page closes back into its card on the page under it. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.closesCard(): Boolean = opens(initialState, targetState)

/**
 * Whether the page changes from one tab to another: from one tab's page to another's, or,
 * [forward], from a page pushed on a tab to another tab, as a place's page to the map.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.switchesTab(forward: Boolean): Boolean {
    val from = Tab.entries.indexOfFirst { initialState.destination.hasRoute(it.type) }
    val to = Tab.entries.indexOfFirst { targetState.destination.hasRoute(it.type) }
    return to >= 0 && from != to && (from >= 0 || forward)
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

/**
 * What a Moment's card offers from its menu beyond what it holds itself: editing, when the
 * Moment can be, and going to its places, its trip and its day, read from the app's [places]
 * and [tripOf].
 */
class MomentActions(
    val canEdit: (String) -> Boolean,
    val onEdit: (String) -> Unit,
    val places: () -> Map<String, Place>,
    val tripOf: (MomentRecord) -> TripRecord?,
    val onPlace: (String) -> Unit,
    val onTrip: (String) -> Unit,
    val onDay: (String) -> Unit,
    /** Whether a route is the page shown, which the menu needn't lead to. */
    val isHere: (Any) -> Boolean,
)

/** The app's [MomentActions], for the cards of any list; none where the app doesn't give them. */
val LocalMomentActions = staticCompositionLocalOf<MomentActions?> { null }

/** The tabs tapped again while chosen, each as its route: [Feed], [Atlas] or [Preferences]. */
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

/** Whether a tab's page has folded the floating tabs away as it scrolls on; the app brings them back as the page is left. */
val LocalTabsHidden = staticCompositionLocalOf { mutableStateOf(false) }

/** A tab screen's floating action button, which that screen fills in and clears, and the app places. */
val LocalNavAccessory = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>> { mutableStateOf(null) }


/**
 * Goes to [route] as a page over the one shown, or, when it is the page just under it, back
 * to that, a Moment's trip from the trip's page closing into its card there rather than
 * opening the trip again on top; and nowhere when it is the page shown, from the viewer over it.
 */
private fun NavController.goTo(route: Any) {
    when {
        currentBackStackEntry?.isOf(route) == true -> Unit
        previousBackStackEntry?.isOf(route) == true -> popBackStack()
        else -> navigate(route)
    }
}

/** Whether this is the page of [route]: the same Moment, trip, place or day, whatever card a day grew from. */
private fun NavBackStackEntry.isOf(route: Any): Boolean = when (route) {
    is Moment -> destination.hasRoute(Moment::class) && toRoute<Moment>() == route
    is Trip -> destination.hasRoute(Trip::class) && toRoute<Trip>() == route
    is PlaceMoments -> destination.hasRoute(PlaceMoments::class) && toRoute<PlaceMoments>() == route
    is Day -> destination.hasRoute(Day::class) && toRoute<Day>().date == route.date
    else -> false
}

/** The navigator name of a dialog destination, which shows over the page under it rather than in its place. */
private const val DIALOG_NAVIGATOR = "dialog"

/**
 * The scheme of the app's links, `moments://moment/{uid}` and the like, opened with
 * `adb shell am start -d` as much as from anywhere else.
 */
const val DEEP_LINK_SCHEME = "moments"

/** 自动记账's link to a payment's bill, what it read in its query, as `ledger/AutoBill` writes it. */
private const val PAYMENT_LINK = "payment?amount={amount}&time={time}&note={note}&account={account}&category={category}"

/** A bill's form for [this], as a route. */
private fun LedgerEntryArgs.route() = LedgerEntry(
    edit, kind?.value, related, moment, date, account, trip, amount?.toString(), time?.toString(), note, category,
)

/** The page a link opens, `moments://moment/{uid}` and the rest; null for one the app has no page for. */
fun routeOf(link: Uri): Any? {
    if (link.scheme != DEEP_LINK_SCHEME) return null
    val id = link.pathSegments.firstOrNull()
    return when (link.host) {
        "moments" -> Feed
        "map" -> Atlas
        // The trips are listed in the map's sheet.
        "trips" -> Atlas
        "settings" -> Preferences
        "sync" -> SyncSettings
        "footprints" -> Atlas
        "footprint-settings" -> FootprintSettings
        "items" -> Items
        "side-key" -> SideKeySettings
        "new" -> NewMoment()
        "edit" -> id?.let(::EditMoment)
        "changes" -> Changes
        "search" -> Search
        // The ledger's sums, as 我 opens them.
        "ledger" -> LedgerHome(1)
        "new-bill" -> NewMoment(bill = true)
        "payment" -> LedgerEntry(
            account = link.getQueryParameter("account"),
            amount = link.getQueryParameter("amount"),
            time = link.getQueryParameter("time"),
            note = link.getQueryParameter("note"),
            category = link.getQueryParameter("category"),
        )
        "bill" -> id?.let(::LedgerBill)
        "account" -> id?.let(::LedgerAccount)
        "ledger-settings" -> LedgerSettings()
        "moment" -> id?.let(::Moment)
        "trip" -> id?.let(::Trip)
        "play" -> id?.let(::TripPlay)
        "trip-edit" -> id?.let(::TripEdit)
        "day" -> id?.let(::Day)
        "place" -> id?.let(::PlaceMoments)
        else -> null
    }
}

/** The app's navigation, for a page to tell what it was opened from. */
private val LocalNavController = staticCompositionLocalOf<NavController?> { null }

/** The whole back stack, which navigation keeps to itself: no public API lists the entries under the one shown. */
private val NavController.backStack: StateFlow<List<NavBackStackEntry>>
    @SuppressLint("RestrictedApi") get() = currentBackStack

/** The page's own entry on the back stack. */
private val LocalPageEntry = staticCompositionLocalOf<NavBackStackEntry?> { null }

/**
 * Whether the page was opened from [route]'s page, the one just under it, as it was when the
 * page opened: kept while it closes, when it has left the back stack. A card for that page
 * then isn't shared with it, so going back the page doesn't grow out of the card as this one
 * shrinks into its own.
 */
@Composable
fun openedFrom(route: Any): Boolean {
    val navController = LocalNavController.current ?: return false
    val entry = LocalPageEntry.current ?: return false
    val below = remember(entry) {
        val stack = navController.backStack.value.filter { it.destination !is NavGraph }
        stack.getOrNull(stack.indexOfFirst { it.id == entry.id } - 1)
    }
    return below?.isOf(route) == true
}

private inline fun <reified T : Any> NavGraphBuilder.screen(
    link: String? = null,
    aliases: List<String> = emptyList(),
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(deepLinks = (listOfNotNull(link) + aliases).map { navDeepLink { uriPattern = "$DEEP_LINK_SCHEME://$it" } }) { entry ->
        val tab = Tab.entries.any { entry.destination.hasRoute(it.type) }
        val room = LocalTabClearance.current
        CompositionLocalProvider(
            LocalNavAnimatedScope provides this,
            LocalPageEntry provides entry,
            LocalBottomClearance provides if (tab) room.bottom else 0.dp,
            LocalFabClearance provides if (tab) room.fab else 0.dp,
        ) { content(entry) }
    }
}
