package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.compose.PlaceChoice
import cc.hyoban.moments.ui.compose.PlaceOrigin
import cc.hyoban.moments.ui.compose.inferredPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The place the Moment editor works out by itself, the one 用照片的地点 goes back to. */
class InferredPlaceTest {
    private val photo = Fix(30.25, 120.15)
    private val stay = Fix(30.26, 120.16)
    private val known = PlaceChoice.Known("p-1")

    @Test
    fun `the photo's place, named after what is around it`() {
        val kind = PlaceKind.entries.first()
        assertEquals(PlaceChoice.New("满觉陇", photo, kind) to PlaceOrigin.PHOTO, inferredPlace(null, photo, stay, "满觉陇", kind))
    }

    @Test
    fun `a stay's own place comes first, else a new one where it is`() {
        assertEquals(known to PlaceOrigin.STAY, inferredPlace(known, photo, stay, "满觉陇", null))
        assertEquals(PlaceChoice.New("宝石山", stay) to PlaceOrigin.STAY, inferredPlace(null, null, stay, "宝石山", null))
    }

    @Test
    fun `none without a photo's place or a stay, or before it is named`() {
        assertNull(inferredPlace(null, null, null, null, null))
        assertNull(inferredPlace(null, photo, null, null, null))
    }
}
