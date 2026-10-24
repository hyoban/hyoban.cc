package cc.hyoban.moments

import cc.hyoban.moments.data.local.MotionPhotos
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotosTest {
    private val directory: File = Files.createTempDirectory("motion").toFile()

    /** A JPEG's bytes as a phone writes them, its XMP naming the clip after it as [xmp] does. */
    private fun photo(xmp: String, clip: ByteArray): Pair<File, ByteArray> {
        val still = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "<x:xmpmeta>$xmp</x:xmpmeta>".toByteArray() + ByteArray(100) { 7 } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val file = File(directory, "photo.jpg").apply { writeBytes(still + clip) }
        return file to still
    }

    private val clip = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + ByteArray(50) { 3 }

    @Test
    fun `a motion photo in Google's container comes apart into its still and its clip`() {
        val (file, still) = photo(
            """
            <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
            <Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="834519" Item:Padding="0"/>
            <Container:Item
              Item:Mime="video/mp4"
              Item:Semantic="MotionPhoto"
              Item:Length="${clip.size}"/>
            """,
            clip,
        )
        val video = File(directory, "photo.mp4")

        assertTrue(MotionPhotos.split(file, video))
        assertContentEquals(still, file.readBytes())
        assertContentEquals(clip, video.readBytes())
    }

    @Test
    fun `the older MicroVideo offset counts from the end`() {
        val (file, still) = photo("""GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${clip.size}"""", clip)

        assertEquals(still.size.toLong(), MotionPhotos.videoStart(file))
    }

    @Test
    fun `a plain photo, or one whose XMP doesn't point at an MP4, is left as it is`() {
        val (plain, _) = photo("""<rdf:Description/>""", ByteArray(0))
        assertNull(MotionPhotos.videoStart(plain))

        val (wrong, _) = photo("""<Container:Item Item:Semantic="MotionPhoto" Item:Length="20"/>""", clip)
        val bytes = wrong.readBytes()
        assertFalse(MotionPhotos.split(wrong, File(directory, "wrong.mp4")))
        assertContentEquals(bytes, wrong.readBytes())
    }
}
