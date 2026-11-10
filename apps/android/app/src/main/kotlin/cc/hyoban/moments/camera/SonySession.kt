package cc.hyoban.moments.camera

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * A session with the camera over [ptp] in remote control with transfer (Sony's mode 2), the one
 * the a7C II both hands over and deletes photos in: the card as Sony's contents, each a content
 * ID within its slot that stays its own whatever else is deleted.
 */
internal class SonySession(private val ptp: PtpIpConnection) : Closeable {
    /** The camera's answers to deletes: content ID to result (1 deleted). */
    private val deleted = ConcurrentHashMap<Int, Int>()
    /** Told, on the event thread, when the card's contents change: a photo taken or deleted. */
    @Volatile var onContentsChanged: () -> Unit = {}

    fun open() {
        ptp.events { code, params ->
            when (code) {
                Ptp.EVENT_DELETE_CONTENT_RESULT -> if (params.size >= 2) deleted[params[1]] = params[0]
                Ptp.EVENT_CONTENT_INFO_LIST_CHANGED -> onContentsChanged()
            }
        }
        ptp.ok(Ptp.SONY_OPEN_SESSION, SESSION, MODE_REMOTE_WITH_TRANSFER)
        ptp.call(Ptp.SONY_CONNECT, 1, 0, 0)
        ptp.call(Ptp.SONY_CONNECT, 2, 0, 0)
        ptp.call(Ptp.SONY_GET_EXT_DEVICE_INFO, EXT_DEVICE_INFO_VERSION)
        ptp.call(Ptp.SONY_CONNECT, 3, 0, 0)
    }

    /** Every content on the card's slot (the a7C II has one). */
    fun contents(slot: Int = 1): List<Ptp.Content> =
        Ptp.contentInfoList(ptp.ok(Ptp.SONY_GET_CONTENT_INFO_LIST, 0, 0, CONTENT_LIMIT, slot, 0).data)

    /** [file]'s thumbnail, a JPEG. */
    fun thumb(content: Ptp.Content, file: Ptp.ContentFile): ByteArray =
        ptp.ok(Ptp.SONY_GET_CONTENT_COMPRESSED_DATA, content.id, fileRef(content, file), THUMBNAIL).data

    /**
     * The whole of [file] to [out], in chunks so [progress] can say how far it is and [cancelled]
     * stop it between them.
     */
    fun download(content: Ptp.Content, file: Ptp.ContentFile, out: OutputStream, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        var offset = 0L
        while (offset < file.size) {
            if (cancelled()) throw CancellationException()
            var got = 0L
            val length = minOf(CHUNK, file.size - offset).toInt()
            ptp.ok(Ptp.SONY_GET_CONTENT_DATA, content.id, fileRef(content, file), offset.toInt(), (offset ushr 32).toInt(), length,
                sink = { b, from, size -> out.write(b, from, size); got += size })
            if (got == 0L) throw IOException("the camera sent nothing")
            offset += got
            progress(offset)
        }
    }

    /** Deletes [content] and all its files from the card, true once the camera says it has. */
    fun delete(content: Ptp.Content): Boolean {
        deleted.remove(content.id)
        ptp.ok(Ptp.SONY_DELETE_CONTENT, content.id, content.slot)
        val until = System.currentTimeMillis() + DELETE_WAIT_MS
        while (System.currentTimeMillis() < until) {
            deleted[content.id]?.let { return it == DELETED }
            Thread.sleep(100)
        }
        return false
    }

    private fun fileRef(content: Ptp.Content, file: Ptp.ContentFile) = (content.slot shl 24) or file.id

    override fun close() {
        runCatching { ptp.call(Ptp.CLOSE_SESSION) }
        ptp.close()
    }

    private companion object {
        const val SESSION = 1
        const val MODE_REMOTE_WITH_TRANSFER = 2
        /** What Sony's apps send GetExtDeviceInfo, the version of its codes they speak. */
        const val EXT_DEVICE_INFO_VERSION = 0xC8
        const val CONTENT_LIMIT = 100_000
        const val THUMBNAIL = 1
        const val DELETED = 1
        const val DELETE_WAIT_MS = 10_000L
        const val CHUNK = 4L * 1024 * 1024
    }
}
