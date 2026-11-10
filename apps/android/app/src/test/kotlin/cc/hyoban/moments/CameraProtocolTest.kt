package cc.hyoban.moments

import cc.hyoban.moments.camera.Ptp
import cc.hyoban.moments.camera.PtpIp
import cc.hyoban.moments.camera.PtpIpConnection
import cc.hyoban.moments.camera.SonyBle
import cc.hyoban.moments.camera.SonySession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** The camera's protocols against made-up bytes laid out as Sony's notes and the PTP/IP standard give them. */
class CameraProtocolTest {
    @Test fun `location packet without time zone`() {
        val packet = SonyBle.locationPacket(48.137154, 11.575533, Instant.parse("2026-11-10T08:05:09Z"), ZoneId.of("Europe/Berlin"), withZone = false)
        assertEquals(91, packet.size)
        val b = ByteBuffer.wrap(packet)
        assertEquals(89, b.short.toInt())
        assertArrayEquals(byteArrayOf(0x08, 0x02, 0xFC.toByte(), 0x00, 0x00, 0x00, 0x10, 0x10, 0x10), packet.copyOfRange(2, 11))
        b.position(11)
        assertEquals(481371540, b.int)
        assertEquals(115755330, b.int)
        assertEquals(2026, b.short.toInt())
        assertArrayEquals(byteArrayOf(11, 10, 8, 5, 9), packet.copyOfRange(21, 26))
        assertTrue(packet.copyOfRange(26, 91).all { it == 0.toByte() })
    }

    @Test fun `location packet with time zone and daylight saving`() {
        val summer = SonyBle.locationPacket(31.2, 121.5, Instant.parse("2026-07-01T00:00:00Z"), ZoneId.of("Europe/Berlin"), withZone = true)
        assertEquals(95, summer.size)
        val b = ByteBuffer.wrap(summer)
        assertEquals(93, b.short.toInt())
        assertEquals(0x03, summer[5].toInt())
        b.position(91)
        assertEquals(60, b.short.toInt())
        assertEquals(60, b.short.toInt())
        val shanghai = ByteBuffer.wrap(SonyBle.locationPacket(31.2, 121.5, Instant.parse("2026-07-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"), withZone = true))
        shanghai.position(91)
        assertEquals(480, shanghai.short.toInt())
        assertEquals(0, shanghai.short.toInt())
    }

    @Test fun `negative coordinates round to the nearest step`() {
        val b = ByteBuffer.wrap(SonyBle.locationPacket(-33.8688, -151.2093, Instant.EPOCH, ZoneId.of("UTC"), withZone = false))
        b.position(11)
        assertEquals(-338688000, b.int)
        assertEquals(-1512093000, b.int)
    }

    @Test fun `time zone taken from the capability byte`() {
        assertTrue(SonyBle.takesTimeZone(byteArrayOf(0, 0, 0, 0, 0x02)))
        assertFalse(SonyBle.takesTimeZone(byteArrayOf(0, 0, 0, 0, 0x01)))
        assertFalse(SonyBle.takesTimeZone(byteArrayOf(0, 0)))
        assertFalse(SonyBle.takesTimeZone(null))
    }

    @Test fun `wifi credentials after their header`() {
        assertEquals("DIRECT-ab12:EXAMPLE", SonyBle.credential(byteArrayOf(0, 0, 0) + "DIRECT-ab12:EXAMPLE".toByteArray()))
        assertEquals("pass1234", SonyBle.credential(byteArrayOf(1, 2, 3) + "pass1234".toByteArray() + byteArrayOf(0)))
        assertNull(SonyBle.credential(byteArrayOf(0, 0, 0)))
        assertNull(SonyBle.credential(null))
    }

    @Test fun `init command carries the guid, the name and the version`() {
        val guid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val packet = PtpIp.read(ByteArrayInputStream(PtpIp.initCommand(guid, "Pixel")))
        assertEquals(PtpIp.INIT_COMMAND_REQUEST, packet.type)
        assertEquals(16 + 6 * 2 + 4, packet.payload.size)
        val b = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
        b.position(16)
        assertEquals("Pixel\u0000", String(CharArray(6) { b.char }))
        assertEquals(0x00010000, b.int)
    }

    @Test fun `content list gives each content's id, slot and files`() {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(100).putShort(0).putLong(0)
        b.put(ByteArray(64)).put(ByteArray(64)).putInt(1)
        b.putInt(3)
        fun content(id: Int, protected: Boolean, files: List<Pair<String, Int>>, image: Boolean) {
            b.putInt(1).putInt(id).putInt(100).putInt(id).putInt(0).putInt(0).putInt(1)
            b.putLong(0).putLong(0)
            b.putLong(java.time.LocalDateTime.of(2026, 11, 10, 13, 30, 5).toEpochSecond(java.time.ZoneOffset.UTC) * 1000).putLong(0)
            b.putInt(0).putInt(if (protected) 1 else 0).putInt(0)
            b.putInt(2).put(1).put(0)
            b.putInt(files.size)
            files.forEachIndexed { i, (path, format) ->
                val bytes = (path + "\u0000").toByteArray()
                b.putShort(i.toShort()).putShort(0).putInt(bytes.size).put(bytes).putInt(format).putLong(1000L + i)
                b.put(ByteArray(32))
                b.putInt(if (image) 1 else 0); if (image) b.putInt(6000).putInt(4000)
                b.putInt(0).putInt(0)
            }
        }
        content(131073, false, listOf("A:/DCIM/100MSDCF/DSC00001.JPG" to 0x3801, "A:/DCIM/100MSDCF/DSC00001.ARW" to 0xB101), image = true)
        content(131074, true, listOf("A:/DCIM/100MSDCF/DSC00002.HIF" to 0xB110), image = false)
        // Deleted while the list was made: zeros from here.
        val list = Ptp.contentInfoList(b.array())
        assertEquals(2, list.size)
        assertEquals(131073, list[0].id)
        assertEquals(1, list[0].slot)
        assertEquals(listOf(Ptp.ContentFile(0, "A:/DCIM/100MSDCF/DSC00001.JPG", 0x3801, 1000), Ptp.ContentFile(1, "A:/DCIM/100MSDCF/DSC00001.ARW", 0xB101, 1001)), list[0].files)
        assertEquals("DSC00001.JPG", list[0].files[0].name)
        assertEquals(LocalDateTime.of(2026, 11, 10, 13, 30, 5), list[0].captured)
        assertEquals(131074, list[1].id)
        assertTrue(list[1].protected)
        assertEquals(listOf(Ptp.ContentFile(0, "A:/DCIM/100MSDCF/DSC00002.HIF", 0xB110, 1000)), list[1].files)
    }

    @Test fun `session lists the card, reads a thumbnail, downloads in chunks and deletes`() {
        val file = ByteArray(9 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val camera = FakeCamera(file)
        val ptp = PtpIpConnection.open(SocketFactory.getDefault(), "127.0.0.1", UUID.randomUUID(), "test", port = camera.port)
        SonySession(ptp).use { session ->
            var changed = 0
            session.onContentsChanged = { changed++ }
            session.open()
            val contents = session.contents()
            assertEquals(1, contents.size)
            val content = contents[0]
            val jpeg = content.files.first { it.photo }
            assertEquals("DSC00001.JPG", jpeg.name)
            assertEquals(file.size.toLong(), jpeg.size)
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3), session.thumb(content, jpeg))
            val out = ByteArrayOutputStream()
            val seen = mutableListOf<Long>()
            session.download(content, jpeg, out, { seen += it })
            assertArrayEquals(file, out.toByteArray())
            assertEquals(listOf(4L * 1024 * 1024, 8L * 1024 * 1024, file.size.toLong()), seen)
            assertTrue(session.delete(content))
            Thread.sleep(200)
            assertEquals(1, changed)
        }
        assertEquals(listOf(0x9210, 0x9201, 0x9201, 0x9202, 0x9201), camera.operations.take(5))
        assertEquals(listOf(1, 2), camera.params[0x9210])
        assertEquals(listOf(131073, 1), camera.params[0x9250])
        // The file is named by slot (top byte) and file ID.
        assertEquals((1 shl 24) or 1, camera.params[0x923E]!![1])
    }

    /** A ContentInfoList as the a7C II sends one: made-up contents, each with its files. */
    private fun contentList(vararg contents: Triple<Int, Boolean, List<Pair<String, Long>>>, captured: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 11, 10, 13, 30, 5)): ByteArray {
        val b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(100).putShort(0).putLong(0)
        b.put(ByteArray(64)).put(ByteArray(64)).putInt(1)
        b.putInt(contents.size + 1)
        for ((id, protected, files) in contents) {
            b.putInt(1).putInt(id).putInt(100).putInt(id).putInt(0).putInt(0).putInt(1)
            b.putLong(0).putLong(0)
            b.putLong(captured.toEpochSecond(java.time.ZoneOffset.UTC) * 1000).putLong(0)
            b.putInt(0).putInt(if (protected) 1 else 0).putInt(0)
            b.putInt(2).put(1).put(0)
            b.putInt(files.size)
            files.forEachIndexed { i, (path, size) ->
                val bytes = (path + "\u0000").toByteArray()
                val format = if (path.endsWith(".ARW")) 0xB101 else if (path.endsWith(".HIF")) 0xB110 else 0x3801
                b.putShort((i + 1).toShort()).putShort(0).putInt(bytes.size).put(bytes).putInt(format).putLong(size)
                b.put(ByteArray(32))
                b.putInt(1).putInt(6000).putInt(4000)
                b.putInt(0).putInt(0)
            }
        }
        // One more counted, deleted while the list was made: zeros from here.
        return b.array()
    }

    /** A camera that answers as the a7C II does in remote control with transfer, on a loopback port. */
    private inner class FakeCamera(private val file: ByteArray) {
        private val server = ServerSocket(0)
        val port = server.localPort
        val operations = mutableListOf<Int>()
        val params = mutableMapOf<Int, List<Int>>()

        init {
            thread(isDaemon = true) {
                val command = server.accept()
                val input = command.getInputStream()
                val output = command.getOutputStream()
                PtpIp.read(input)
                output.write(PtpIp.packet(PtpIp.INIT_COMMAND_ACK, PtpIp.le(4 + 16 + 2 + 4).putInt(42).array()))
                val event = server.accept()
                PtpIp.read(event.getInputStream())
                event.getOutputStream().write(PtpIp.packet(PtpIp.INIT_EVENT_ACK, ByteArray(0)))
                runCatching { serve(input, output, event.getOutputStream()) }
                command.close(); event.close(); server.close()
            }
        }

        private fun serve(input: InputStream, output: OutputStream, events: OutputStream) {
            while (true) {
                val packet = PtpIp.read(input)
                val b = ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN)
                b.int
                val code = b.short.toInt() and 0xFFFF
                val id = b.int
                val p = List(b.remaining() / 4) { b.int }
                synchronized(operations) { operations += code; params[code] = p }
                fun respond(response: Int = Ptp.RESPONSE_OK) =
                    output.write(PtpIp.packet(PtpIp.OPERATION_RESPONSE, PtpIp.le(6).putShort(response.toShort()).putInt(id).array()))
                fun event(code: Int, vararg params: Int) =
                    events.write(PtpIp.packet(PtpIp.EVENT, PtpIp.le(6 + params.size * 4).putShort(code.toShort()).putInt(0).apply { params.forEach { putInt(it) } }.array()))
                fun data(bytes: ByteArray) {
                    output.write(PtpIp.packet(PtpIp.START_DATA, PtpIp.le(12).putInt(id).putLong(bytes.size.toLong()).array()))
                    var at = 0
                    while (at < bytes.size) {
                        val n = minOf(65536, bytes.size - at)
                        val last = at + n == bytes.size
                        output.write(PtpIp.packet(if (last) PtpIp.END_DATA else PtpIp.DATA, PtpIp.le(4 + n).putInt(id).put(bytes, at, n).array()))
                        at += n
                    }
                    respond()
                }
                when (code) {
                    Ptp.SONY_GET_CONTENT_INFO_LIST -> data(contentList(Triple(131073, false, listOf("A:/DCIM/100MSDCF/DSC00001.JPG" to file.size.toLong(), "A:/DCIM/100MSDCF/DSC00001.ARW" to 1000L))))
                    Ptp.SONY_GET_CONTENT_COMPRESSED_DATA -> data(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
                    Ptp.SONY_GET_CONTENT_DATA -> {
                        val offset = (p[2].toLong() and 0xFFFFFFFFL) or (p[3].toLong() shl 32)
                        data(file.copyOfRange(offset.toInt(), minOf(file.size.toLong(), offset + p[4]).toInt()))
                    }
                    Ptp.SONY_DELETE_CONTENT -> {
                        respond()
                        output.flush()
                        event(Ptp.EVENT_DELETE_CONTENT_RESULT, 1, p[0], p[1])
                        event(Ptp.EVENT_CONTENT_INFO_LIST_CHANGED, 1, 2, 0)
                    }
                    Ptp.CLOSE_SESSION -> { respond(); return }
                    else -> respond()
                }
                output.flush()
            }
        }
    }
}
