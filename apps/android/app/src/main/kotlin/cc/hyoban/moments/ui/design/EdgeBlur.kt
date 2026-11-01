package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import cc.hyoban.moments.ui.theme.Blur
import cc.hyoban.moments.ui.theme.tokens
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

// The page passing under what stays over it at the screen's top and bottom, the status bar and
// the top bar's circles, the tab bar, softened as the system softens a scroll edge: blurred as
// much as `Blur.edge` at the screen's edge and less and less to none where the edge ends, under
// a veil of the page's own colour that thins the same way and keeps what is over it clear.
// Where blur can't be drawn, the veil alone.

/**
 * Draws behind this the page under it, which [source] marks with `hazeSource`, softened most at
 * its top edge if [top], else at its bottom, and not at all at the other; [veil] is how much of
 * the page's colour lies over it at that edge.
 */
@Composable
fun Modifier.edgeBlur(source: HazeState, top: Boolean, veil: Float = EDGE_VEIL): Modifier {
    val style = remember(top) {
        HazeBlurStyle {
            blurRadius(Blur.edge)
            // No grain: the veil over it is the page's own flat colour.
            noiseFactor(0f)
            // Easing to none where the edge ends, so what crosses it isn't cut there.
            progressive(
                if (top) {
                    HazeProgressive.verticalGradient(easing = EaseOut, startIntensity = 1f, endIntensity = 0f)
                } else {
                    HazeProgressive.verticalGradient(easing = EaseIn, startIntensity = 0f, endIntensity = 1f)
                },
            )
        }
    }
    val page = tokens.bg
    val edge = page.copy(alpha = veil)
    val clear = page.copy(alpha = 0f)
    return hazeBlur(HazeInput.Sources(source), style)
        .background(Brush.verticalGradient(if (top) listOf(edge, clear) else listOf(clear, edge)))
}

/** How much of the page's colour veils the softened edge where it is strongest. */
private const val EDGE_VEIL = 0.6f
