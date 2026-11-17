package cc.hyoban.moments.data.local

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * A part of a photo to cover, as fractions of the upright photo's width and height, so it holds
 * whatever size the photo is decoded at: with a mosaic, or with the sticker its [sticker] names
 * (`ui/design/Stickers.kt`).
 */
@Serializable
data class Mask(val left: Float, val top: Float, val right: Float, val bottom: Float, val sticker: String? = null) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    companion object {
        /**
         * A patch around ([x], [y]) on a photo of [shape] (width over height), kept inside the
         * photo: the size of the one of [beside] nearest it, a face as far off as the faces round
         * it, else a square a fifth of the photo's shorter side across.
         */
        fun around(x: Float, y: Float, shape: Float, beside: List<Mask> = emptyList()): Mask {
            val near = beside.minByOrNull { mask ->
                val dx = ((mask.left + mask.right) / 2 - x) * shape
                val dy = (mask.top + mask.bottom) / 2 - y
                dx * dx + dy * dy
            }
            val side = 0.2f
            val width = near?.let { it.right - it.left } ?: if (shape >= 1f) side / shape else side
            val height = near?.let { it.bottom - it.top } ?: if (shape >= 1f) side else side * shape
            val left = (x - width / 2).coerceIn(0f, 1f - width)
            val top = (y - height / 2).coerceIn(0f, 1f - height)
            return Mask(left, top, left + width, top + height)
        }

        /**
         * How wide a sticker is drawn over a patch, against the patch's longer side: its round face,
         * about 16 of its 26 across, then takes in the face with a little of the hair round it, the
         * patch being a face widened a fifth each side and more above already.
         */
        const val STICKER_SCALE = 1.25f
    }
}

/** The SHA-256 of [file]'s bytes, as a photo's local copy keeps it. */
fun sha256Of(file: File): String {
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

/**
 * 人脸打码: the faces found in a photo picked for a Moment, and its copy in the app's own store
 * written again with chosen parts pixelated. The phone finds them with ML Kit through Google
 * Play services; the Mac has neither, so it offers none of it.
 */
expect object Faces {
    /** Whether this device offers 人脸打码 at all. */
    val available: Boolean

    /** The faces in the photo at [ref], each widened to take in the hair and chin; null when they can't be found now. */
    suspend fun find(ref: PhotoRef): List<Mask>?

    /**
     * Each of [masks] with a mosaic on the photo at [ref] as saving will pixelate it, its blocks
     * drawn from a smaller copy of the photo, for the viewer to show before it is saved; null when
     * it can't be read. A sticker's patch the viewer draws itself.
     */
    suspend fun preview(ref: PhotoRef, masks: List<Mask>): Map<Mask, ImageBitmap>?

    /**
     * Writes [source], a photo the app copied in, again into [target] as an upright JPEG with
     * each of [masks] pixelated beyond recognition or covered by its sticker, its EXIF kept but
     * for its orientation and no HDR gain map left to carry the faces' detail; [source] stays as
     * it is, to change the patches again. Returns [target] and its SHA-256.
     */
    suspend fun pixelate(source: File, target: File, masks: List<Mask>): CopiedPhoto
}
