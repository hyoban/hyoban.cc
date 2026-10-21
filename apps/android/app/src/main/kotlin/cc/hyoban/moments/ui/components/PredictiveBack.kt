package cc.hyoban.moments.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How far the system's back gesture has gone over what it would close, 0 to 1, and from which edge. */
class PredictiveBack {
    var progress by mutableFloatStateOf(0f)
    var fromLeft by mutableStateOf(true)
}

/**
 * Back, followed as the system's back gesture is made, as Android 14 previews what back
 * will do: what it closes follows the finger by the returned [PredictiveBack]; let go, [onBack]
 * closes it from there, and called off, it settles back. A back key closes it at once.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean = true, onBack: () -> Unit): PredictiveBack {
    val state = remember { PredictiveBack() }
    val back by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val settle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    // Open again, it starts from rest rather than where the last gesture left it.
    LaunchedEffect(enabled) { if (enabled) state.progress = 0f }
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                state.fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                state.progress = event.progress
            }
            back()
        } catch (e: CancellationException) {
            scope.launch { animate(state.progress, 0f, animationSpec = settle) { value, _ -> state.progress = value } }
            throw e
        }
    }
    return state
}

/**
 * A full-screen layer following [back], as Material 3 has one previewing its close: shrinking
 * a little toward the side away from the edge swiped from, its corners rounding.
 */
fun Modifier.predictiveBackScale(back: PredictiveBack): Modifier = graphicsLayer {
    val progress = back.progress
    val scale = 1f - PREVIEW_SHRINK * progress
    scaleX = scale
    scaleY = scale
    translationX = (if (back.fromLeft) 1f else -1f) * PREVIEW_SHIFT.toPx() * progress
    // A plain rounded rectangle, clipped as it is drawn rather than through a mask each frame.
    shape = RoundedCornerShape(PREVIEW_CORNER * progress)
    clip = progress > 0f
}

private const val PREVIEW_SHRINK = 0.1f
private val PREVIEW_SHIFT = 8.dp
private val PREVIEW_CORNER = 28.dp
