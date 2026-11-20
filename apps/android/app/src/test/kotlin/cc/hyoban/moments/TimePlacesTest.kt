package cc.hyoban.moments

import cc.hyoban.moments.ui.picker.TimePlaces
import cc.hyoban.moments.ui.picker.TimedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimePlacesTest {
    private val minute = 60_000L
    private val a = TimedPlace(0, 1.0, 1.0)
    private val b = TimedPlace(60 * minute, 2.0, 2.0)
    private val photos = listOf(a, b)

    @Test fun nearestPhotoInTimeGivesThePlace() {
        assertEquals(a, TimePlaces.nearest(photos, 20 * minute))
        assertEquals(b, TimePlaces.nearest(photos, 40 * minute))
        assertEquals(a, TimePlaces.nearest(photos, -30 * minute))
        assertEquals(b, TimePlaces.nearest(photos, 61 * minute))
    }

    @Test fun nothingBeyondTwoHours() {
        assertEquals(b, TimePlaces.nearest(photos, 180 * minute))
        assertNull(TimePlaces.nearest(photos, 181 * minute))
        assertNull(TimePlaces.nearest(photos, -121 * minute))
        assertNull(TimePlaces.nearest(emptyList(), 0))
    }

    @Test fun footprintsComeBeforePhotos() {
        val footprint = TimedPlace(10 * minute, 9.0, 9.0)
        val placed = TimePlaces.place(mapOf(1L to 5 * minute, 2L to 500 * minute, 3L to 55 * minute), photos) { time ->
            footprint.takeIf { time == 5 * minute }
        }
        assertEquals(mapOf(1L to footprint, 3L to b), placed)
    }

    @Test fun bucketsCoverTheReachEitherSide() {
        val time = TimePlaces.BUCKET + 30 * minute
        assertEquals(0L..1L, TimePlaces.buckets(time))
        assertEquals(-1L..0L, TimePlaces.buckets(0))
    }
}
