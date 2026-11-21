package cc.hyoban.moments

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.nas.NasKind
import cc.hyoban.moments.nas.NasOption
import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.ui.picker.Facet
import cc.hyoban.moments.ui.picker.Pick
import cc.hyoban.moments.ui.picker.PhotoFilter
import cc.hyoban.moments.ui.picker.PhotoKind
import cc.hyoban.moments.ui.picker.PhotoSource
import cc.hyoban.moments.ui.viewer.PhotoDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhotoFilterTest {
    /** A photo the filter is asked about, with only what it reads. */
    private class Photo(
        override val day: LocalDate?,
        override val kind: PhotoKind? = PhotoKind.PHOTO,
        override val hasPlace: Boolean? = null,
        override val inMoment: Boolean? = null,
    ) : Pick {
        override val key = "photo/$day"
        override val source: PhotoSource get() = error("not asked")
        override suspend fun thumbnail(size: Int): ImageBitmap? = null
        override val type get() = cc.hyoban.moments.data.spec.MediaType.IMAGE
        @Composable override fun badge(): cc.hyoban.moments.ui.components.BadgeKind? = null
        @Composable override fun description() = ""
        @Composable override fun place(): String? = null
        @Composable override fun rememberDetails(): PhotoDetails? = null
        @Composable override fun rememberStill(active: Boolean, deep: Boolean) = cc.hyoban.moments.ui.viewer.ViewedStill(1f)
    }

    private val october = LocalDate.of(2025, 10, 12)

    @Test
    fun nothingChosenLetsEverythingThrough() {
        assertFalse(PhotoFilter.NONE.active)
        assertTrue(PhotoFilter.NONE.matches(Photo(null, kind = null)))
    }

    @Test
    fun kindsAreAnyOfThoseChosen() {
        val filter = PhotoFilter(kinds = setOf(PhotoKind.VIDEO, PhotoKind.LIVE))
        assertTrue(filter.matches(Photo(october, PhotoKind.LIVE)))
        assertFalse(filter.matches(Photo(october, PhotoKind.PHOTO)))
        assertFalse(filter.matches(Photo(october, kind = null)))
    }

    @Test
    fun aYearAMonthOrARangeOfDays() {
        assertEquals(LocalDate.of(2025, 1, 1)..LocalDate.of(2025, 12, 31), PhotoFilter(year = 2025).days)
        assertEquals(LocalDate.of(2024, 2, 1)..LocalDate.of(2024, 2, 29), PhotoFilter(year = 2024, month = 2).days)
        // A range chosen is the time, whatever year was chosen before.
        assertEquals(LocalDate.of(2025, 10, 3)..LocalDate.of(2025, 11, 2), PhotoFilter(year = 2020, from = "2025-10-03", to = "2025-11-02").days)
        assertNull(PhotoFilter().days)
        val range = PhotoFilter(from = "2025-10-03", to = "2025-11-02")
        assertTrue(range.matches(Photo(october)))
        assertTrue(range.matches(Photo(LocalDate.of(2025, 11, 2))))
        assertFalse(range.matches(Photo(LocalDate.of(2025, 11, 3))))
        assertFalse(range.matches(Photo(null)))
    }

    @Test
    fun placeAndMomentNeedThePhotoToSay() {
        assertTrue(PhotoFilter(place = false).matches(Photo(october, hasPlace = false)))
        assertFalse(PhotoFilter(place = true).matches(Photo(october, hasPlace = false)))
        assertFalse(PhotoFilter(moment = true).matches(Photo(october, inMoment = null)))
    }

    @Test
    fun aListingHearsOnlyWhatItCanTell() {
        val filter = PhotoFilter(kinds = setOf(PhotoKind.LIVE), year = 2025, place = true, moment = false, exif = mapOf("camera" to listOf("\"ILCE-7CM2\"")))
        val gallery = filter.within(setOf(Facet.KIND, Facet.TIME))
        assertEquals(PhotoFilter(kinds = setOf(PhotoKind.LIVE), year = 2025), gallery)
        assertEquals(PhotoFilter(year = 2025), filter.within(setOf(Facet.TIME)))
        assertEquals(filter, filter.within(Facet.entries.toSet()))
    }

    @Test
    fun keptAsItWas() {
        val filter = PhotoFilter(kinds = setOf(PhotoKind.VIDEO), from = "2025-10-03", to = "2025-11-02", moment = true,
            exif = mapOf("iso" to listOf("100")), exifNames = mapOf("100" to "ISO 100"))
        assertEquals(filter, PhotoFilter.decode(filter.encode()))
        assertEquals("", PhotoFilter.NONE.encode())
        assertEquals(PhotoFilter.NONE, PhotoFilter.decode(""))
        assertEquals(PhotoFilter.NONE, PhotoFilter.decode("not json"))
    }

    @Test
    fun theNasNarrowsByItsOwnKinds() {
        assertEquals(setOf(NasKind.IMAGE, NasKind.LIVE), PhotoFilter(kinds = setOf(PhotoKind.PHOTO, PhotoKind.LIVE)).nasQuery.kinds)
        val all = NasProtocol.typeOption(emptySet(), raw = true)
        assertEquals(1, all["all"]!!.jsonPrimitive.int)
        assertEquals(0, all["video"]!!.jsonPrimitive.int)
        // A still is any of the NAS's stills: its images, GIFs and RAWs.
        val stills = NasProtocol.typeOption(setOf(NasKind.IMAGE), raw = true)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), listOf("all", "image", "video", "gif", "live", "raw").map { stills[it]!!.jsonPrimitive.int })
        // An album's list takes no RAWs apart.
        assertNull(NasProtocol.typeOption(setOf(NasKind.VIDEO), raw = false)["raw"])
    }

    @Test
    fun theNasHearsEveryExifFieldAndTheValuesAsItGaveThem() {
        val option = NasProtocol.advancedOption(mapOf("camera" to listOf("\"ILCE-7CM2\""), "iso" to listOf("12")))
        assertEquals(NasProtocol.EXIF.toSet(), option.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("ILCE-7CM2"))), option["camera"])
        assertEquals(JsonArray(listOf(JsonPrimitive(12))), option["iso"])
        assertEquals(JsonArray(emptyList()), option["lens"])
    }

    @Test
    fun readsTheNassOptionsPlainOrNamed() {
        val data = Json.parseToJsonElement(
            """{"camera":["ILCE-7CM2","iPhone 15 Pro",""],"lens":[{"id":7,"name":"FE 40mm F2.5 G"}],"focal":null,"iso":[100,200]}""",
        ).jsonObject
        val options = NasProtocol.options(data)
        assertEquals(listOf(NasOption("\"ILCE-7CM2\"", "ILCE-7CM2"), NasOption("\"iPhone 15 Pro\"", "iPhone 15 Pro")), options["camera"])
        assertEquals(listOf(NasOption("7", "FE 40mm F2.5 G")), options["lens"])
        assertEquals(emptyList<NasOption>(), options["focal"])
        assertEquals(listOf("100", "200"), options["iso"]!!.map { it.value })
        // Sent back as they came.
        assertEquals(JsonArray(listOf(JsonPrimitive(7))), NasProtocol.advancedOption(mapOf("lens" to listOf(options["lens"]!!.single().value)))["lens"])
        assertTrue(NasProtocol.EXIF.all { it in options })
        assertEquals(options.keys.size, NasProtocol.EXIF.size)
    }
}
