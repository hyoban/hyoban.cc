package cc.hyoban.moments.ui

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.design.edgeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.animateColorAsState
import cc.hyoban.moments.ui.design.LocalContentColor
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.things.ThingKind
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.Settings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.DialogPane
import cc.hyoban.moments.ui.components.LocalMediaMaxHeight
import cc.hyoban.moments.ui.components.LocalNavAnimatedScope
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.LocalCardOpening
import cc.hyoban.moments.ui.components.LocalSharedTransitionScope
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.components.isExpandedWidth
import cc.hyoban.moments.ui.feed.DayScreen
import cc.hyoban.moments.ui.feed.FeedScreen
import cc.hyoban.moments.ui.feed.PlaceScreen
import cc.hyoban.moments.ui.feed.SearchScreen
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ui.ledger.LedgerEntryArgs
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LedgerNav
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.TransitionMotion
import cc.hyoban.moments.ui.trips.TripPlayerScreen
import cc.hyoban.moments.ui.trips.TripScreen
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// What the app's shell hands down to its pages, on every device.

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
