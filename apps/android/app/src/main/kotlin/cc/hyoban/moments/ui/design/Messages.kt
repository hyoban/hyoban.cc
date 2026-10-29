package cc.hyoban.moments.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Messages at the foot of the screen, one at a time; pulling a list down to refresh it; and a
// row swiped from its end to take something back.

// ---------------------------------------------------------------- messages

enum class SnackbarDuration { Short, Long, Indefinite }

enum class SnackbarResult { Dismissed, ActionPerformed }

class SnackbarVisuals(val message: String, val actionLabel: String?, val withDismissAction: Boolean, val duration: SnackbarDuration)

/** The message shown, and how it is answered: its action taken, or let go. */
class SnackbarData internal constructor(val visuals: SnackbarVisuals, private val answer: CancellableContinuation<SnackbarResult>) {
    fun performAction() {
        if (answer.isActive) answer.resume(SnackbarResult.ActionPerformed)
    }

    fun dismiss() {
        if (answer.isActive) answer.resume(SnackbarResult.Dismissed)
    }
}

/** Messages waiting their turn: [showSnackbar] shows one and waits for its answer. */
@Stable
class SnackbarHostState {
    private val turn = Mutex()

    var currentSnackbarData by mutableStateOf<SnackbarData?>(null)
        private set

    suspend fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    ): SnackbarResult = turn.withLock {
        try {
            suspendCancellableCoroutine { answer -> currentSnackbarData = SnackbarData(SnackbarVisuals(message, actionLabel, withDismissAction, duration), answer) }
        } finally {
            currentSnackbarData = null
        }
    }
}

/** Where messages show, each rising in and fading out, and going by itself unless it waits for an answer. */
@Composable
fun SnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier, snackbar: @Composable (SnackbarData) -> Unit = { AppSnackbar(it) }) {
    val data = hostState.currentSnackbarData
    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        when (data.visuals.duration) {
            SnackbarDuration.Short -> delay(4000)
            SnackbarDuration.Long -> delay(10000)
            SnackbarDuration.Indefinite -> return@LaunchedEffect
        }
        data.dismiss()
    }
    AnimatedContent(
        data,
        modifier,
        transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
        label = "message",
    ) { shown -> if (shown != null) Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { snackbar(shown) } }
}

/** A message: on the surface with its hairline, its words, its action in the accent and a way to close it. */
@Composable
fun AppSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val shape = AppTheme.shapes.medium
    Row(
        modifier
            .padding(12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(tokens.surface)
            .border(cardBorder(), shape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(data.visuals.message, Modifier.weight(1f).padding(vertical = 12.dp), color = tokens.tx, style = AppTheme.typography.bodyMedium)
        data.visuals.actionLabel?.let { label ->
            TextButton(onClick = data::performAction, colors = ButtonDefaults.textButtonColors(contentColor = tokens.accent)) { Text(label) }
        }
        if (data.visuals.withDismissAction) {
            IconButton(onClick = data::dismiss) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.action_close), tint = tokens.tx2)
            }
        }
    }
}

// ---------------------------------------------------------------- pulled to refresh

/**
 * A list that refreshes when pulled down from its top: a spinner on a small round surface comes
 * down with the pull, and [onRefresh] runs once it is let go past the threshold; it stays while
 * [isRefreshing].
 */
@Composable
fun PullToRefreshBox(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(isRefreshing) { pull.animateTo(if (isRefreshing) threshold else 0f, spring(dampingRatio = 1f, stiffness = 500f)) }
    val connection = remember(isRefreshing, threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isRefreshing || available.y >= 0f || pull.value <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val before = pull.value
                scope.launch { pull.snapTo((before + available.y).coerceAtLeast(0f)) }
                return Offset(0f, (before + available.y).coerceAtLeast(0f) - before)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (isRefreshing || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                scope.launch { pull.snapTo((pull.value + available.y * 0.5f).coerceAtMost(threshold * 1.6f)) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isRefreshing || pull.value <= 0f) return Velocity.Zero
                if (pull.value >= threshold) onRefresh() else pull.animateTo(0f)
                return Velocity.Zero
            }
        }
    }
    Box(modifier.nestedScroll(connection)) {
        content()
        val shown = (pull.value / threshold).coerceIn(0f, 1f)
        if (pull.value > 0f || isRefreshing) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (pull.value - 48.dp.toPx()).roundToInt()) }
                    .graphicsLayer { alpha = shown; scaleX = 0.6f + 0.4f * shown; scaleY = 0.6f + 0.4f * shown },
            ) { ContainedSpinner() }
        }
    }
}

// ---------------------------------------------------------------- swiped away

/**
 * A row swiped from its end towards its start: [backgroundContent] shows from under it as it
 * goes, and past 40% of its width, let go, [onDismiss] runs; short of that it springs back.
 */
@Composable
fun SwipeToDismissBox(onDismiss: () -> Unit, backgroundContent: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    Box(modifier.onSizeChanged { width = it.width.toFloat() }) {
        Row(Modifier.matchParentSize(), content = backgroundContent)
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(-width, 0f)) } },
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        if (offset.value < -width * 0.4f || velocity < -2000f) {
                            offset.animateTo(-width, spring(stiffness = 1500f))
                            onDismiss()
                            offset.snapTo(0f)
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 800f))
                        }
                    },
                ),
        ) { content() }
    }
}
