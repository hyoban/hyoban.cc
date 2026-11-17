package cc.hyoban.moments.data.local

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.PathParser
import androidx.exifinterface.media.ExifInterface
import cc.hyoban.moments.ui.design.Sticker
import cc.hyoban.moments.AndroidApp
import cc.hyoban.moments.logWarning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

actual object Masking {
    actual val available: Boolean = true

    actual suspend fun find(ref: PhotoRef): FoundPatches? = withContext(Dispatchers.IO) {
        try {
            val bitmap = small(ref)
            val image = InputImage.fromBitmap(bitmap, 0)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(MIN_FACE)
                    .build(),
            )
            val faces = try {
                detector.process(image).result("Faces not found in $ref")?.map { widened(it.boundingBox, width, height) }
            } finally {
                detector.close()
            }
            val reader = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val plates = try {
                // A plate near takes in more than a tile, one far off too few pixels for its
                // characters to be read in the small photo: it is read whole, then in tiles of
                // the photo at its full size.
                val whole = platesIn(reader, image, ref)?.map { plate(it, width, height) }
                val tiled = whole?.let { platesInTiles(reader, ref) }
                whole?.let { distinctPatches(it + tiled.orEmpty()) }
            } finally {
                reader.close()
            }
            if (faces == null && plates == null) null else FoundPatches(faces.orEmpty(), plates.orEmpty())
        } catch (e: Exception) {
            logWarning(TAG, "Couldn't read $ref for faces and plates", e)
            null
        }
    }

    /** The boxes, in [image]'s pixels, of the plates [reader] reads in it; null when it can't read it. */
    private suspend fun platesIn(reader: TextRecognizer, image: InputImage, ref: PhotoRef): List<Rect>? =
        reader.process(image).result("Text not read in $ref")?.textBlocks?.flatMap { it.lines }?.mapNotNull { line ->
            plateBox(line.elements.map { it.text to it.boundingBox })
        }

    /**
     * The plates in the photo at [ref] read at up to [TILED_SIZE] on its longer side, in tiles
     * [TILE] across overlapping by [TILE_OVERLAP], so that a plate is whole in one of them; none
     * when the photo is no larger than the small one already read.
     */
    private suspend fun platesInTiles(reader: TextRecognizer, ref: PhotoRef): List<Mask> {
        val large = decode(ImageDecoder.createSource(AndroidApp.context.contentResolver, ref), TILED_SIZE, mutable = false)
        try {
            if (max(large.width, large.height) <= FIND_SIZE) return emptyList()
            val found = mutableListOf<Mask>()
            for (top in tileStarts(large.height)) for (left in tileStarts(large.width)) {
                val tile = Bitmap.createBitmap(large, left, top, minOf(TILE, large.width - left), minOf(TILE, large.height - top))
                try {
                    platesIn(reader, InputImage.fromBitmap(tile, 0), ref)?.forEach { box ->
                        box.offset(left, top)
                        found += plate(box, large.width.toFloat(), large.height.toFloat())
                    }
                } finally {
                    if (tile !== large) tile.recycle()
                }
            }
            return found
        } finally {
            large.recycle()
        }
    }

    /** Where the tiles along a side [length] long start, the last ending at its end. */
    private fun tileStarts(length: Int): List<Int> {
        if (length <= TILE) return listOf(0)
        val step = TILE - TILE_OVERLAP
        return (0 until length - TILE step step) + (length - TILE)
    }

    /** What this task gives, or null, logging [failed] with why, when it fails. */
    private suspend fun <T> Task<T>.result(failed: String): T? = suspendCancellableCoroutine { done ->
        addOnSuccessListener { done.resume(it) }
        addOnFailureListener { error ->
            logWarning(TAG, failed, error)
            done.resume(null)
        }
    }

    /**
     * The box round the words of a line, [words] each with its box, that read as a plate: those
     * the plate's characters fall in, or null when the line has none or a word of it has no box.
     */
    private fun plateBox(words: List<Pair<String, Rect?>>): Rect? {
        val range = plateIn(words.joinToString("") { it.first }) ?: return null
        var start = 0
        val box = Rect()
        for ((word, rect) in words) {
            val end = start + word.length
            if (end > range.first && start <= range.last) box.union(rect ?: return null)
            start = end
        }
        return box.takeUnless { it.isEmpty }
    }

    actual suspend fun preview(ref: PhotoRef, masks: List<Mask>): Map<Mask, ImageBitmap>? = withContext(Dispatchers.IO) {
        try {
            val bitmap = small(ref)
            masks.filter { Sticker.of(it.sticker) == null }.associateWith { mask -> mosaic(bitmap, rectOf(mask, bitmap)).asImageBitmap() }
        } catch (e: Exception) {
            logWarning(TAG, "Couldn't read $ref for its patches", e)
            null
        }
    }

    /**
     * The photo at [ref] upright and at most [FIND_SIZE] on its longer side, for finding faces and
     * plates and drawing the patches' preview; the last one is kept, as the viewer asks for the same
     * photo each time a patch is laid or taken away.
     */
    private fun small(ref: PhotoRef): Bitmap = synchronized(this) {
        smallest?.takeIf { it.first == ref }?.second
            ?: decode(ImageDecoder.createSource(AndroidApp.context.contentResolver, ref), FIND_SIZE, mutable = false)
                .also { smallest = ref to it }
    }

    private var smallest: Pair<PhotoRef, Bitmap>? = null

    /** [mask] in [bitmap]'s pixels, at least one each way. */
    private fun rectOf(mask: Mask, bitmap: Bitmap): Rect {
        val left = (mask.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (mask.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        return Rect(
            left,
            top,
            (mask.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width),
            (mask.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height),
        )
    }

    actual suspend fun pixelate(source: File, target: File, masks: List<Mask>): CopiedPhoto = withContext(Dispatchers.IO) {
        val bitmap = decode(ImageDecoder.createSource(source), WRITE_SIZE, mutable = true)
        // An Ultra HDR photo's gain map is a second picture of the same faces: it doesn't go along.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) bitmap.gainmap = null
        val canvas = Canvas(bitmap)
        for (mask in masks) {
            val rect = rectOf(mask, bitmap)
            if (rect.width() < 2 || rect.height() < 2) continue
            val sticker = Sticker.of(mask.sticker)
            if (sticker != null) {
                drawSticker(canvas, sticker, rect)
                continue
            }
            val blocks = mosaic(bitmap, rect)
            canvas.drawBitmap(blocks, null, rect, null)
            if (blocks !== bitmap) blocks.recycle()
        }
        val written = File(target.parentFile, "${target.name}.pixelated")
        written.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        keepExif(source, written, bitmap.width, bitmap.height)
        bitmap.recycle()
        target.delete()
        check(written.renameTo(target)) { "Couldn't keep ${target.name}" }
        CopiedPhoto(target, sha256Of(target))
    }

    /**
     * The photo upright, as [ImageDecoder] turns it by its EXIF, at most [longest] on its longer
     * side, in memory the CPU can read.
     */
    private fun decode(source: ImageDecoder.Source, longest: Int, mutable: Boolean): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = mutable
            val side = max(info.size.width, info.size.height)
            if (side > longest) {
                val scale = longest.toFloat() / side
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }

    /**
     * [rect] of [bitmap] in blocks of a tenth of its longer side, each one colour, so that
     * no feature survives; shrunk in two steps, so each block averages more of what it covers
     * than one filtered scale would sample.
     */
    private fun mosaic(bitmap: Bitmap, rect: Rect): Bitmap {
        // Counted against the patch, not in pixels, so the preview's blocks are the ones saved.
        val block = max(rect.width(), rect.height()) / BLOCKS.toFloat()
        val across = ceil(rect.width() / block).toInt().coerceAtLeast(1)
        val down = ceil(rect.height() / block).toInt().coerceAtLeast(1)
        val part = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val half = Bitmap.createScaledBitmap(part, (across * 4).coerceAtMost(part.width), (down * 4).coerceAtMost(part.height), true)
        val small = Bitmap.createScaledBitmap(half, across, down, true)
        val blocks = Bitmap.createScaledBitmap(small, rect.width(), rect.height(), false)
        listOf(part, half, small).forEach { if (it !== blocks && it !== bitmap) it.recycle() }
        return blocks
    }

    /** [sticker] over [rect], its cover box taking it in, as the viewer draws it. */
    private fun drawSticker(canvas: Canvas, sticker: Sticker, rect: Rect) {
        val scale = sticker.scaleOver(rect.width().toFloat(), rect.height().toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.save()
        canvas.translate(rect.exactCenterX(), rect.exactCenterY())
        canvas.scale(scale, scale)
        canvas.translate(-(sticker.coverLeft + sticker.coverWidth / 2), -(sticker.coverTop + sticker.coverHeight / 2))
        for (layer in sticker.layers) {
            val path = PathParser.createPathFromPathData(layer.path)
            layer.fill?.let {
                paint.style = Paint.Style.FILL
                paint.color = it.toInt()
                canvas.drawPath(path, paint)
            }
            layer.stroke?.let {
                paint.style = Paint.Style.STROKE
                paint.color = it.toInt()
                paint.strokeWidth = layer.width
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }

    /** What [from] says of when, where and with what it was taken, written into [to], upright at its new size. */
    private fun keepExif(from: File, to: File, width: Int, height: Int) {
        val old = runCatching { ExifInterface(from) }.getOrNull() ?: return
        val new = ExifInterface(to)
        for (tag in KEPT_TAGS) old.getAttribute(tag)?.let { new.setAttribute(tag, it) }
        new.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        new.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        new.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        new.saveAttributes()
    }

    /**
     * ML Kit's box runs from the brows to the chin: widened a fifth each side and more above,
     * for the hair and forehead, as fractions of the photo.
     */
    private fun widened(box: Rect, width: Float, height: Float): Mask {
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        return Mask(
            ((box.left - w * 0.2f) / width).coerceIn(0f, 1f),
            ((box.top - h * 0.35f) / height).coerceIn(0f, 1f),
            ((box.right + w * 0.2f) / width).coerceIn(0f, 1f),
            ((box.bottom + h * 0.15f) / height).coerceIn(0f, 1f),
        )
    }

    /**
     * The words of a plate run from one edge of its characters to the other: widened a sixth each
     * side and a third above and below, for the plate's frame, as fractions of the photo.
     */
    private fun plate(box: Rect, width: Float, height: Float): Mask {
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        return Mask(
            ((box.left - w / 6) / width).coerceIn(0f, 1f),
            ((box.top - h / 3) / height).coerceIn(0f, 1f),
            ((box.right + w / 6) / width).coerceIn(0f, 1f),
            ((box.bottom + h / 3) / height).coerceIn(0f, 1f),
            plate = true,
        )
    }

    private const val TAG = "Moments"

    /** The size faces and plates are looked for at: enough for a face a fiftieth of the photo across. */
    private const val FIND_SIZE = 1600

    /**
     * The size plates are read at in tiles: a plate a car's length off in a 12 MP photo has
     * characters some 20 pixels high, enough to read, where the small photo leaves it a third of that.
     */
    private const val TILED_SIZE = 4096

    /** A tile's side, near what the text recognizer reads best, and how much tiles overlap: wider than a far plate. */
    private const val TILE = 1536
    private const val TILE_OVERLAP = 256

    /** The smallest face looked for, against the photo's width. */
    private const val MIN_FACE = 0.02f

    /**
     * The largest a pixelated photo is written, on its longer side: a 50 MP photo decoded whole
     * takes 200 MB, more than the app may hold.
     */
    private const val WRITE_SIZE = 6000

    private const val QUALITY = 92

    /** How many blocks run across a patch's longer side. */
    private const val BLOCKS = 10

    private val KEPT_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
    )
}
