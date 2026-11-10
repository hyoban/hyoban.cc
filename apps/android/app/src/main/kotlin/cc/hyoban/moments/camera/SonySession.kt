package cc.hyoban.moments.camera

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * A contents-transfer session with the camera over [ptp]: the whole card readable, as the
 * a7C II opens it to an app (Sony's own open, its handshake, then the transfer mode switched
 * off and on). A plain PTP session sees no card on it.
 */
internal class SonySession(private val ptp: PtpIpConnection) : Closeable {
    @Volatile private var storeAdded = false

    fun open() {
        ptp.events { code, _ -> if (code == Ptp.EVENT_STORE_ADDED) storeAdded = true }
        ptp.ok(Ptp.SONY_OPEN_SESSION, SESSION, MODE_CONTENTS_TRANSFER)
        ptp.call(Ptp.SONY_CONNECT, 1, 0, 0)
        ptp.call(Ptp.SONY_CONNECT, 2, 0, 0)
        ptp.call(Ptp.SONY_GET_EXT_DEVICE_INFO, EXT_DEVICE_INFO_VERSION)
        ptp.call(Ptp.SONY_CONNECT, 3, 0, 0)
        ptp.call(Ptp.SONY_SET_CONTENTS_TRANSFER_MODE, 2, 0, 0)
        Thread.sleep(TRANSFER_MODE_PAUSE_MS)
        ptp.ok(Ptp.SONY_SET_CONTENTS_TRANSFER_MODE, 2, 1, 0)
    }

    /** The card's storages, once the camera has put them up (it says so with StoreAdded, a few seconds on). */
    fun storages(): IntArray {
        val until = System.currentTimeMillis() + STORAGE_WAIT_MS
        while (true) {
            val answer = ptp.call(Ptp.GET_STORAGE_IDS)
            val ids = if (answer.ok) Ptp.ints(answer.data).filter { it and 0xFFFF != 0 }.toIntArray() else IntArray(0)
            if (ids.isNotEmpty()) return ids
            if (System.currentTimeMillis() > until) throw IOException("the camera put up no card")
            Thread.sleep(if (storeAdded) 200 else 500)
        }
    }

    /** Every object on [storage], folders too. */
    fun handles(storage: Int): IntArray = Ptp.ints(ptp.ok(Ptp.GET_OBJECT_HANDLES, storage, 0, 0).data)

    fun info(handle: Int): Ptp.ObjectInfo = Ptp.objectInfo(handle, ptp.ok(Ptp.GET_OBJECT_INFO, handle).data)

    /** The small JPEG the camera keeps of a photo, without the four bytes of its length the a7C II puts first. */
    fun thumb(handle: Int): ByteArray {
        val data = ptp.ok(Ptp.GET_THUMB, handle).data
        val jpeg = data.size > 6 && data[0] != 0xFF.toByte() && data[4] == 0xFF.toByte() && data[5] == 0xD8.toByte()
        return if (jpeg) data.copyOfRange(4, data.size) else data
    }

    /**
     * The whole of [info]'s file to [out], in chunks so [progress] can say how far it is and
     * [cancelled] stop it between them; with no partial reads, in one piece.
     */
    fun download(info: Ptp.ObjectInfo, out: OutputStream, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val write = { bytes: ByteArray, from: Int, size: Int -> out.write(bytes, from, size) }
        var offset = 0L
        while (offset < info.size) {
            if (cancelled()) throw CancellationException()
            val length = minOf(CHUNK, info.size - offset)
            var got = 0L
            val answer = ptp.call(
                Ptp.SONY_GET_PARTIAL_LARGE_OBJECT, info.handle,
                offset.toInt(), (offset ushr 32).toInt(), length.toInt(), (length ushr 32).toInt(),
                sink = { bytes, from, size -> write(bytes, from, size); got += size },
            )
            if (!answer.ok) {
                if (offset == 0L && got == 0L) return whole(info, write, progress)
                throw PtpException(answer.code, Ptp.SONY_GET_PARTIAL_LARGE_OBJECT)
            }
            if (got == 0L) throw IOException("the camera sent nothing")
            offset += got
            progress(offset)
        }
    }

    private fun whole(info: Ptp.ObjectInfo, write: (ByteArray, Int, Int) -> Unit, progress: (Long) -> Unit) {
        var done = 0L
        ptp.ok(Ptp.GET_OBJECT, info.handle, sink = { bytes, from, size -> write(bytes, from, size); done += size; progress(done) })
    }

    override fun close() {
        runCatching { ptp.call(Ptp.CLOSE_SESSION) }
        ptp.close()
    }

    private companion object {
        const val SESSION = 1
        const val MODE_CONTENTS_TRANSFER = 1
        /** What Sony's apps send GetExtDeviceInfo, the version of its codes they speak. */
        const val EXT_DEVICE_INFO_VERSION = 0xC8
        const val TRANSFER_MODE_PAUSE_MS = 1500L
        const val STORAGE_WAIT_MS = 15_000L
        const val CHUNK = 4L * 1024 * 1024
    }
}
