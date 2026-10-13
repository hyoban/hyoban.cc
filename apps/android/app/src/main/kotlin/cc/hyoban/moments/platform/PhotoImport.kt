package cc.hyoban.moments.platform

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.Timestamps
import cc.hyoban.moments.data.spec.Ulid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Turns a picked photo into a media item: its size and time from the file, kept by reference or copied. */
class PhotoImport(private val context: Context) {
    private val copies = File(context.filesDir, "media")

    suspend fun import(uri: Uri, time: Long, copy: Boolean): MomentMedia = withContext(Dispatchers.IO) {
        val uid = Ulid.new(time)
        val resolver = context.contentResolver
        val extension = resolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        val source = if (copy) {
            copies.mkdirs()
            val file = File(copies, "$uid.$extension")
            resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(file)
        } else {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uri
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var width = bounds.outWidth
        var height = bounds.outHeight
        var takenAt: String? = null
        resolver.openInputStream(source)?.use { stream ->
            val exif = ExifInterface(stream)
            if (exif.rotationDegrees % 180 != 0) width = height.also { height = width }
            takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::parseExifTime)
        }
        MomentMedia(
            uid = uid,
            type = MediaType.IMAGE,
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            takenAt = takenAt,
            local = LocalMedia(uri = source.toString(), copied = copy),
        )
    }

    private fun parseExifTime(value: String): String? = runCatching {
        val local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        Timestamps.format(local.atZone(ZoneId.systemDefault()).toOffsetDateTime())
    }.getOrNull()
}
