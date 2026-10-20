package cc.hyoban.moments.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import com.kyant.capsule.ContinuousRoundedRectangle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import android.app.Activity
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape

// A photo keeps its place between screens: the card's tile grows into the viewer and
// shrinks back, by a key both screens give the same media.

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The navigation transition of the screen a composable is on. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The key of the photo the viewer shows over the pages, while it is open: the tile with
 * that key steps out of sight, so the photo seems to have lifted out of it, and steps back
 * as the viewer closes, so the photo flies back into it.
 */
val LocalViewerShownKey = staticCompositionLocalOf<String?> { null }

/**
 * Whether a back swipe is under way, or has just ended and its page change is still
 * playing out: the app root watches the system's back gesture and sets it.
 */
val LocalBackSwipe = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * Whether the photo viewer is open, or still closing: a photo flies between a tile and
 * the viewer only then. Between pages it is the whole card that moves (see sharedCard).
 */
val LocalViewerShowing = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Whether a composable is in the viewer, whose photo shows whenever the viewer does. */
val LocalInViewer = staticCompositionLocalOf { false }

/** The key a media item shares across the screens that show it. */
fun mediaKey(momentUid: String, mediaUid: String) = "media/$momentUid/$mediaUid"

/**
 * Joins this element to the others with [key] as the screens change; nothing without a
 * key or outside a transition. On its way it keeps the corners of [shape], which its
 * parents' clipping can't give it there.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(key: String?, shape: Shape = RectangleShape): Modifier {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (key == null || shared == null || animated == null) return this

    return with(shared) {
        val clip = OverlayClip(shape)
        if (LocalInViewer.current) {
            this@sharedMedia.sharedElement(rememberSharedContentState(key), animatedVisibilityScope = animated, clipInOverlayDuringTransition = clip)
        } else {
            // A back swipe previews the page under this one, following the finger, and
            // the pages cross-fade with it as the system's own preview does; a photo
            // flying between them as well played a second animation over the first.
            // From the swipe's start until the page change settles, the photo stays
            // with its page.
            val transition = animated.transition
            val swiped = LocalBackSwipe.current
            val viewerShowing = LocalViewerShowing.current
            val config = remember(swiped, viewerShowing) {
                object : SharedTransitionScope.SharedContentConfig {
                    override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = !swiped.value && viewerShowing.value
                    override val shouldKeepEnabledForOngoingAnimation: Boolean get() = false
                }
            }
            val tileState = rememberSharedContentState(key, config)
            // A tile shows while its page does and the viewer isn't showing it.
            val visible = transition.targetState == EnterExitState.Visible && LocalViewerShownKey.current != key
            this@sharedMedia.sharedElementWithCallerManagedVisibility(tileState, visible = visible, clipInOverlayDuringTransition = clip)
        }
    }
}

/**
 * A Moment's card on a list and its page, as one surface: tapping the card grows it into
 * the page, photo, words and all, as the App Store's stories open, and going back shrinks
 * the page into the card. Its corners go from the card's to none as it grows. Not during
 * a back swipe, whose own preview would play it twice, nor while the viewer shows.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(key: String, cardCorner: Dp = 16.dp, page: Boolean = false): Modifier {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (shared == null || animated == null) return this

    val swiped = LocalBackSwipe.current
    val viewerShowing = LocalViewerShowing.current
    // The page's corners round as it shrinks toward the card, and square off as it fills the screen.
    val corner by animated.transition.animateDp(label = "card corner") { state ->
        if (page && state == EnterExitState.Visible) 0.dp else cardCorner
    }
    return with(shared) {
        val config = remember(swiped, viewerShowing) {
            object : SharedTransitionScope.SharedContentConfig {
                override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = !swiped.value && !viewerShowing.value
                override val shouldKeepEnabledForOngoingAnimation: Boolean get() = false
            }
        }
        this@sharedCard.sharedBounds(
            rememberSharedContentState("card/$key", config),
            animatedVisibilityScope = animated,
            enter = fadeIn(tween(CARD_MS / 2)),
            exit = fadeOut(tween(CARD_MS / 2)),
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 380f) },
            // Scaled, not laid out again at each size, so the words keep their lines as it grows.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = OverlayClip(ContinuousRoundedRectangle(corner)),
        )
    }
}

private const val CARD_MS = 400

/**
 * Keeps what lies over a photo, a sheet or a button, over it as the photo comes onto its
 * screen, fading in with the screen; without this the photo would pass over it and drop
 * back under at the end. Leaving its screen it stays with the screen, under the photo on
 * its way out.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.aboveSharedMedia(): Modifier {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (shared == null || animated == null) return this

    val transition = animated.transition
    val alpha by transition.animateFloat(label = "above shared media") { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        // While this page arrives, until both its own transition and the photo flying in
        // with it have settled; a photo flying from the settled page into the viewer is
        // another transition, and passes over the sheet.
        var settled by remember(transition) { mutableStateOf(false) }
        LaunchedEffect(transition, this) {
            snapshotFlow {
                transition.targetState == EnterExitState.Visible &&
                    transition.currentState == EnterExitState.Visible &&
                    !isTransitionActive
            }.collect { if (it) settled = true }
        }
        LaunchedEffect(transition.targetState) { if (transition.targetState != EnterExitState.Visible) settled = false }
        val arriving = { transition.targetState == EnterExitState.Visible && !settled }
        this@aboveSharedMedia
            .renderInSharedTransitionScopeOverlay(renderInOverlay = arriving, zIndexInOverlay = 1f)
            .graphicsLayer { this.alpha = if (arriving()) alpha else 1f }
    }
}

/**
 * The URL each media item's still was last drawn from, so a larger view can show that
 * one, already in memory, while its own loads.
 */
object DrawnStills {
    private val urls = mutableMapOf<String, String>()

    fun note(mediaUid: String, url: String) {
        urls[mediaUid] = url
    }

    fun of(mediaUid: String): String? = urls[mediaUid]
}

/**
 * White status bar icons while [over] is true, as over a photo running under the bar,
 * and the theme's own otherwise; put back as they were when this leaves.
 */
@Composable
fun StatusBarOverPhoto(over: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val themeLight = remember(controller) { controller.isAppearanceLightStatusBars }

    LaunchedEffect(over) { controller.isAppearanceLightStatusBars = if (over) false else themeLight }
    DisposableEffect(controller) { onDispose { controller.isAppearanceLightStatusBars = themeLight } }
}
