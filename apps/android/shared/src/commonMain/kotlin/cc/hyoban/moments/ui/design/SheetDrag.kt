package cc.hyoban.moments.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
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
 * itself or, upright, by what it holds once that is scrolled to its top, and let go, goes on to
 * the next anchor the way it was going once it is a third of the way there or flung, else back.
 * Passing that third is felt (armed, disarmed).
 */
@Stable
class SheetDrag internal constructor(private val scope: CoroutineScope) {
    private val offset = Animatable(Float.NaN)

    /** Where it can rest, in order; set by the sheet once it knows its size. */
    var anchors by mutableStateOf(emptyList<Float>())
        internal set

    /** Where it is now, out from fully open; NaN until it is placed. */
    val position: Float get() = offset.value

    internal var haptics: Haptics? = null

    /** The anchor it is about to settle at, as it is let go. */
    internal var onSettle: (Float) -> Unit = {}

    /** The anchor it has settled at. */
    internal var onSettled: (Float) -> Unit = {}

    // Where the hand took it from, until it is let go.
    private var origin = Float.NaN

    internal fun setAnchors(values: Collection<Float>) {
        anchors = values.distinct().sorted()
    }

    private val resting: Boolean get() = anchors.any { abs(it - position) < 1f }

    /** Moves it by [delta] within its anchors; what it took. */
    fun dragBy(delta: Float): Float {
        val now = offset.value
        if (now.isNaN() || anchors.isEmpty()) return 0f
        if (origin.isNaN()) origin = now
        val next = (now + delta).coerceIn(anchors.first(), anchors.last())
        if (next == now) return 0f
        val way = sign(next - origin)
        val goal = if (way > 0f) anchors.firstOrNull { it > origin + 1f } else anchors.lastOrNull { it < origin - 1f }
        if (goal != null) haptics?.across((now - origin) * way, (next - origin) * way, abs(goal - origin) / 3f)
        // Applied now unless an animation holds it, so the deltas of one frame add up.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
        return next - now
    }

    suspend fun snapTo(target: Float) = offset.snapTo(target)

    suspend fun animateTo(target: Float, velocity: Float = 0f) {
        if (offset.value.isNaN()) offset.snapTo(target) else offset.animateTo(target, SETTLE, velocity)
    }

    /** Let go with [velocity]: on to the anchor it was going to, or back. */
    suspend fun settle(velocity: Float) {
        val from = origin
        origin = Float.NaN
        val now = offset.value
        if (now.isNaN() || anchors.isEmpty()) return
        val target = when {
            velocity > FLING -> anchors.firstOrNull { it > now + 1f } ?: anchors.last()
            velocity < -FLING -> anchors.lastOrNull { it < now - 1f } ?: anchors.first()
            else -> {
                val lo = anchors.lastOrNull { it <= now } ?: anchors.first()
                val hi = anchors.firstOrNull { it >= now } ?: anchors.last()
                val third = (hi - lo) / 3f
                when {
                    lo == hi -> lo
                    !from.isNaN() && from <= lo -> if (now - lo > third) hi else lo
                    !from.isNaN() && from >= hi -> if (hi - now > third) lo else hi
                    else -> if (now - lo < hi - now) lo else hi
                }
            }
        }
        onSettle(target)
        animateTo(target, velocity)
        onSettled(target)
    }

    internal val draggable = DraggableState { dragBy(it) }

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
            settle(available.y)
            return available
        }
    }

    private companion object {
        /** Faster than this, in pixels a second, a let-go is a fling. */
        const val FLING = 1500f
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
