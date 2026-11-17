package cc.hyoban.moments.data.local

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * A part of a photo to cover, as fractions of the upright photo's width and height, so it holds
 * whatever size the photo is decoded at: with a mosaic, or with the sticker its [sticker] names
 * (`ui/design/Stickers.kt`), one of the [plate] stickers over a license plate and else a face's.
 */
@Serializable
data class Mask(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sticker: String? = null,
    val plate: Boolean = false,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    companion object {
        /**
         * A patch around ([x], [y]) on a photo of [shape] (width over height), kept inside the
         * photo: the size and kind of the one of [beside] nearest it, a face as far off as the
         * faces round it or a plate beside a plate, else a face's square a fifth of the photo's
         * shorter side across.
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
            return Mask(left, top, left + width, top + height, plate = near?.plate == true)
        }
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

/** What 打码 found in a photo: its [faces] and its license [plates], each a patch with no style yet. */
class FoundPatches(val faces: List<Mask>, val plates: List<Mask>) {
    val isEmpty: Boolean get() = faces.isEmpty() && plates.isEmpty()

    /**
     * The patches to lay beside those [laid], each face and plate in the style [style] gives for
     * its kind beside those before it.
     */
    fun patches(laid: List<Mask>, style: (beside: List<Mask>, plate: Boolean) -> String?): List<Mask> =
        (faces + plates.map { it.copy(plate = true) })
            .fold(laid) { beside, patch -> beside + patch.copy(sticker = style(beside, patch.plate)) }
            .drop(laid.size)
}

/**
 * [patches] with those that mostly lie over one kept before them left out: a plate read both in
 * the whole photo and in a tile, or in two tiles where they overlap, is covered once.
 */
fun distinctPatches(patches: List<Mask>): List<Mask> = patches.fold(emptyList()) { kept, patch ->
    val area = (patch.right - patch.left) * (patch.bottom - patch.top)
    val over = kept.any { other ->
        val w = minOf(patch.right, other.right) - maxOf(patch.left, other.left)
        val h = minOf(patch.bottom, other.bottom) - maxOf(patch.top, other.top)
        w > 0 && h > 0 && w * h > area / 2
    }
    if (over) kept else kept + patch
}

/**
 * A Chinese license plate in [text], as text recognition reads one off a photo (粤B·12345, 粤BD12345,
 * the dot read or not, spaced or not), as the range of [text] it takes; null when there is none. A
 * plate with its province read is taken whole. Without it, often read as a letter or not at all on a
 * plate far off, one is taken only when the dot after the city's letter was read, or when the rest
 * is a new-energy plate's (D or F and five more, or five digits and D or F), so a sign's code or a
 * price isn't taken for one.
 */
fun plateIn(text: String): IntRange? =
    PLATE.find(text)?.range ?: CITY_PLATE.find(text)?.range ?: NEW_ENERGY_PLATE.find(text)?.range

private const val PLATE_TAIL = "[A-Z0-9]{4,5}[A-Z0-9挂学警港澳]"
private val PLATE = Regex("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][·•・.\\s-]?\\s?$PLATE_TAIL(?![A-Z0-9])")
private val CITY_PLATE = Regex("(?<![A-Z0-9])[A-Z][·•・]\\s?$PLATE_TAIL(?![A-Z0-9])")
private val NEW_ENERGY_PLATE =
    Regex("(?<![A-Z0-9])[A-Z]?[A-Z][·•・.\\s-]?\\s?(?:[DF][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[DF])(?![A-Z0-9])")

/**
 * 打码: the faces and license plates found in a photo picked for a Moment, and its copy in the
 * app's own store written again with chosen parts pixelated. The phone finds them with ML Kit
 * through Google Play services, plates by reading the photo's text; the Mac has neither, so it
 * offers none of it.
 */
expect object Masking {
    /** Whether this device offers 打码 at all. */
    val available: Boolean

    /**
     * The faces in the photo at [ref], each widened to take in the hair and chin, and its plates,
     * each widened to its frame; null when neither can be looked for now.
     */
    suspend fun find(ref: PhotoRef): FoundPatches?

    /**
     * Each of [masks] with a mosaic on the photo at [ref] as saving will pixelate it, its blocks
     * drawn from a smaller copy of the photo, for the viewer to show before it is saved; null when
     * it can't be read. A sticker's patch the viewer draws itself.
     */
    suspend fun preview(ref: PhotoRef, masks: List<Mask>): Map<Mask, ImageBitmap>?

    /**
     * Writes [source], a photo the app copied in, again into [target] as an upright JPEG with
     * each of [masks] pixelated beyond recognition or covered by its sticker, its EXIF kept but
     * for its orientation and no HDR gain map left to carry their detail; [source] stays as
     * it is, to change the patches again. Returns [target] and its SHA-256.
     */
    suspend fun pixelate(source: File, target: File, masks: List<Mask>): CopiedPhoto
}
