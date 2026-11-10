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

    @Test fun `object info gives the name, size and capture time`() {
        val info = Ptp.objectInfo(0x40001001, objectInfo("DSC00042.JPG", 0x3801, 8_123_456, "20261031T143005"))
        assertEquals("DSC00042.JPG", info.name)
        assertEquals(8_123_456L, info.size)
        assertEquals(LocalDateTime.of(2026, 10, 31, 14, 30, 5), info.captured)
        assertTrue(info.photo)
        assertEquals("image/jpeg", info.mime)
        val raw = Ptp.objectInfo(2, objectInfo("DSC00043.ARW", 0xB101, 3_000_000_000, "20261031T143006.0+0800"))
        assertTrue(raw.raw && !raw.photo)
        assertEquals(3_000_000_000L, raw.size)
        assertEquals(LocalDateTime.of(2026, 10, 31, 14, 30, 6), raw.captured)
        assertTrue(Ptp.objectInfo(3, objectInfo("HIF", 0x3001, 0, "")).folder)
        assertEquals("image/heif", Ptp.objectInfo(4, objectInfo("DSC00044.HIF", 0xB110, 1, "")).mime)
    }

    @Test fun `session opens, lists the card and downloads in chunks`() {
        val file = ByteArray(9 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val camera = FakeCamera(file, partial = true)
        val ptp = PtpIpConnection.open(SocketFactory.getDefault(), "127.0.0.1", UUID.randomUUID(), "test", port = camera.port)
        SonySession(ptp).use { session ->
            session.open()
            val storages = session.storages()
            assertArrayEquals(intArrayOf(0x00010001), storages)
            val handles = session.handles(storages[0])
            assertArrayEquals(intArrayOf(7, 8), handles)
            val info = session.info(8)
            assertEquals("DSC00008.JPG", info.name)
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3), session.thumb(8))
            val out = ByteArrayOutputStream()
            val seen = mutableListOf<Long>()
            session.download(info, out, { seen += it })
            assertArrayEquals(file, out.toByteArray())
            assertEquals(listOf(4L * 1024 * 1024, 8L * 1024 * 1024, file.size.toLong()), seen)
        }
        assertEquals(listOf(0x9210, 0x9201, 0x9201, 0x9202, 0x9201, 0x9212, 0x9212), camera.operations.take(7))
        assertEquals(listOf(1, 1), camera.params[0x9210])
        assertEquals(listOf(2, 1, 0), camera.params[0x9212])
    }

    @Test fun `download falls back to the whole object without partial reads`() {
        val file = ByteArray(100_000) { it.toByte() }
        val camera = FakeCamera(file, partial = false)
        val ptp = PtpIpConnection.open(SocketFactory.getDefault(), "127.0.0.1", UUID.randomUUID(), "test", port = camera.port)
        SonySession(ptp).use { session ->
            session.open()
            val out = ByteArrayOutputStream()
            session.download(session.info(8), out)
            assertArrayEquals(file, out.toByteArray())
        }
    }

    private fun objectInfo(name: String, format: Int, size: Long, captured: String): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00010001).putShort(format.toShort()).putShort(0).putInt(size.toInt())
        while (b.position() < 52) b.put(0)
        fun str(s: String) {
            if (s.isEmpty()) { b.put(0); return }
            b.put((s.length + 1).toByte()); s.forEach { b.putChar(it) }; b.putChar('\u0000')
        }
        str(name); str(captured); str(""); str("")
        return b.array().copyOf(b.position())
    }

    /** A camera that answers as the a7C II does to an app's contents-transfer session, on a loopback port. */
    private inner class FakeCamera(private val file: ByteArray, private val partial: Boolean) {
        private val server = ServerSocket(0)
        val port = server.localPort
        val operations = mutableListOf<Int>()
        val params = mutableMapOf<Int, List<Int>>()
        private var opened = false

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
                fun ints(vararg v: Int) = PtpIp.le(4 + v.size * 4).putInt(v.size).apply { v.forEach { putInt(it) } }.array()
                when (code) {
                    Ptp.SONY_OPEN_SESSION -> respond()
                    Ptp.SONY_SET_CONTENTS_TRANSFER_MODE -> {
                        respond()
                        if (p.getOrNull(1) == 1) {
                            opened = true
                            events.write(PtpIp.packet(PtpIp.EVENT, PtpIp.le(10).putShort(Ptp.EVENT_STORE_ADDED.toShort()).putInt(0).putInt(0x00010001).array()))
                        }
                    }
                    Ptp.GET_STORAGE_IDS -> if (opened) data(ints(0x00010001)) else respond(Ptp.RESPONSE_STORE_NOT_AVAILABLE)
                    Ptp.GET_OBJECT_HANDLES -> data(ints(7, 8))
                    Ptp.GET_OBJECT_INFO -> data(objectInfo("DSC0000${p[0]}.JPG", 0x3801, file.size.toLong(), "20261101T090000"))
                    Ptp.GET_THUMB -> data(byteArrayOf(0x09, 0, 0, 0, 0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
                    Ptp.SONY_GET_PARTIAL_LARGE_OBJECT -> if (!partial) respond(0x2005) else {
                        val offset = (p[1].toLong() and 0xFFFFFFFFL) or (p[2].toLong() shl 32)
                        val length = (p[3].toLong() and 0xFFFFFFFFL) or (p[4].toLong() shl 32)
                        data(file.copyOfRange(offset.toInt(), minOf(file.size.toLong(), offset + length).toInt()))
                    }
                    Ptp.GET_OBJECT -> data(file)
                    Ptp.CLOSE_SESSION -> { respond(); return }
                    else -> respond()
                }
                output.flush()
            }
        }
    }
}

