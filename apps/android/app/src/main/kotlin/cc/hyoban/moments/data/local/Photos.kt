package cc.hyoban.moments.data.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * What a photo says of itself: its size upright, when it was taken and where; and, for one
 * whose EXIF has no time, as a photo saved from WeChat or a chat, when its file was made.
 */
data class PhotoInfo(val width: Int?, val height: Int?, val takenAt: OffsetDateTime?, val fix: Fix?, val savedAt: OffsetDateTime? = null)

/** A photo copied into the app's own store, and the SHA-256 of its bytes. */
data class CopiedPhoto(val file: File, val sha256: String)

/** Photos picked for a Moment written on the phone: read for their time and place, and copied in. */
object Photos {
    /**
     * Reads [uri]'s EXIF. Where a photo was taken is left in only for an app with the media
     * location permission, and only from the documents picker: the photo picker takes it
     * out whatever the app may see, and won't hand over the original.
     */
    suspend fun read(context: Context, uri: Uri): PhotoInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val exif = runCatching { resolver.openInputStream(uri)?.use(::ExifInterface) }.getOrNull()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val width = bounds.outWidth.takeIf { it > 0 } ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)?.takeIf { it > 0 }
        val height = bounds.outHeight.takeIf { it > 0 } ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)?.takeIf { it > 0 }
        val turned = exif?.rotationDegrees == 90 || exif?.rotationDegrees == 270
        PhotoInfo(
            width = if (turned) height else width,
            height = if (turned) width else height,
            takenAt = exif?.let {
                exifTime(
                    it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_DATETIME),
                    it.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_OFFSET_TIME),
                )
            },
            fix = exif?.latLong?.let { (latitude, longitude) -> Fix(latitude, longitude) }?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 },
            savedAt = runCatching { fileTime(context, uri) }.getOrNull(),
        )
    }

    /**
     * When the file at [uri] was made, as the provider has it: the media store's time taken,
     * else the file's last change, which for a photo saved from a chat is when it was saved.
     */
    private fun fileTime(context: Context, uri: Uri): OffsetDateTime? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val millis = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun column(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it > 0 }
            column(MediaStore.MediaColumns.DATE_TAKEN)
                ?: column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                ?: column(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000)
        } ?: return null
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toOffsetDateTime()
    }

    /** Copies [uri] into [directory] as `<name>.<its extension>`, as it is, its EXIF and all. */
    suspend fun copy(context: Context, uri: Uri, directory: File, name: String): CopiedPhoto = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val extension = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        directory.mkdirs()
        val file = File(directory, "$name.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        val input = resolver.openInputStream(uri) ?: error("Can't read $uri")
        input.use { source ->
            file.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                }
            }
        }
        CopiedPhoto(file, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
