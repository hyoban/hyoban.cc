package cc.hyoban.moments.data.local

import java.io.File
import java.io.RandomAccessFile

/**
 * Motion photos, as Pixel, OPPO, Xiaomi and others take them: a JPEG with its clip, an MP4,
 * appended after it, and the clip's length in the photo's XMP, in Google's Motion Photo
 * container (`Item:Semantic="MotionPhoto"` with its `Item:Length`) or the older
 * `GCamera:MicroVideoOffset`. Split in two they make a live photo's still and video.
 */
object MotionPhotos {
    /** Where the clip starts in [file], or null when it is a plain photo. */
    fun videoStart(file: File): Long? {
        val size = file.length()
        val head = ByteArray(minOf(size, XMP_READ).toInt())
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val length = videoLength(String(head, Charsets.ISO_8859_1)) ?: return null
        val start = size - length
        if (start <= 0) return null
        // The clip is an MP4 only if its first box is `ftyp`; anything else, the XMP is wrong.
        val box = ByteArray(4)
        RandomAccessFile(file, "r").use { it.seek(start + 4); it.readFully(box) }
        return start.takeIf { String(box, Charsets.ISO_8859_1) == "ftyp" }
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
