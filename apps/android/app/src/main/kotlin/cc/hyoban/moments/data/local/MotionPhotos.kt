package cc.hyoban.moments.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Motion photos, as Pixel, OPPO, Xiaomi and others take them: a JPEG with its clip, an MP4,
 * appended after it, and the clip's length in the photo's XMP, in Google's Motion Photo
 * container (`Item:Semantic="MotionPhoto"` with its `Item:Length`) or the older
 * `GCamera:MicroVideoOffset`. Split in two they make a live photo's still and video.
 */
object MotionPhotos {
    /** Where the clip starts in [file], or null when it is a plain photo. */
    fun videoStart(file: File): Long? = RandomAccessFile(file, "r").use { videoStart(it.channel) }

    /** Whether the photo at [uri] is a motion photo; one that can't be read through is taken for a plain one. */
    fun isMotion(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { videoStart(it) != null }
        } ?: false
    }.getOrDefault(false)

    private fun videoStart(channel: FileChannel): Long? {
        val size = channel.size()
        val head = read(channel, 0, minOf(size, XMP_READ).toInt())
        val length = videoLength(String(head, Charsets.ISO_8859_1)) ?: return null
        val start = size - length
        if (start <= 0) return null
        // The clip is an MP4 only if its first box is `ftyp`; anything else, the XMP is wrong.
        return start.takeIf { String(read(channel, start + 4, 4), Charsets.ISO_8859_1) == "ftyp" }
    }

    private fun read(channel: FileChannel, at: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining() && channel.read(buffer, at + buffer.position()) >= 0) Unit
        return buffer.array()
    }

    /** The clip's length in bytes as the XMP in [head] gives it. */
    internal fun videoLength(head: String): Long? {
        val xmp = head.substringAfter("<x:xmpmeta", "").substringBefore("</x:xmpmeta>").ifEmpty { return null }
        ITEM.findAll(xmp).map { it.value }.firstOrNull { "Semantic=\"MotionPhoto\"" in it }?.let { item ->
            return LENGTH.find(item)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
        }
        return MICRO_VIDEO_OFFSET.find(xmp)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * Splits the motion photo [file] in place: its clip goes to [video], and [file] is left
     * the still alone. Whether it was one.
     */
    fun split(file: File, video: File): Boolean {
        val start = videoStart(file) ?: return false
        RandomAccessFile(file, "rw").use { photo ->
            video.outputStream().use { out ->
                var at = start
                while (at < photo.length()) at += photo.channel.transferTo(at, photo.length() - at, out.channel)
            }
            photo.setLength(start)
        }
        return true
    }

    /** How much of a photo is read for its XMP: it sits in the first segments, an Ultra HDR one's too. */
    private const val XMP_READ = 256L * 1024
    private val ITEM = Regex("""<Container:Item\b[^>]*>""")
    private val LENGTH = Regex("""Item:Length="(\d+)"""")
    private val MICRO_VIDEO_OFFSET = Regex("""GCamera:MicroVideoOffset="(\d+)"""")
}
