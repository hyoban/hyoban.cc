package cc.hyoban.moments.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.TransitionMotion

// A Moment's card keeps its place between screens: it grows into the Moment's page and
// shrinks back, by a key both screens give the same Moment.

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * The uid of the Moment whose card grows into its page or shrinks back from it: a card
 * joins its page only then, not when a page holding the same card, a bill's, comes or goes.
 */
val LocalCardOpening = staticCompositionLocalOf<State<String?>> { mutableStateOf(null) }

/** The navigation transition of the screen a composable is on. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A Moment's card on a list and its page, as one surface: tapping the card grows it into
 * the page, photo, words and all, as the App Store's stories open, and going back, by a
 * swipe too, shrinks the page into the card. Its corners go from the card's to none as it
 * grows.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(key: String, cardCorner: Dp = Radius.medium, page: Boolean = false): Modifier {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalNavAnimatedScope.current
    if (shared == null || animated == null) return this

    // The page's corners round as it shrinks toward the card, and square off as it fills the
    // screen; read as the clip is drawn, so the cards aren't composed again every frame for it.
    val corner = animated.transition.animateDp(label = "card corner") { state ->
        if (page && state == EnterExitState.Visible) 0.dp else cardCorner
    }
    val clip = remember(corner) { CornerClip(corner) }
    // The transitions' springs, a card growing into its page being one: it grows on the
    // spatial one, its two faces cross on the effects one.
    val motion = TransitionMotion
    val opening = LocalCardOpening.current
    return with(shared) {
        val config = remember(opening, key) {
            object : SharedTransitionScope.SharedContentConfig {
                override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = opening.value == key
            }
        }
        this@sharedCard.sharedBounds(
            rememberSharedContentState("card/$key", config),
            animatedVisibilityScope = animated,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            boundsTransform = { _, _ -> motion.defaultSpatialSpec() },
            // Scaled, not laid out again at each size, so the words keep their lines as it grows.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = OverlayClip(clip),
        )
    }
}

/**
 * A plain rounded rectangle, its corners [corner] as they are when it is drawn: the graphics
 * pipeline clips to one as it draws, where a continuous corner's path goes through a mask
 * drawn in software and uploaded every frame, and on the move the two can't be told apart.
 */
private class CornerClip(private val corner: State<Dp>) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        RoundedCornerShape(corner.value).createOutline(size, layoutDirection, density)
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
