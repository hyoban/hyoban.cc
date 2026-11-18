package cc.hyoban.moments.nas

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * Links the Moments' photos and videos written before they were linked as they were picked
 * ([cc.hyoban.moments.data.spec.NasLink]) to the same ones on the NAS: one taken at the same
 * second on the clock, a photo for a photo and a video for a video, of the same shape (the
 * published copy may be smaller, or turned upright). Two alike at that second leave it unlinked
 * rather than guess.
 */
object NasMatch {
    /** A Moment's photo to link, and when it was taken on the clock then. */
    data class Wanted(val moment: String, val media: MomentMedia, val taken: LocalDateTime)

    /** The photos and videos of [moments] not linked yet that say when they were taken. */
    fun wanted(moments: List<MomentRecord>): List<Wanted> = moments.flatMap { record ->
        record.media.mapNotNull { media ->
            if (media.nas != null) return@mapNotNull null
            val taken = media.takenAt?.let { runCatching { OffsetDateTime.parse(it).toLocalDateTime() }.getOrNull() } ?: return@mapNotNull null
            Wanted(record.uid, media, taken)
        }
    }

    /** The one of [photos] that is [wanted]'s, leaving out those [taken] already; null if none is, or more than one. */
    fun match(wanted: Wanted, photos: List<NasPhoto>, taken: Set<Long> = emptySet()): NasPhoto? {
        val at = wanted.taken.withNano(0)
        val video = wanted.media.type == MediaType.VIDEO
        val alike = photos.filter { it.id !in taken && it.taken == at && it.video == video && sameShape(wanted.media, it) }
        if (alike.size <= 1) return alike.firstOrNull()
        // Shots in a burst: the one the same size, if only one is.
        return alike.filter { sameSize(wanted.media, it) }.singleOrNull()
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

    /** The days [wanted] were taken on, which the NAS's timeline is read by. */
    fun days(wanted: List<Wanted>): Set<LocalDate> = wanted.mapTo(mutableSetOf()) { it.taken.toLocalDate() }

    /** How far a shape may differ, what resizing rounds away. */
    private const val SHAPE = 0.02
}

/**
 * Each of [moments]' photos and videos found on the NAS, by the Moment's uid and then the
 * photo's: the NAS's timeline read for the days they were taken on alone. [progress] is how many
 * of those days are read, of how many.
 */
suspend fun Nas.matchMoments(moments: List<MomentRecord>, progress: (Int, Int) -> Unit = { _, _ -> }): Map<String, Map<String, NasPhoto>> {
    val wanted = NasMatch.wanted(moments)
    if (wanted.isEmpty()) return emptyMap()
    val days = NasMatch.days(wanted)
    // The timeline's days, newest first: where each starts in it, so only those days are read.
    var offset = 0
    val starts = timelineDays().associate { day -> day.day to (offset to day.count).also { offset += day.count } }
    val byDay = wanted.groupBy { it.taken.toLocalDate() }
    val found = mutableMapOf<String, MutableMap<String, NasPhoto>>()
    val taken = moments.flatMapTo(mutableSetOf()) { record -> record.media.mapNotNull { it.nas?.id } }
    val read = days.filter { it in starts }.sortedDescending()
    read.forEachIndexed { index, day ->
        progress(index, read.size)
        val (start, count) = starts.getValue(day)
        val photos = buildList {
            while (size < count) {
                val (page, _) = timeline(start + size, minOf(Nas.PAGE, count - size))
                if (page.isEmpty()) break
                addAll(page)
            }
        }
        byDay[day].orEmpty().forEach { want ->
            NasMatch.match(want, photos, taken)?.let { photo ->
                taken += photo.id
                found.getOrPut(want.moment) { mutableMapOf() }[want.media.uid] = photo
            }
        }
    }
    progress(read.size, read.size)
    return found
}
