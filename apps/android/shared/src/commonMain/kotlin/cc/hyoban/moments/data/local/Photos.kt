package cc.hyoban.moments.data.local

import java.io.File
import java.time.OffsetDateTime

/** What draws [ref]: the site's photos by their address, the device's by however it keeps them. */
fun imageModel(ref: PhotoRef): Any = ref.toString().let { address ->
    if (address.startsWith("https:") || address.startsWith("http:")) address else ref.asImageModel()
}

/** What the image loader takes for a photo on the device. */
expect fun PhotoRef.asImageModel(): Any

/** A photo or a video picked for a Moment, by where the device has it: the phone's content address, a file on a computer. */
expect abstract class PhotoRef {
    /** Its address written out, as a record keeps it. */
    abstract override fun toString(): String
}

/** The photo an address written out stands for, as a record keeps its local copy's. */
expect fun String.toPhotoRef(): PhotoRef

expect fun File.toPhotoRef(): PhotoRef

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

/** What may go in a Moment, whichever device it is written on. */
object PhotoRules {
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
}

/** Photos and videos picked for a Moment written on the device: read for their time and place, and copied in. */
expect object Photos {
    /** What the photo or video at [ref] says of itself. */
    suspend fun read(ref: PhotoRef): PhotoInfo

    /**
     * Copies [ref] into [directory] as `<name>.<its extension>`, as it is, its EXIF and all;
     * with [splitMotion] a motion photo is split, its still there and its clip beside it as
     * `<name>.mp4`. With [poster], a video gets its first frame beside it, as
     * `<name>-poster.jpg`, its still while it is on the device.
     */
    suspend fun copy(ref: PhotoRef, directory: File, name: String, splitMotion: Boolean = false, poster: Boolean = false): CopiedPhoto
}
