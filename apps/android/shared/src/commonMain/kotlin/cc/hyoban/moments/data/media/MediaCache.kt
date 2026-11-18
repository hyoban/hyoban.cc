package cc.hyoban.moments.data.media

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
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
    @OptIn(ExperimentalResourceApi::class)
    fun bitmap(key: String): ImageBitmap? = file(key)?.let { runCatching { it.readBytes().decodeToImageBitmap() }.getOrNull() }

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

    /** The bytes kept under [key], or null. */
    fun bytes(key: String): ByteArray? = runCatching { file(key)?.readBytes() }.getOrNull()

    fun remove(key: String) {
        disk.remove(key)
    }

    companion object {
        private val made = mutableMapOf<String, MediaCache>()

        /** The camera's photos' thumbnails and originals, by the photo's name and size. */
        fun camera(cacheDir: File, originals: Boolean) =
            of(cacheDir, if (originals) "camera-originals" else "camera-thumbnails", if (originals) ORIGINALS else THUMBNAILS)

        /** The NAS's photos' thumbnails and originals, by the photo's id and when it was uploaded. */
        fun nas(cacheDir: File, originals: Boolean) =
            of(cacheDir, if (originals) "nas-originals" else "nas-thumbnails", if (originals) ORIGINALS else THUMBNAILS)

        /**
         * What else is read off the NAS and doesn't change for a photo: the start of its original
         * (its EXIF), how it looks for matching, a day's list as long as the day holds as many, and
         * the map's places, shown while they are read again.
         */
        fun nasData(cacheDir: File) = of(cacheDir, "nas-data", DATA)

        private fun of(cacheDir: File, name: String, size: Long): MediaCache = synchronized(made) {
            made.getOrPut(name) {
                val directory = File(cacheDir, name)
                MediaCache(DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(size).build())
            }
        }

        private const val ORIGINALS = 1L shl 30
        private const val THUMBNAILS = 100L shl 20
        private const val DATA = 64L shl 20
    }
}
