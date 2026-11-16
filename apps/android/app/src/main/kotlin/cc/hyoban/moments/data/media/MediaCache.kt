package cc.hyoban.moments.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.OutputStream

/**
 * What 选照片 keeps on disk of the photos it reads off the camera and the NAS, so coming back to
 * one asks nothing of them again: their thumbnails, and the originals looked at full size. Each
 * is one of Coil's disk caches in the app's cache, the least lately used going first once it is
 * past its size, and the system may empty it too. One of each for the whole app, as two caches
 * on one folder would spoil each other.
 */
class MediaCache private constructor(private val disk: DiskCache) {

    /** What is kept under [key], whole, or null. */
    fun file(key: String): File? = disk.openSnapshot(key)?.use { it.data.toFile() }

    /** The picture kept under [key], or null. */
    fun bitmap(key: String): Bitmap? = file(key)?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Keeps what [write] writes under [key] and gives its file; nothing is kept if [write] throws,
     * or if [key] is being written already, when it is null.
     */
    fun put(key: String, write: (OutputStream) -> Unit): File? {
        val editor = disk.openEditor(key) ?: return null
        try {
            editor.data.toFile().outputStream().buffered(1 shl 20).use(write)
        } catch (e: Throwable) {
            editor.abort()
            throw e
        }
        return editor.commitAndOpenSnapshot()?.use { it.data.toFile() }
    }

    fun put(key: String, bytes: ByteArray) {
        runCatching { put(key) { it.write(bytes) } }
    }

    fun remove(key: String) {
        disk.remove(key)
    }

    companion object {
        private val made = mutableMapOf<String, MediaCache>()

        /** The camera's photos' thumbnails and originals, by the photo's name and size. */
        fun camera(context: Context, originals: Boolean) =
            of(context, if (originals) "camera-originals" else "camera-thumbnails", if (originals) ORIGINALS else THUMBNAILS)

        /** The NAS's photos' thumbnails and originals, by the photo's id and when it was uploaded. */
        fun nas(context: Context, originals: Boolean) =
            of(context, if (originals) "nas-originals" else "nas-thumbnails", if (originals) ORIGINALS else THUMBNAILS)

        private fun of(context: Context, name: String, size: Long): MediaCache = synchronized(made) {
            made.getOrPut(name) {
                val directory = File(context.applicationContext.cacheDir, name)
                MediaCache(DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(size).build())
            }
        }

        private const val ORIGINALS = 1L shl 30
        private const val THUMBNAILS = 100L shl 20
    }
}
