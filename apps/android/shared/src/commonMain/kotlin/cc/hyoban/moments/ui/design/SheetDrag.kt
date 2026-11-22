package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import cc.hyoban.moments.ui.theme.Springs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * How every sheet moves under the hand, so all are dragged and let go alike: it rests at one of
 * its [anchors] (pixels out from where it is fully open, further out the larger), is dragged by
 * itself or, upright, by what it holds once that is scrolled to its top, and let go moving, goes
 * on to the next anchor the way it was moving once it is a third of the way there or flung, else
 * back; let go still, to the nearest. Passing that third is felt (armed, disarmed).
 *
 * Compose foundation's [AnchoredDraggableState] keeps where it is, decides where a let-go goes and
 * animates it there; this adds the hand-off with what the sheet holds, the haptics and the
 * callbacks. Its anchors are keyed by their place in [anchors].
 */
@Stable
class SheetDrag internal constructor(private val scope: CoroutineScope) {
    internal val state = AnchoredDraggableState(initialValue = 0)

    /** Foundation's: where a let-go goes, and its spring there; given by [rememberSheetDrag]. */
    internal var fling: TargetedFlingBehavior? = null

    /** Where it can rest, in order; set by the sheet once it knows its size. */
    var anchors by mutableStateOf(emptyList<Float>())
        private set

    /** Where it is now, out from fully open; NaN until it is placed. */
    val position: Float get() = state.offset

    internal var haptics: Haptics? = null

    /** The anchor it is about to settle at, as it is let go. */
    internal var onSettle: (Float) -> Unit = {}

    /** The anchor it has settled at. */
    internal var onSettled: (Float) -> Unit = {}

    // Where the hand took it from, until it is let go.
    private var origin = Float.NaN

    /**
     * Where it can rest; the first time, it is placed at the anchor nearest [start], and after
     * that it stays where it is, for its sheet to move it on.
     */
    internal fun setAnchors(values: Collection<Float>, start: Float = values.maxOrNull() ?: 0f) {
        val sorted = values.distinct().sorted()
        if (sorted.isEmpty() || sorted == anchors) return
        val before = position
        anchors = sorted
        val next = DraggableAnchors { sorted.forEachIndexed { index, at -> index at at } }
        state.updateAnchors(next, next.closestAnchor(if (before.isNaN()) start else before) ?: 0)
        // The state snaps to its new anchor; held where it was instead, as far as they reach.
        if (!before.isNaN() && !state.isAnimationRunning && state.offset != before) state.dispatchRawDelta(before - state.offset)
    }

    private fun indexOf(target: Float): Int? = anchors.indices.minByOrNull { abs(anchors[it] - target) }

    private val resting: Boolean get() = !state.isAnimationRunning && anchors.any { abs(it - position) < 1f }

    /** Where [delta] would take it, held within its anchors, felt as it passes a third. */
    private fun toward(delta: Float): Float {
        val now = position
        if (now.isNaN() || anchors.isEmpty()) return now
        val next = (now + delta).coerceIn(anchors.first(), anchors.last())
        // Held where it is (open all the way, a list scrolled up within it), it isn't being dragged.
        if (next == now) return now
        if (origin.isNaN()) origin = now
        val way = sign(next - origin)
        val goal = if (way > 0f) anchors.firstOrNull { it > origin + 1f } else anchors.lastOrNull { it < origin - 1f }
        if (goal != null) haptics?.across((now - origin) * way, (next - origin) * way, abs(goal - origin) / 3f)
        return next
    }

    /** Moves it by [delta] within its anchors; what it took. */
    fun dragBy(delta: Float): Float {
        val now = position
        val next = toward(delta)
        if (next.isNaN() || next == now) return 0f
        // A drag handed over by what it holds stops a settle still going, as a drag of its own does.
        if (state.isAnimationRunning) scope.launch(start = CoroutineStart.UNDISPATCHED) { state.anchoredDrag(MutatePriority.UserInput) {} }
        return state.dispatchRawDelta(next - now)
    }

    suspend fun snapTo(target: Float) {
        indexOf(target)?.let { state.snapTo(it) }
    }

    suspend fun animateTo(target: Float) {
        val index = indexOf(target) ?: return
        if (position.isNaN()) state.snapTo(index) else state.animateTo(index, SETTLE)
    }

    /** Let go with [velocity]: on to the anchor it was going to, or back; what it took of it. */
    suspend fun settle(velocity: Float): Float {
        origin = Float.NaN
        if (position.isNaN() || anchors.isEmpty()) return 0f
        val fling = fling ?: return 0f
        var going: Float? = null
        var left = velocity
        state.anchoredDrag {
            val anchored = this
            val scroll = object : ScrollScope {
                override fun scrollBy(pixels: Float): Float {
                    val now = state.offset
                    anchored.dragTo(now + pixels)
                    return state.offset - now
                }
            }
            left = with(fling) {
                scroll.performFling(velocity) { remaining ->
                    // After a first 0, its first word is how far off the anchor it chose is.
                    if (going == null && remaining != 0f) going = anchors[indexOf(state.offset + remaining) ?: state.currentValue].also(onSettle)
                }
            }
        }
        if (going == null) onSettle(anchors[state.settledValue])
        onSettled(anchors[state.settledValue])
        return velocity - left
    }

    /** Its own drags, which take it from a settle still going. */
    internal val draggable = object : DraggableState {
        override fun dispatchRawDelta(delta: Float) {
            dragBy(delta)
        }

        override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
            state.anchoredDrag(dragPriority) {
                val anchored = this
                object : DragScope {
                    override fun dragBy(pixels: Float) {
                        val next = toward(pixels)
                        if (!next.isNaN()) anchored.dragTo(next)
                    }
                }.block()
            }
        }
    }

    /**
     * What it holds scrolls until it is at its top; the rest of a drag down takes the sheet, and
     * a drag up takes the sheet back before the list scrolls again. A fling's leftover never does.
     */
    internal val nested = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (origin.isNaN() && resting) return Velocity.Zero
            // Dragged open all the way and the list then scrolled on: the fling up is the list's.
            if (available.y < 0f && anchors.isNotEmpty() && abs(position - anchors.first()) < 1f) {
                settle(0f)
                return Velocity.Zero
            }
            settle(available.y)
            return available
        }
    }

    internal companion object {
        /** A let-go goes on once it is this much of the way to the next anchor. */
        val THIRD: (Float) -> Float = { distance -> distance / 3f }
        val SETTLE = spring<Float>(dampingRatio = 1f, stiffness = Springs.spatialDefault.stiffness)
    }
}

/**
 * A [SheetDrag]; [onSettle] hears where it is going as it is let go, [onSettled] where it came to
 * rest.
 */
@Composable
fun rememberSheetDrag(onSettle: (Float) -> Unit = {}, onSettled: (Float) -> Unit = {}): SheetDrag {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val drag = remember { SheetDrag(scope) }
    drag.haptics = haptics
    drag.fling = AnchoredDraggableDefaults.flingBehavior(drag.state, positionalThreshold = SheetDrag.THIRD, animationSpec = SheetDrag.SETTLE)
    drag.onSettle = onSettle
    drag.onSettled = onSettled
    return drag
}

/**
 * Lets [state] be dragged: [orientation] the way it goes out (sideways for a sheet at the end),
 * [reverse] when out is toward the top or the start. Upright, what it holds scrolling hands it
 * the drag at its top.
 */
fun Modifier.sheetDrag(state: SheetDrag, orientation: Orientation = Orientation.Vertical, reverse: Boolean = false, enabled: Boolean = true): Modifier =
    draggable(state.draggable, orientation, enabled, reverseDirection = reverse, onDragStopped = { velocity -> state.settle(velocity) })
        .then(if (enabled && orientation == Orientation.Vertical) Modifier.nestedScroll(state.nested) else Modifier)
