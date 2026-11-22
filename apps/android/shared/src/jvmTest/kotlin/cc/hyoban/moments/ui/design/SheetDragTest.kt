package cc.hyoban.moments.ui.design

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetDragTest {
    /** Frames 16 ms apart, as fast as they are asked for. */
    private object Frames : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            now += 16_000_000L
            return onFrame(now)
        }
    }

    private object Nothing : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** A sheet resting at 0, 300 and 1000, out at 1000, with foundation's fling as on a 1x screen. */
    private fun sheet(block: suspend CoroutineScope.(SheetDrag) -> Unit) = runBlocking(Frames) {
        val drag = SheetDrag(this)
        val recomposer = Recomposer(coroutineContext)
        val composing = launch { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(Nothing, recomposer)
        composition.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                drag.fling = AnchoredDraggableDefaults.flingBehavior(drag.state, positionalThreshold = SheetDrag.THIRD, animationSpec = SheetDrag.SETTLE)
            }
        }
        drag.setAnchors(listOf(0f, 300f, 1000f), start = 1000f)
        try {
            block(drag)
        } finally {
            composition.dispose()
            recomposer.cancel()
            composing.cancel()
        }
    }

    @Test
    fun placedAtItsStart() = sheet { drag ->
        assertEquals(1000f, drag.position)
        drag.setAnchors(listOf(0f, 400f, 1200f))
        assertEquals(1000f, drag.position, "kept where it was until its sheet moves it")
    }

    @Test
    fun goesOnPastAThird() = sheet { drag ->
        drag.snapTo(300f)
        drag.dragBy(-110f)
        drag.settle(-50f)
        assertEquals(0f, drag.position)
    }

    @Test
    fun goesBackShortOfAThird() = sheet { drag ->
        drag.snapTo(300f)
        drag.dragBy(-80f)
        drag.settle(-50f)
        assertEquals(300f, drag.position)
    }

    @Test
    fun letGoStillItGoesToTheNearest() = sheet { drag ->
        drag.snapTo(300f)
        drag.dragBy(-110f)
        drag.settle(0f)
        assertEquals(300f, drag.position)
    }

    @Test
    fun aFlingGoesOn() = sheet { drag ->
        drag.snapTo(300f)
        drag.dragBy(20f)
        var going = Float.NaN
        drag.onSettle = { going = it }
        drag.settle(3000f)
        assertEquals(1000f, going)
        assertEquals(1000f, drag.position)
    }

    @Test
    fun aListScrolledUpInAnOpenSheetKeepsItsFling() = sheet { drag ->
        drag.snapTo(0f)
        val took = drag.nested.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, took)
        assertEquals(Velocity.Zero, drag.nested.onPreFling(Velocity(0f, -4000f)))
    }

    @Test
    fun draggedOpenThenScrolledOnTheListKeepsItsFling() = sheet { drag ->
        drag.snapTo(300f)
        drag.nested.onPreScroll(Offset(0f, -400f), NestedScrollSource.UserInput)
        assertEquals(0f, drag.position)
        assertEquals(Velocity.Zero, drag.nested.onPreFling(Velocity(0f, -4000f)))
        assertEquals(0f, drag.position)
    }

    @Test
    fun aListAtItsTopHandsTheSheetTheDragDown() = sheet { drag ->
        drag.snapTo(0f)
        val took = drag.nested.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.UserInput)
        assertEquals(200f, took.y)
        assertTrue(drag.nested.onPreFling(Velocity(0f, 2500f)).y > 0f)
        assertEquals(300f, drag.position)
    }
}
