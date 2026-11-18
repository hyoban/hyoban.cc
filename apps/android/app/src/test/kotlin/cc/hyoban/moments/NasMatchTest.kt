package cc.hyoban.moments

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.NasLink
import cc.hyoban.moments.nas.NasMatch
import cc.hyoban.moments.nas.NasPhoto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class NasMatchTest {
    private val at = LocalDateTime.of(2025, 3, 8, 14, 5, 9)

    private fun photo(id: Long, taken: LocalDateTime? = at, width: Int = 4000, height: Int = 3000, video: Boolean = false) =
        NasPhoto(id, "IMG_$id.HEIC", 1, width, height, video, live = false, duration = null, taken = taken, uploaded = 7)

    private fun media(width: Int = 1600, height: Int = 1200, type: MediaType = MediaType.IMAGE, takenAt: String? = "2025-03-08T14:05:09+08:00", nas: NasLink? = null) =
        MomentMedia(uid = "m", type = type, width = width, height = height, takenAt = takenAt, nas = nas)

    private fun wanted(media: MomentMedia = media(), taken: LocalDateTime? = at) = NasMatch.Wanted("moment", media, taken, at.toLocalDate())

    private fun match(wanted: NasMatch.Wanted, photos: List<NasPhoto>, taken: Set<Long> = emptySet(), own: Long? = null, looks: Map<Long, Long> = emptyMap()) =
        runBlocking { NasMatch.match(wanted, photos, taken, own) { asked -> asked.mapNotNull { p -> looks[p.id]?.let { p.id to it } }.toMap() } }

    /** A made-up picture: soft random blobs, [seed] telling one from another. */
    private fun picture(seed: Int, width: Int, height: Int): IntArray {
        val random = Random(seed)
        val blobs = List(6) { Triple(random.nextDouble(), random.nextDouble(), random.nextInt(256)) }
        return IntArray(width * height) { i ->
            val x = (i % width).toDouble() / width
            val y = (i / width).toDouble() / height
            val v = blobs.sumOf { (bx, by, level) -> level / (1 + 40 * ((x - bx) * (x - bx) + (y - by) * (y - by))) }.toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test
    fun matchesTheClockSecondAndShapeOfASmallerCopy() {
        assertEquals(2L, match(wanted(), listOf(photo(1, at.minusSeconds(1)), photo(2)))?.id)
    }

    @Test
    fun takesAnUprightCopyAsTheSameShape() {
        assertEquals(1L, match(wanted(media(width = 1200, height = 1600)), listOf(photo(1)))?.id)
    }

    @Test
    fun leavesTwoAlikeUnlinkedUnlessOneIsTheSameSizeOrLooksTheSame() {
        val burst = listOf(photo(1), photo(2))
        assertNull(match(wanted(), burst))
        assertEquals(2L, match(wanted(media(width = 4000, height = 3000)), listOf(photo(1, width = 4032, height = 3024), photo(2)))?.id)
        assertEquals(2L, match(wanted(), burst, own = 0L, looks = mapOf(1L to 0xFFFFL, 2L to 0b11L))?.id)
        // Frames of a burst look nearly alike: too close to tell, so left alone.
        assertNull(match(wanted(), burst, own = 0L, looks = mapOf(1L to 0b111L, 2L to 0b11L)))
    }

    @Test
    fun findsOneWithoutATimeByHowItLooks() {
        val day = listOf(photo(1, at.minusHours(2)), photo(2, at.minusHours(1)))
        assertNull(match(wanted(taken = null), day))
        assertEquals(1L, match(wanted(taken = null), day, own = 0L, looks = mapOf(1L to 0b1L, 2L to -1L))?.id)
        assertNull(match(wanted(taken = null), day, own = 0L, looks = mapOf(1L to 0xFFFFFL, 2L to -1L)))
    }

    @Test
    fun turnsDownTheSameSecondWhenItLooksPlainlyElse() {
        assertNull(match(wanted(), listOf(photo(1)), own = 0L, looks = mapOf(1L to -1L)))
        assertEquals(1L, match(wanted(), listOf(photo(1)), own = 0L, looks = emptyMap())?.id)
    }

    @Test
    fun keepsPhotosAndVideosApartAndSkipsWhatIsTaken() {
        assertNull(match(wanted(media(type = MediaType.VIDEO)), listOf(photo(1))))
        assertNull(match(wanted(), listOf(photo(1)), taken = setOf(1L)))
        assertNull(match(wanted(), listOf(photo(1, width = 3000, height = 3000))))
    }

    @Test
    fun hashesASmallerCopyNearAndAnotherPictureFar() {
        val big = picture(1, 400, 300)
        val small = IntArray(160 * 120) { i -> big[(i / 160) * 5 / 2 * 400 + (i % 160) * 5 / 2] }
        val other = picture(2, 400, 300)
        val a = NasMatch.dHash(big, 400, 300)
        assertTrue(NasMatch.distance(a, NasMatch.dHash(small, 160, 120)) <= 4)
        assertTrue(NasMatch.distance(a, NasMatch.dHash(other, 400, 300)) > 10)
    }

    @Test
    fun wantsOnlyUnlinkedMediaAndLooksAroundTheMomentsDayWithoutATime() {
        val record = MomentRecord(
            uid = "moment", text = "", occurredAt = "2025-03-08T20:00:00+08:00", createdAt = "", updatedAt = "",
            media = listOf(media(), media(takenAt = null).copy(uid = "n"), media(nas = NasLink(1, 1, "a.jpg")).copy(uid = "o")),
        )
        val wanted = NasMatch.wanted(listOf(record))
        assertEquals(listOf("m", "n"), wanted.map { it.media.uid })
        assertEquals(at, wanted[0].taken)
        assertEquals(listOf(LocalDate.of(2025, 3, 8)), NasMatch.days(wanted[0]))
        assertEquals((7..9).map { LocalDate.of(2025, 3, it) }, NasMatch.days(wanted[1]))
    }

    @Test
    fun keepsTheLinkThroughTheRecord() {
        val json = Json.encodeToString(MomentMedia.serializer(), media(nas = NasLink(42, 7, "IMG_42.HEIC")))
        assertEquals(NasLink(42, 7, "IMG_42.HEIC"), Json.decodeFromString(MomentMedia.serializer(), json).nas)
    }
}
