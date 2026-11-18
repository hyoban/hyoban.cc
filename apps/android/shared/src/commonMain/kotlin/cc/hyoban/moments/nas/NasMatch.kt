package cc.hyoban.moments.nas

import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Links the Moments' photos and videos written before they were linked as they were picked
 * ([cc.hyoban.moments.data.spec.NasLink]) to the same ones on the NAS, a photo for a photo and a
 * video for a video, among those of the day it was taken (or, saying no time, of its Moment's day
 * and those either side):
 *
 * - one taken at the same second on the clock, of the same shape (the published copy may be smaller,
 *   or turned upright), unless it looks plainly else;
 * - else, or where two are alike at that second, the one that looks the same ([dHash] of the two
 *   thumbnails), when it is close and clearly closer than the next.
 *
 * What can't be told apart is left unlinked rather than guessed.
 */
object NasMatch {
    /** What linking is at: the NAS's days read, the photos compared, the Moments changed kept. */
    enum class Stage { DAYS, PHOTOS, SAVING }

    /** A Moment's photo to link: when it was taken on the clock then, if it says, and the day it is looked for on. */
    data class Wanted(val moment: String, val media: MomentMedia, val taken: LocalDateTime?, val day: LocalDate)

    /** The photos and videos of [moments] not linked yet. */
    fun wanted(moments: List<MomentRecord>): List<Wanted> = moments.flatMap { record ->
        val day = runCatching { OffsetDateTime.parse(record.occurredAt).toLocalDate() }.getOrNull()
        record.media.mapNotNull { media ->
            if (media.nas != null) return@mapNotNull null
            val taken = media.takenAt?.let { runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrNull() }
            Wanted(record.uid, media, taken, taken?.toLocalDate() ?: day ?: return@mapNotNull null)
        }
    }

    /** The days [wanted] is looked for on: the day it was taken, or without a time its Moment's and those either side. */
    fun days(wanted: Wanted): List<LocalDate> =
        if (wanted.taken != null) listOf(wanted.day) else listOf(wanted.day.minusDays(1), wanted.day, wanted.day.plusDays(1))

    /**
     * The one of [photos] that is [wanted]'s, leaving out those [taken] already; null if none is,
     * or more than one may be. [own] is its thumbnail's [dHash] and [looks] some NAS photos', by
     * their ids, those that couldn't be had left out.
     */
    suspend fun match(
        wanted: Wanted,
        photos: List<NasPhoto>,
        taken: Set<Long> = emptySet(),
        own: Long? = null,
        looks: suspend (List<NasPhoto>) -> Map<Long, Long> = { emptyMap() },
    ): NasPhoto? {
        val video = wanted.media.type == MediaType.VIDEO
        val kind = photos.filter { it.id !in taken && it.video == video && sameShape(wanted.media, it) }
        val at = wanted.taken?.withNano(0)
        val timed = if (at == null) emptyList() else kind.filter { it.taken == at }
        val byTime = timed.singleOrNull() ?: timed.filter { sameSize(wanted.media, it) }.singleOrNull()
        if (byTime != null) {
            // The same second, unless it looks plainly else.
            val apart = own?.let { mine -> looks(listOf(byTime))[byTime.id]?.let { distance(mine, it) } }
            if (apart == null || apart <= UNLIKE) return byTime
        }
        if (own == null) return null
        // By how it looks: among those at that second where two were, else the whole day's.
        val among = if (timed.size > 1) timed else kind
        val seen = looks(among)
        val near = among.mapNotNull { photo -> seen[photo.id]?.let { photo to distance(own, it) } }.sortedBy { it.second }
        val (best, apart) = near.firstOrNull() ?: return null
        val next = near.getOrNull(1)?.second
        return best.takeIf { apart <= CLOSE && (next == null || next - apart >= MARGIN) }
    }

    private fun sameShape(media: MomentMedia, photo: NasPhoto): Boolean {
        val (w, h) = (media.width ?: return true) to (media.height ?: return true)
        val (pw, ph) = (photo.width ?: return true) to (photo.height ?: return true)
        if (h <= 0 || pw <= 0 || ph <= 0) return true
        val shape = w.toDouble() / h
        return listOf(pw.toDouble() / ph, ph.toDouble() / pw).any { abs(it - shape) / shape < SHAPE }
    }

    private fun sameSize(media: MomentMedia, photo: NasPhoto) =
        setOf(media.width, media.height) == setOf(photo.width, photo.height)

    /**
     * How a picture looks, in 64 bits: shrunk to 9 by 8 in grey, each bit whether a spot is
     * brighter than the one to its right. A smaller, recompressed or lightly 打码'd copy keeps
     * nearly all of them; [pixels] are ARGB, [width] by [height].
     */
    fun dHash(pixels: IntArray, width: Int, height: Int): Long {
        val grey = DoubleArray(9 * 8)
        for (gy in 0 until 8) for (gx in 0 until 9) {
            val x0 = gx * width / 9
            val x1 = maxOf(x0 + 1, minOf(width, (gx + 1) * width / 9))
            val y0 = gy * height / 8
            val y1 = maxOf(y0 + 1, minOf(height, (gy + 1) * height / 8))
            var sum = 0.0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * width + x]
                sum += 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
            }
            grey[gy * 9 + gx] = sum / ((x1 - x0) * (y1 - y0))
        }
        var bits = 0L
        for (gy in 0 until 8) for (gx in 0 until 8) {
            if (grey[gy * 9 + gx] > grey[gy * 9 + gx + 1]) bits = bits or (1L shl (gy * 8 + gx))
        }
        return bits
    }

    /** [dHash] of a decoded picture. */
    fun dHash(image: ImageBitmap): Long {
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return dHash(pixels, image.width, image.height)
    }

    /** How many of two [dHash]es' bits differ. */
    fun distance(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** How far a shape may differ, what resizing rounds away. */
    private const val SHAPE = 0.02

    /** Bits apart within which two pictures look the same. */
    private const val CLOSE = 10

    /** How much closer the closest must be than the next, so a burst's frames aren't told apart by chance. */
    private const val MARGIN = 4

    /** Bits apart past which two at the same second are plainly not the same picture. */
    private const val UNLIKE = 24
}

/**
 * Each of [moments]' photos and videos found on the NAS, by the Moment's uid and then the
 * photo's: the NAS's timeline read for the days they are looked for on alone. [still] is a
 * Moment's photo's small thumbnail, to compare how it looks; [progress] says how far it is: the
 * days read, then the photos compared, each of how many. Thumbnails come [AT_ONCE] at a time.
 */
suspend fun Nas.matchMoments(
    moments: List<MomentRecord>,
    still: suspend (MomentMedia) -> ImageBitmap?,
    progress: (NasMatch.Stage, Int, Int) -> Unit = { _, _, _ -> },
): Map<String, Map<String, NasPhoto>> = coroutineScope {
    val wanted = NasMatch.wanted(moments)
    if (wanted.isEmpty()) return@coroutineScope emptyMap()
    // The timeline's days, newest first: where each starts in it, so only those days are read.
    progress(NasMatch.Stage.DAYS, 0, 0)
    var offset = 0
    val starts = timelineDays().associate { day -> day.day to (offset to day).also { offset += day.count } }
    val read = wanted.flatMapTo(mutableSetOf()) { NasMatch.days(it) }.filter { it in starts }.sortedDescending()
    val photosOf = mutableMapOf<LocalDate, List<NasPhoto>>()
    read.forEachIndexed { index, day ->
        progress(NasMatch.Stage.DAYS, index, read.size)
        val (start, listed) = starts.getValue(day)
        photosOf[day] = dayPhotos(listed, start)
    }

    val gate = Semaphore(AT_ONCE)
    // How each NAS photo looks, read once; null where its thumbnail couldn't be had.
    val looked = mutableMapOf<Long, Long?>()
    val looks: suspend (List<NasPhoto>) -> Map<Long, Long> = { photos ->
        val missing = photos.filter { it.id !in looked }.distinctBy { it.id }
        missing.map { photo -> async { photo.id to gate.withPermit { look(photo) } } }
            .awaitAll().forEach { (id, hash) -> looked[id] = hash }
        photos.mapNotNull { photo -> looked[photo.id]?.let { photo.id to it } }.toMap()
    }
    val looking = wanted.filter { want -> NasMatch.days(want).any { photosOf[it].orEmpty().isNotEmpty() } }
        // Those that say when they were taken first, so a time settles a photo before a look is guessed at.
        .sortedBy { it.taken == null }
    val found = mutableMapOf<String, MutableMap<String, NasPhoto>>()
    val taken = moments.flatMapTo(mutableSetOf()) { record -> record.media.mapNotNull { it.nas?.id } }
    var done = 0
    progress(NasMatch.Stage.PHOTOS, 0, looking.size)
    // A batch's own stills read together, then each decided in turn, as what one takes the next can't.
    looking.chunked(AT_ONCE * 2).forEach { batch ->
        val own = batch.map { want ->
            async {
                gate.withPermit {
                    // A published still doesn't change: how it looks is kept by its object's ETag.
                    val etag = want.media.published?.etag
                    if (etag == null) still(want.media)?.let(NasMatch::dHash)
                    else remembered("own:$etag") { still(want.media)?.let(NasMatch::dHash) }
                }
            }
        }.awaitAll()
        batch.zip(own).forEach { (want, mine) ->
            val photos = NasMatch.days(want).flatMap { photosOf[it].orEmpty() }
            NasMatch.match(want, photos, taken, mine, looks)?.let { photo ->
                taken += photo.id
                found.getOrPut(want.moment) { mutableMapOf() }[want.media.uid] = photo
            }
            progress(NasMatch.Stage.PHOTOS, ++done, looking.size)
        }
    }
    found
}

/** How many thumbnails are read at once while matching. */
private const val AT_ONCE = 6
