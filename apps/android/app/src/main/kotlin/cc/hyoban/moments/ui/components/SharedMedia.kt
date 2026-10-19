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
        val state = rememberSharedContentState(key)
        val clip = OverlayClip(shape)
        if (LocalInViewer.current) {
            this@sharedMedia.sharedElement(state, animatedVisibilityScope = animated, clipInOverlayDuringTransition = clip)
        } else {
            // A tile shows while its page does and the viewer isn't showing it.
            val visible = animated.transition.targetState == EnterExitState.Visible && LocalViewerShownKey.current != key
            this@sharedMedia.sharedElementWithCallerManagedVisibility(state, visible = visible, clipInOverlayDuringTransition = clip)
        }
    }
}

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
        val arriving = { isTransitionActive && transition.targetState == EnterExitState.Visible }
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
