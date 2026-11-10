package cc.hyoban.moments.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The PTP and Sony operations, responses and events that taking photos off the camera and
 * deleting them use (Sony's Camera Control PTP 3 Reference), and the contents list it reads.
 */
object Ptp {
    const val CLOSE_SESSION = 0x1003

    /** Sony's: open a session in a mode, the second parameter (2 = remote control with transfer). */
    const val SONY_OPEN_SESSION = 0x9210
    const val SONY_CONNECT = 0x9201
    const val SONY_GET_EXT_DEVICE_INFO = 0x9202
    /** The card as contents, each a content ID within its slot. */
    const val SONY_GET_CONTENT_INFO_LIST = 0x923C
    /** A content's file, a piece at a time (by content ID, slot and file ID). */
    const val SONY_GET_CONTENT_DATA = 0x923D
    /** A content's thumbnail (1) or screennail (2, about 2 megapixels). */
    const val SONY_GET_CONTENT_COMPRESSED_DATA = 0x923E
    /** Deletes a content (all its files) from the card; the result comes as [EVENT_DELETE_CONTENT_RESULT]. */
    const val SONY_DELETE_CONTENT = 0x9250

    const val RESPONSE_OK = 0x2001

    /** The card's contents changed: a photo taken or deleted. */
    const val EVENT_CONTENT_INFO_LIST_CHANGED = 0xC234
    const val EVENT_DELETE_CONTENT_RESULT = 0xC240

    /** A content on the card as Sony lists it: one shot, with its files (a JPEG and its RAW are one content). */
    data class Content(val id: Int, val slot: Int, val protected: Boolean, val files: List<ContentFile>, val captured: LocalDateTime? = null)

    /** One file of a content: its ID within it, path on the card, format and size. */
    data class ContentFile(val id: Int, val path: String, val format: Int, val size: Long) {
        val name get() = path.substringAfterLast('/')
        val extension get() = name.substringAfterLast('.', "").uppercase()
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

    /**
     * A ContentInfoList: the slot, then each content with its capture time (the camera's clock,
     * which keeps local time) and its files; a content whose type is 0 was deleted while the
     * list was made, and ends it.
     */
    internal fun contentInfoList(data: ByteArray): List<Content> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4 + 8 + 64 + 64)
        val slot = b.int
        val count = b.int
        val out = mutableListOf<Content>()
        repeat(count) {
            val type = b.int
            if (type == 0) return out
            val id = b.int
            // Directory and file numbers, group, representative; creation and modification in UTC.
            b.position(b.position() + 5 * 4 + 2 * 8)
            val createdLocal = b.long
            // Modification in local time, rating.
            b.position(b.position() + 8 + 4)
            val protected = b.int != 0
            b.int // dummy
            val marks = b.int
            b.position(b.position() + marks)
            val files = List(b.int) {
                val fileId = b.short.toInt() and 0xFFFF
                b.short
                val path = ByteArray(b.int).also { b.get(it) }
                val format = b.int
                val size = b.long
                b.position(b.position() + 32) // UMID
                if (b.int != 0) b.position(b.position() + 2 * 4) // image
                if (b.int != 0) b.position(b.position() + 19 * 4) // video
                if (b.int != 0) b.position(b.position() + 4 * 4) // audio
                ContentFile(fileId, String(path, Charsets.UTF_8).trimEnd('\u0000'), format, size)
            }
            val captured = createdLocal.takeIf { it > 0 }?.let { LocalDateTime.ofEpochSecond(it / 1000, ((it % 1000) * 1_000_000).toInt(), ZoneOffset.UTC) }
            out += Content(id, slot, protected, files, captured)
        }
        return out
    }
}
