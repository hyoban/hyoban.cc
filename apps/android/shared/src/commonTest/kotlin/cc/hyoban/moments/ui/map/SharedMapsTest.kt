package cc.hyoban.moments.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedMapsTest {
    @Test
    fun positionKeepsLatitudeAndLongitudeInTheirNamedAxes() {
        val position = positionOf(latitude = 31.2, longitude = 121.5)

        assertEquals(31.2, position.latitude)
        assertEquals(121.5, position.longitude)
    }

    @Test
    fun playbackSpringSettlesWithoutOvershooting() {
        var value = 0.0
        var velocity = 0.0
        repeat(240) {
            val before = value
            val next = playbackSpring(value, velocity, goal = 1.0, omega = 5.0, seconds = 1.0 / 60)
            value = next.first
            velocity = next.second
            assertTrue(value in before..1.0)
        }

        assertTrue(value > 0.999999)
    }

    @Test
    fun completedFogSweepIsClearWhenExitingAndOpaqueWhenEntering() {
        repeat(24) { index ->
            assertEquals(0f, fogRingOpacity(index, even = false, reach = 1f, entering = false))
            assertEquals(1f, fogRingOpacity(index, even = false, reach = 1f, entering = true))
        }
        assertEquals(0f, fogRingOpacity(index = 0, even = true, reach = 1f, entering = false))
        assertEquals(1f, fogRingOpacity(index = 0, even = true, reach = 1f, entering = true))
    }
}
