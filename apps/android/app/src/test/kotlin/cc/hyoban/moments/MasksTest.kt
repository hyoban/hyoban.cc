package cc.hyoban.moments

import cc.hyoban.moments.data.local.Mask
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.ui.design.Sticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 人脸打码's patch laid by a tap: square on the photo, a fifth of its shorter side, and inside it. */
class MasksTest {
    @Test
    fun aPatchIsSquareOnAWidePhoto() {
        val mask = Mask.around(0.5f, 0.5f, shape = 1.5f)
        // 0.2 of the height across is 0.2 / 1.5 of the width.
        assertEquals(0.2f / 1.5f, mask.right - mask.left, 1e-5f)
        assertEquals(0.2f, mask.bottom - mask.top, 1e-5f)
        assertTrue(mask.contains(0.5f, 0.5f))
    }

    @Test
    fun aPatchIsSquareOnATallPhoto() {
        val mask = Mask.around(0.5f, 0.5f, shape = 0.75f)
        assertEquals(0.2f, mask.right - mask.left, 1e-5f)
        assertEquals(0.2f * 0.75f, mask.bottom - mask.top, 1e-5f)
    }

    @Test
    fun aPatchAtTheEdgeStaysOnThePhoto() {
        val mask = Mask.around(0.99f, 0.01f, shape = 1f)
        assertEquals(1f, mask.right, 1e-5f)
        assertEquals(0f, mask.top, 1e-5f)
        assertEquals(0.2f, mask.right - mask.left, 1e-5f)
    }

    @Test
    fun aPatchNamesItsSticker() {
        assertEquals(Sticker.CAT, Sticker.of(Mask(0f, 0f, 1f, 1f, sticker = "cat").sticker))
        // No sticker, or one the app doesn't draw, is a mosaic.
        assertNull(Sticker.of(null))
        assertNull(Sticker.of("unicorn"))
    }

    @Test
    fun aPhotoKeepsItsPatchesAndTheCopyAsPicked() {
        val local = LocalMedia(
            uri = "file:///media/a-drawn.jpg",
            copied = true,
            unmasked = "file:///media/a.heic",
            masks = listOf(Mask(0.1f, 0.2f, 0.3f, 0.4f), Mask(0.5f, 0.5f, 0.7f, 0.8f, sticker = "bear")),
        )
        val read = Spec.json.decodeFromString(LocalMedia.serializer(), Spec.json.encodeToString(LocalMedia.serializer(), local))
        assertEquals(local.unmasked, read.unmasked)
        assertEquals(local.masks, read.masks)
    }

    @Test
    fun aRandomStickerIsOneNotUsedYet() {
        val used = Sticker.entries.drop(1).map { it.key }
        repeat(20) { assertEquals(Sticker.entries.first().key, Sticker.random(used)) }
        // With every one used, any will do.
        assertTrue(Sticker.of(Sticker.random(Sticker.entries.map { it.key })) != null)
    }

    @Test
    fun aPatchLaidAmongFacesTakesTheNearestOnesSize() {
        val small = Mask(0.10f, 0.40f, 0.14f, 0.46f)
        val large = Mask(0.70f, 0.30f, 0.90f, 0.60f)
        val laid = Mask.around(0.2f, 0.45f, shape = 1.5f, beside = listOf(small, large))
        assertEquals(0.04f, laid.right - laid.left, 1e-5f)
        assertEquals(0.06f, laid.bottom - laid.top, 1e-5f)
        assertTrue(laid.contains(0.2f, 0.45f))
    }
}
