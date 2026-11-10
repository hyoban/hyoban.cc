package cc.hyoban.moments.camera

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.SocketFactory

/**
 * PTP over TCP (PTP/IP, CIPA DC-005) as far as taking photos off the camera needs it: the two
 * connections, commands with their data coming back, and the events the camera sends. Every
 * number is little-endian.
 */
internal object PtpIp {
    const val PORT = 15740

    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14

    /** One packet: its type and what follows its header. */
    class Packet(val type: Int, val payload: ByteArray)

    fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun packet(type: Int, payload: ByteArray): ByteArray =
        le(8 + payload.size).putInt(8 + payload.size).putInt(type).put(payload).array()

    fun initCommand(guid: UUID, name: String): ByteArray {
        val chars = name.take(39) + '\u0000'
        val body = le(16 + chars.length * 2 + 4)
        body.putLong(guid.mostSignificantBits).putLong(guid.leastSignificantBits)
        chars.forEach { body.putChar(it) }
        body.putInt(0x00010000)
        return packet(INIT_COMMAND_REQUEST, body.array())
    }

    fun initEvent(connection: Int): ByteArray = packet(INIT_EVENT_REQUEST, le(4).putInt(connection).array())

    /** A command, with no data from the phone after it (none here sends any). */
    fun request(code: Int, transaction: Int, params: IntArray): ByteArray {
        val body = le(4 + 2 + 4 + params.size * 4)
        body.putInt(1).putShort(code.toShort()).putInt(transaction)
        params.forEach { body.putInt(it) }
        return packet(OPERATION_REQUEST, body.array())
    }

    fun read(input: InputStream): Packet {
        val head = input.readFully(8)
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.int
        if (length < 8 || length > MAX_PACKET) throw IOException("bad PTP/IP packet length $length")
        return Packet(type, input.readFully(length - 8))
    }

    private fun InputStream.readFully(size: Int): ByteArray {
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = read(out, at, size - at)
            if (n < 0) throw EOFException()
            at += n
        }
        return out
    }

    /** Larger than any data packet the camera sends (it splits a file into many). */
    private const val MAX_PACKET = 64 * 1024 * 1024
}

/** What the camera answered a command: its response code, and any data it sent. */
internal class PtpResponse(val code: Int, val data: ByteArray) {
    val ok get() = code == Ptp.RESPONSE_OK
}

internal class PtpException(code: Int, operation: Int) :
    IOException("PTP 0x%04X answered 0x%04X".format(operation, code))

/** An event's or a response's code, then (past the transaction id) its parameters. */
private fun codeAndParams(payload: ByteArray): Pair<Int, IntArray> {
    val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val code = b.short.toInt() and 0xFFFF
    b.int
    return code to IntArray(b.remaining() / 4) { b.int }
}

private fun OutputStream.answerProbe() {
    write(PtpIp.packet(PtpIp.PROBE_RESPONSE, ByteArray(0)))
    flush()
}

/**
 * One connection to the camera: the command connection and the event one, each a socket from
 * [sockets] (the camera's Wi‑Fi network's, so the traffic stays on it). One command at a time.
 */
internal class PtpIpConnection private constructor(
    private val command: Socket,
    private val event: Socket,
) : Closeable {
    private val input = command.getInputStream().buffered(256 * 1024)
    private val output: OutputStream = command.getOutputStream()
    private var transaction = 0
    @Volatile private var closed = false

    /** Events as they come, read on a thread of their own; a probe is answered there. */
    fun events(onEvent: (code: Int, params: IntArray) -> Unit): Thread = Thread {
        val events = event.getInputStream()
        val reply = event.getOutputStream()
        try {
            while (!closed) {
                val packet = PtpIp.read(events)
                when (packet.type) {
                    PtpIp.PROBE_REQUEST -> reply.answerProbe()
                    PtpIp.EVENT -> codeAndParams(packet.payload).let { (code, params) -> onEvent(code, params) }
                }
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; name = "ptpip-events"; start() }

    /**
     * Sends [code] with [params] and waits for its answer. Data the camera sends with it goes to
     * [sink] as it comes, chunk by chunk, if given, else into the response.
     */
    @Synchronized
    fun call(code: Int, vararg params: Int, sink: ((ByteArray, Int, Int) -> Unit)? = null): PtpResponse {
        val id = ++transaction
        output.write(PtpIp.request(code, id, params))
        output.flush()
        val collected = if (sink == null) ByteArrayOutputStream() else null
        while (true) {
            val packet = PtpIp.read(input)
            when (packet.type) {
                PtpIp.START_DATA -> Unit
                PtpIp.DATA, PtpIp.END_DATA -> {
                    // After the transaction id.
                    val size = packet.payload.size - 4
                    if (sink != null) sink(packet.payload, 4, size) else collected!!.write(packet.payload, 4, size)
                }
                PtpIp.OPERATION_RESPONSE -> return PtpResponse(codeAndParams(packet.payload).first, collected?.toByteArray() ?: ByteArray(0))
                PtpIp.PROBE_REQUEST -> output.answerProbe()
                else -> throw IOException("unexpected PTP/IP packet ${packet.type}")
            }
        }
    }

    /** [call] that throws unless the camera says OK. */
    fun ok(code: Int, vararg params: Int, sink: ((ByteArray, Int, Int) -> Unit)? = null): PtpResponse =
        call(code, *params, sink = sink).also { if (!it.ok) throw PtpException(it.code, code) }

    override fun close() {
        closed = true
        runCatching { command.close() }
        runCatching { event.close() }
    }

    companion object {
        /** Opens both connections to [host], introducing the phone as [name] under [guid]. */
        fun open(sockets: SocketFactory, host: String, guid: UUID, name: String, timeoutMs: Int = 10_000, port: Int = PtpIp.PORT): PtpIpConnection {
            val command = sockets.createSocket().apply { tcpNoDelay = true; soTimeout = 30_000; connect(InetSocketAddress(host, port), timeoutMs) }
            try {
                command.getOutputStream().apply { write(PtpIp.initCommand(guid, name)); flush() }
                val ack = PtpIp.read(command.getInputStream())
                if (ack.type == PtpIp.INIT_FAIL) throw IOException("camera refused the connection")
                if (ack.type != PtpIp.INIT_COMMAND_ACK) throw IOException("unexpected PTP/IP packet ${ack.type}")
                val connection = ByteBuffer.wrap(ack.payload).order(ByteOrder.LITTLE_ENDIAN).int
                val event = sockets.createSocket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), timeoutMs) }
                try {
                    event.getOutputStream().apply { write(PtpIp.initEvent(connection)); flush() }
                    event.soTimeout = 30_000
                    val eventAck = PtpIp.read(event.getInputStream())
                    if (eventAck.type != PtpIp.INIT_EVENT_ACK) throw IOException("camera refused the event connection")
                    // Events may be minutes apart.
                    event.soTimeout = 0
                    return PtpIpConnection(command, event)
                } catch (e: Exception) { event.close(); throw e }
            } catch (e: Exception) { command.close(); throw e }
        }
    }
}
