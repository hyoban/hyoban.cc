package cc.hyoban.moments.data.local

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.imageio.ImageIO

/** A file on the computer, by its `file:` address, as the records keep a local copy's. */
actual abstract class PhotoRef {
    actual abstract override fun toString(): String
}

/** The one kind of [PhotoRef] a computer has: a file, equal to another of the same address. */
private data class FileRef(val uri: String) : PhotoRef() {
    override fun toString() = uri
}

/** The file a [PhotoRef] stands for. */
val PhotoRef.file: File get() = File(URI(toString()))

actual fun String.toPhotoRef(): PhotoRef = FileRef(this)

actual fun File.toPhotoRef(): PhotoRef = FileRef(toURI().toString())

/**
 * Photos picked from the computer's files. A photo's size, time and place are read from the file
 * itself; a video is told apart but not yet taken, as nothing here draws the frame it needs for its still.
 */
actual object Photos {
    actual suspend fun read(ref: PhotoRef): PhotoInfo = withContext(Dispatchers.IO) {
        val file = ref.file
        val type = typeOf(file)
        val saved = Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault()).toOffsetDateTime()
        if (type?.startsWith("video/") == true) {
            return@withContext PhotoInfo(null, null, null, null, savedAt = saved, video = true, type = type, bytes = file.length())
        }
        val metadata = runCatching { ImageMetadataReader.readMetadata(file) }.getOrNull()
        val size = sizeOf(file, metadata)
        val orientation = metadata?.getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInteger(ExifDirectoryBase.TAG_ORIENTATION)
        val turned = orientation != null && orientation in 5..8
        val exif = metadata?.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val base = metadata?.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        PhotoInfo(
            width = if (turned) size?.second else size?.first,
            height = if (turned) size?.first else size?.second,
            takenAt = exifTime(
                exif?.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL) ?: base?.getString(ExifDirectoryBase.TAG_DATETIME),
                exif?.getString(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL) ?: exif?.getString(ExifDirectoryBase.TAG_TIME_ZONE),
            ),
            fix = metadata?.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation
                ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { Fix(it.latitude, it.longitude) },
            savedAt = saved,
            motion = runCatching { MotionPhotos.videoStart(file) != null }.getOrDefault(false),
            type = type,
        )
    }

    actual suspend fun copy(ref: PhotoRef, directory: File, name: String, splitMotion: Boolean, poster: Boolean): CopiedPhoto = withContext(Dispatchers.IO) {
        val source = ref.file
        val type = typeOf(source)
        check(type?.startsWith("video/") != true) { "Videos can't be added on this computer yet" }
        directory.mkdirs()
        val file = File(directory, "$name.${source.extension.lowercase().ifEmpty { "jpg" }}")
        source.copyTo(file, overwrite = true)
        val video = File(directory, "$name.mp4").takeIf { splitMotion && MotionPhotos.split(file, it) }
        CopiedPhoto(file, sha256(file), video)
    }

    /** The file's type, as the system tells it from its name, else as its extension says. */
    private fun typeOf(file: File): String? = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> null
    }

    /** The picture's width and height as stored: as an image reader sees them, else as its metadata says. */
    private fun sizeOf(file: File, metadata: Metadata?): Pair<Int, Int>? {
        runCatching {
            ImageIO.createImageInputStream(file)?.use { input ->
                ImageIO.getImageReaders(input).asSequence().firstOrNull()?.let { reader ->
                    try {
                        reader.input = input
                        return reader.getWidth(0) to reader.getHeight(0)
                    } finally {
                        reader.dispose()
                    }
                }
            }
        }
        // What ImageIO can't read (HEIC): any directory that names a width and a height.
        metadata?.directories?.forEach { directory ->
            val width = directory.tags.firstOrNull { it.tagName.equals("Image Width", ignoreCase = true) }?.let { directory.getInteger(it.tagType) }
            val height = directory.tags.firstOrNull { it.tagName.equals("Image Height", ignoreCase = true) }?.let { directory.getInteger(it.tagType) }
            if (width != null && height != null && width > 0 && height > 0) return width to height
        }
        return null
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

actual fun PhotoRef.asImageModel(): Any = toString()
