package cc.hyoban.moments.data.media

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.PublishedMedia
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A photo or video as it leaves the device, whichever way it goes (the phone's share sheet, a
 * folder on the Mac): the device's own copy while it is there, else the published original,
 * downloaded once per original, as objects never change; and never where it was taken, which
 * is blanked on everything written ([Location.stripped]), as publishing blanks it.
 */
object Outgoing {
    /** One file of an item: where the device keeps it, if it does, and what was published of it. */
    data class Part(val local: String?, val published: PublishedMedia?, val video: Boolean)

    /** How the device reads its own files: opens one by its address, and names its type and an extension for a type. */
    interface Files {
        fun open(address: String): InputStream?
        fun type(address: String): String?
        fun extension(type: String): String
    }

    /**
     * The files [media] goes out as: a photo, a video whole, a live photo's still, and with
     * [withClip] its clip after it.
     */
    fun parts(media: MomentMedia, withClip: Boolean): List<Part> {
        val local = media.local
        val published = media.published
        return when (media.type) {
            MediaType.IMAGE -> listOf(Part(local?.uri, published, video = false))
            MediaType.VIDEO -> listOf(Part(local?.let { it.videoUri ?: it.uri }, published, video = true))
            MediaType.LIVE_PHOTO -> listOfNotNull(
                Part(local?.uri, published, video = false),
                Part(local?.videoUri, published?.video, video = true)
                    .takeIf { withClip && (it.local != null || it.published != null) },
            )
        }
    }

    /**
     * [part] written to [directory] as `<name>.<its extension>`, and its type. The device's copy
     * is read when it is still there; a published one is downloaded into [originals] only when
     * the device's copy is gone or isn't the one published, and kept there for the next time.
     */
    fun fetch(part: Part, urls: MediaUrls, files: Files, directory: File, originals: File, name: String): Pair<File, String> {
        val published = part.published
        part.local?.let { local ->
            val own = if (published == null) {
                own(local, part.video, files, directory, name)
            } else {
                runCatching { own(local, part.video, files, directory, name) }.getOrNull()
            }
            // Publishing blanks a location in place, so the published original keeps the size of the device's copy without it.
            if (own != null && (published == null || own.first.length() == published.bytes)) return own
            own?.first?.delete()
        }
        if (published == null) throw IOException("$name is neither published nor on the device")
        val type = published.contentType
        val cached = File(originals, "${published.key}-${published.etag}".replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (!cached.isFile || cached.length() != published.bytes) {
            originals.mkdirs()
            val partial = File.createTempFile("download-", null, originals)
            try {
                Http.client.newCall(Request.Builder().url(urls.asset(published.key)).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GET ${published.key} answered ${response.code}")
                    partial.outputStream().use { response.body!!.byteStream().copyTo(it) }
                }
                if (!partial.renameTo(cached)) throw IOException("Can't keep ${published.key}")
            } finally {
                partial.delete()
            }
        } else {
            cached.setLastModified(System.currentTimeMillis())
        }
        val file = File(directory, "$name.${files.extension(type)}")
        cached.copyTo(file, overwrite = true)
        blank(file, part.video || type.startsWith("video/"), directory)
        return file to type
    }

    /** The device's file at [address], copied to [directory] as `<name>.<its extension>` without where it was taken. */
    private fun own(address: String, video: Boolean, files: Files, directory: File, name: String): Pair<File, String> {
        val type = files.type(address) ?: if (video) "video/mp4" else "image/jpeg"
        val file = File(directory, "$name.${files.extension(type)}")
        val input = files.open(address) ?: throw IOException("Can't read $address")
        try {
            input.use { from -> file.outputStream().use { from.copyTo(it) } }
            blank(file, video || type.startsWith("video/"), directory)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file to type
    }

    /** Blanks where [file] was taken, keeping its name. */
    private fun blank(file: File, video: Boolean, directory: File) {
        val stripped = Location.stripped(file, video, directory)
        if (stripped != file && !stripped.renameTo(file)) {
            stripped.delete()
            throw IOException("Can't blank the location of ${file.name}")
        }
    }

    /** The pictures a computer's files are, by their names' endings: what its file dialog offers and a drop takes. */
    val PICTURES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
}
