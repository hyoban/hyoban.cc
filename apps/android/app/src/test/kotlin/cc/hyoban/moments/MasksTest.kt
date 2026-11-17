package cc.hyoban.moments

import cc.hyoban.moments.data.local.FoundPatches
import cc.hyoban.moments.data.local.distinctPatches
import cc.hyoban.moments.data.local.Mask
import cc.hyoban.moments.data.local.plateIn
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.ui.design.Sticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 打码's patch laid by a tap: square on the photo, a fifth of its shorter side, and inside it. */
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
    fun aRandomStickerIsOneNotUsedYetOfItsKind() {
        for (plate in listOf(false, true)) {
            val kind = Sticker.forPlate(plate)
            val used = kind.drop(1).map { it.key }
            repeat(20) { assertEquals(kind.first().key, Sticker.random(used, plate)) }
            // With every one used, any of its kind will do.
            repeat(20) { assertEquals(plate, Sticker.of(Sticker.random(kind.map { it.key }, plate))?.plate) }
        }
    }

    @Test
    fun aPlateIsReadAsTextRecognitionGivesIt() {
        for (plate in listOf("粤B·12345", "粤B12345", "粤B 12345", "京A·D12345", "沪AF1234D", "苏E·1234学")) {
            assertEquals(plate.indices, plateIn(plate), plate)
        }
        // Amid other words, only the plate.
        assertEquals(3..10, plateIn("车牌：浙A·8K9Q2 停"))
        // Without its province, only with the dot after the city's letter.
        assertEquals(0..6, plateIn("B·12345"))
        assertNull(plateIn("B12345"))
        // A new-energy plate far off, its province read as a letter or lost, its dot lost.
        assertEquals(0..7, plateIn("QBD12345"))
        assertEquals(0..8, plateIn("QB D12345"))
        assertEquals(0..6, plateIn("BD12345"))
        assertEquals(0..6, plateIn("B12345F"))
    }

    @Test
    fun whatIsNoPlateIsLeft() {
        for (text in listOf("营业时间 9:00", "粤B123", "粤B1234567", "A12345", "WIFI·PASSWORD", "粤b12345", "WIFID12345", "XYZD12345", "BD1234")) {
            assertNull(plateIn(text), text)
        }
    }

    @Test
    fun eachFoundPatchTakesTheStyleOfItsKind() {
        val face = Mask(0.1f, 0.1f, 0.2f, 0.2f)
        val plate = Mask(0.5f, 0.8f, 0.7f, 0.85f)
        val laid = listOf(Mask(0.3f, 0.3f, 0.4f, 0.4f, sticker = "cat"))
        val patches = FoundPatches(listOf(face), listOf(plate)).patches(laid) { beside, isPlate ->
            if (isPlate) "fish:${beside.size}" else "dog:${beside.size}"
        }
        assertEquals(listOf(face.copy(sticker = "dog:1"), plate.copy(sticker = "fish:2", plate = true)), patches)
        assertTrue(FoundPatches(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun aPatchLaidBesideAPlateIsAPlate() {
        val plate = Mask(0.40f, 0.70f, 0.50f, 0.74f, plate = true)
        assertTrue(Mask.around(0.45f, 0.65f, shape = 1.5f, beside = listOf(plate)).plate)
        assertTrue(!Mask.around(0.45f, 0.65f, shape = 1.5f).plate)
    }

    @Test
    fun aStickerCoversItsPatchWhole() {
        // A plate's patch, some 2.6 times as wide as high, and a face's square.
        for ((width, height) in listOf(260f to 100f, 100f to 100f, 80f to 120f)) {
            for (sticker in Sticker.entries) {
                val scale = sticker.scaleOver(width, height)
                assertTrue(sticker.coverWidth * scale >= width - 1e-3f && sticker.coverHeight * scale >= height - 1e-3f, sticker.key)
            }
        }
    }

    @Test
    fun aPlateReadTwiceIsCoveredOnce() {
        val whole = Mask(0.40f, 0.70f, 0.50f, 0.74f)
        // Read again in a tile, a little off; and a plate of its own beside it.
        val tiled = Mask(0.41f, 0.70f, 0.50f, 0.745f)
        val other = Mask(0.52f, 0.70f, 0.60f, 0.74f)
        assertEquals(listOf(whole, other), distinctPatches(listOf(whole, tiled, other)))
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
