package cc.hyoban.moments.data.local

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
 * What a photo or a video says of itself: its size upright, when it was taken and where;
 * and, for one that doesn't say when, as a photo saved from WeChat or a chat, when its file
 * was made; whether it is a motion photo, with a clip after its still; and for a video, its
 * type and how big its file is, which the API has limits for.
 */
data class PhotoInfo(
    val width: Int?,
    val height: Int?,
    val takenAt: OffsetDateTime?,
    val fix: Fix?,
    val savedAt: OffsetDateTime? = null,
    val motion: Boolean = false,
    val video: Boolean = false,
    val type: String? = null,
    val bytes: Long? = null,
)

/**
 * A photo copied into the app's own store, the SHA-256 of its bytes, and a motion photo's
 * clip; or a video, in [video], with a frame of it as its still in [file].
 */
data class CopiedPhoto(val file: File, val sha256: String, val video: File? = null)

/** Why a picked file can't go in a Moment, or null when it can. */
enum class Refusal { NOT_MEDIA, VIDEO_TYPE, VIDEO_TOO_BIG }

/** Photos and videos picked for a Moment written on the phone: read for their time and place, and copied in. */
object Photos {
    /** The video types the API keeps (`packages/data-spec`'s `getExtension`). */
    val VIDEO_TYPES = setOf("video/mp4", "video/quicktime", "video/webm")

    /**
     * The largest video taken, in bytes: a Worker takes no request bigger than 100 MB, and
     * Media Transformations, which make the site's poster from it, no video bigger either.
     */
    const val MAX_VIDEO_BYTES = 100_000_000L

    /** Whether a file read as [info] can go in a Moment. */
    fun refusal(info: PhotoInfo): Refusal? = when {
        !info.video -> if (info.type == null || info.type.startsWith("image/")) null else Refusal.NOT_MEDIA
        info.type !in VIDEO_TYPES -> Refusal.VIDEO_TYPE
        (info.bytes ?: 0) > MAX_VIDEO_BYTES -> Refusal.VIDEO_TOO_BIG
        else -> null
    }

    /** The type of what [uri] holds, as its provider says, else as its name's extension does. */
    fun typeOf(context: Context, uri: Uri): String? =
        context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase())

    /**
     * Reads [uri]'s EXIF. Where a photo was taken is left in only for an app with the media
     * location permission, and only from the documents picker: the photo picker takes it
     * out whatever the app may see, and won't hand over the original.
     */
    suspend fun read(context: Context, uri: Uri): PhotoInfo = withContext(Dispatchers.IO) {
        val type = typeOf(context, uri)
        if (type?.startsWith("video/") == true) return@withContext readVideo(context, uri, type)
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
            motion = MotionPhotos.isMotion(context, uri),
            type = type,
        )
    }

    /**
     * A video's size upright, when and where it was recorded as its container says, which,
     * like a photo's place, is left in only with the media location permission.
     */
    private fun readVideo(context: Context, uri: Uri, type: String): PhotoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            runCatching { retriever.setDataSource(context, uri) }
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()?.takeIf { it > 0 }
            val width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val turned = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).let { it == 90 || it == 270 }
            return PhotoInfo(
                width = if (turned) height else width,
                height = if (turned) width else height,
                takenAt = videoTime(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)),
                fix = videoFix(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)),
                savedAt = runCatching { fileTime(context, uri) }.getOrNull(),
                video = true,
                type = type,
                bytes = runCatching { fileSize(context, uri) }.getOrNull(),
            )
        } finally {
            retriever.release()
        }
    }

    private fun fileSize(context: Context, uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let(::File)?.length()
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
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

    /**
     * Copies [uri] into [directory] as `<name>.<its extension>`, as it is, its EXIF and all;
     * with [splitMotion] a motion photo is split, its still there and its clip beside it as
     * `<name>.mp4`. With [poster], a video gets its first frame beside it, as
     * `<name>-poster.jpg`, its still while it is on the phone.
     */
    suspend fun copy(context: Context, uri: Uri, directory: File, name: String, splitMotion: Boolean = false, poster: Boolean = false): CopiedPhoto = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val type = typeOf(context, uri)
        val extension = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        directory.mkdirs()
        val file = File(directory, "$name.$extension")
        val input = resolver.openInputStream(uri) ?: error("Can't read $uri")
        input.use { source -> file.outputStream().use { source.copyTo(it, 64 * 1024) } }
        if (poster && type?.startsWith("video/") == true) {
            val still = File(directory, "$name-poster.jpg")
            writePoster(file, still)
            return@withContext CopiedPhoto(still, sha256(file), file)
        }
        val video = File(directory, "$name.mp4").takeIf { splitMotion && MotionPhotos.split(file, it) }
        CopiedPhoto(file, sha256(file), video)
    }

    /** A video's first frame, upright and at most [POSTER_SIZE] on its longer side, as a JPEG. */
    private fun writePoster(video: File, poster: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_SIZE, POSTER_SIZE)
                ?: error("No frame in ${video.name}")
            poster.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** How big a video's poster is on its longer side: the largest the app shows a still. */
private const val POSTER_SIZE = 1920
