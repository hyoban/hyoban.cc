package cc.hyoban.moments.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import cc.hyoban.moments.ui.design.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
actual fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBack {
    val state = remember { PredictiveBack() }
    val back by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val settle = AppTheme.motionScheme.fastSpatialSpec<Float>()
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
