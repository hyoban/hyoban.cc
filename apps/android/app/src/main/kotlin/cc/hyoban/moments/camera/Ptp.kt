package cc.hyoban.moments.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** PTP's operations, responses and events that taking photos off the camera uses, and Sony's own. */
object Ptp {
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A

    /** Sony's: open a session in a mode, the second parameter (1 = contents transfer). */
    const val SONY_OPEN_SESSION = 0x9210
    const val SONY_CONNECT = 0x9201
    const val SONY_GET_EXT_DEVICE_INFO = 0x9202
    /** A 64-bit offset and length into an object: the one partial read a contents-transfer session takes. */
    const val SONY_GET_PARTIAL_LARGE_OBJECT = 0x9211
    const val SONY_SET_CONTENTS_TRANSFER_MODE = 0x9212

    const val RESPONSE_OK = 0x2001
    const val RESPONSE_STORE_NOT_AVAILABLE = 0x2013

    const val EVENT_STORE_ADDED = 0x4004

    const val FORMAT_ASSOCIATION = 0x3001

    /** What GetObjectInfo says of one object, as far as choosing and saving a photo needs it. */
    data class ObjectInfo(
        val handle: Int,
        val format: Int,
        val size: Long,
        val name: String,
        /** When it was taken, by the camera's clock, which keeps local time. */
        val captured: LocalDateTime?,
    ) {
        val extension get() = name.substringAfterLast('.', "").uppercase()
        val folder get() = format == FORMAT_ASSOCIATION
        /** What a Moment takes: JPEG and HEIF; RAW is kept on the camera. */
        val photo get() = extension in PHOTO_EXTENSIONS
        val raw get() = extension in RAW_EXTENSIONS
        val mime get() = when (extension) {
            "JPG", "JPEG" -> "image/jpeg"
            "HIF", "HEIF", "HEIC" -> "image/heif"
            else -> "application/octet-stream"
        }
    }

    private val PHOTO_EXTENSIONS = setOf("JPG", "JPEG", "HIF", "HEIF", "HEIC")
    private val RAW_EXTENSIONS = setOf("ARW")

    internal fun ints(data: ByteArray): IntArray {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 4) return IntArray(0)
        val count = b.int
        return IntArray(minOf(count, b.remaining() / 4)) { b.int }
    }

    /** An ObjectInfo dataset (PTP 1.1 §5.5.2). */
    internal fun objectInfo(handle: Int, data: ByteArray): ObjectInfo {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.int // storage
        val format = b.short.toInt() and 0xFFFF
        b.short // protection
        val size = b.int.toLong() and 0xFFFFFFFFL
        b.position(52)
        val name = string(b)
        val captured = string(b)
        return ObjectInfo(handle, format, size, name, parseDate(captured))
    }

    /** A PTP string: its length in UTF-16 units with the closing nul, then the units. */
    private fun string(b: ByteBuffer): String {
        if (!b.hasRemaining()) return ""
        val count = b.get().toInt() and 0xFF
        if (count == 0) return ""
        val chars = CharArray(count) { b.char }
        return String(chars).trimEnd('\u0000')
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** "YYYYMMDDThhmmss", with tenths, a zone or neither after it. */
    internal fun parseDate(text: String): LocalDateTime? =
        text.takeIf { it.length >= 15 }?.let { runCatching { LocalDateTime.parse(it.substring(0, 15), DATE) }.getOrNull() }
}
