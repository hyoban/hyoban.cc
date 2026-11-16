package cc.hyoban.moments.data.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The same cases as @hyoban/data-spec's location tests, at a made-up place.
class LocationTest {
    /** A JPEG whose EXIF has the camera's model, an orientation and a GPS IFD with a latitude. */
    private fun photoAt(): ByteArray {
        val tiff = ByteBuffer.allocate(80)
        tiff.putInt(0, 0x4d4d002a).putInt(4, 8)
        tiff.putShort(8, 2)
        tiff.putShort(10, 0x0112).putShort(12, 3).putInt(14, 1).putShort(18, 6) // Orientation 6
        tiff.putShort(22, 0x8825.toShort()).putShort(24, 4).putInt(26, 1).putInt(30, 38)
        tiff.putShort(38, 1)
        tiff.putShort(40, 2).putShort(42, 5).putInt(44, 3).putInt(48, 56) // GPSLatitude
        listOf(12, 1, 34, 1, 56, 1).forEachIndexed { index, value -> tiff.putInt(56 + index * 4, value) }
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) +
            exif + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { all, child -> all + child }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + body.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(body)
        }
        return out.toByteArray()
    }

    private fun u32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    @Test
    fun blanksTheGpsOfAnExifInPlaceKeepingTheRest() {
        val before = photoAt()
        val after = before.copyOf()

        assertTrue(Location.strip(after) > 0)
        assertEquals(before.size, after.size)
        assertTrue(Location.find(after).isEmpty())
        // Up to the GPS IFD (the orientation among it) is as it was; the IFD and its latitude are zeros.
        assertContentEquals(before.copyOfRange(0, 50), after.copyOfRange(0, 50))
        assertTrue(after.copyOfRange(50, 92).all { it == 0.toByte() })
        assertTrue(before.copyOfRange(50, 92).any { it != 0.toByte() })
    }

    @Test
    fun leavesAFileThatSaysNothingOfWhereItWasAlone() {
        assertTrue(Location.find(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())).isEmpty())
    }

    @Test
    fun blanksTheGpsOfAnXmpPacket() {
        val xmp = "<x:xmpmeta><rdf:Description exif:GPSLatitude=\"12,34.93N\" exif:DateTimeOriginal=\"2020\">" +
            "<exif:GPSAltitude>120/1</exif:GPSAltitude></rdf:Description></x:xmpmeta>"
        val file = xmp.toByteArray(Charsets.ISO_8859_1)

        Location.strip(file)
        val text = String(file, Charsets.ISO_8859_1)

        assertTrue("exif:GPSLatitude=\"         \"" in text)
        assertTrue("<exif:GPSAltitude>     </exif:GPSAltitude>" in text)
        assertTrue("exif:DateTimeOriginal=\"2020\"" in text)
        assertTrue(Location.find(file).isEmpty())
    }

    private fun video(): ByteArray {
        val where = "+12.5823+098.1181/".toByteArray(Charsets.ISO_8859_1)
        val key = "com.apple.quicktime.location.ISO6709".toByteArray(Charsets.ISO_8859_1)
        return box("ftyp", "isom".toByteArray(), u32(0)) +
            box(
                "moov",
                box("udta", box("©xyz", u32(0), where), box("loci", where)),
                box(
                    "meta",
                    box("hdlr", ByteArray(12)),
                    box("keys", u32(0), u32(1), u32(8 + key.size), "mdta".toByteArray(), key),
                    box("ilst", box("\u0000\u0000\u0000\u0001", box("data", u32(1), u32(0), where))),
                ),
            ) +
            box("mdat", "frames".toByteArray())
    }

    @Test
    fun turnsAVideosLocationBoxesIntoFreeSpaceAndZeroesKeyedLocations() {
        val file = File.createTempFile("location-", ".mp4").apply { writeBytes(video()) }
        try {
            val copy = Location.stripped(file, video = true)
            try {
                val text = String(copy.readBytes(), Charsets.ISO_8859_1)

                assertEquals(file.length(), copy.length())
                assertFalse("+12.5823" in text)
                assertEquals(2, text.split("free").size - 1)
                assertTrue("frames" in text)
                assertSame(copy, Location.stripped(copy, video = true), "a second pass finds nothing")
                assertTrue("+12.5823" in String(file.readBytes(), Charsets.ISO_8859_1), "the phone's own keeps it")
            } finally {
                copy.delete()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun findsALocationInAVideoAMotionPhotoCarriesAfterItsStill() {
        val file = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()) + video()

        assertTrue(Location.strip(file) > 0)
        assertTrue(Location.find(file).isEmpty())
    }

    @Test
    fun anImageWithNothingToTakeOutIsSentAsItIs() {
        val file = File.createTempFile("location-", ".jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
        try {
            assertSame(file, Location.stripped(file, video = false))
        } finally {
            file.delete()
        }
    }
}
