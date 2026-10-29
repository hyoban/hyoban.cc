package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every record the site has must come back out of the app's models exactly as it went
 * in: the spec is shared, and nothing may be lost on the way.
 */
class SpecRoundTripTest {
    private val repository = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/manifest.json").exists() }

    @Test
    fun `every Moment in the repository round-trips`() {
        val files = roundTripAll(File(repository, "data/moments"), MomentRecord.serializer())
        assertTrue(files > 400 || File(repository, "data/entries").isDirectory, "expected the site's Moments, saw $files")
    }

    /** Once data/ is in the second shape: every entry with words is a Moment and the same entry again. */
    @Test
    fun `every entry in the repository round-trips through a Moment`() {
        val data = System.getenv("HYOBAN_DATA_ROOT")?.let(::File) ?: File(repository, "data")
        val files = File(data, "entries").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        var moments = 0
        for (file in files) {
            val entry = Spec.json.parseToJsonElement(file.readText()) as JsonObject
            val moment = Entries.toMoment(entry) ?: continue
            assertEquivalent(entry, Entries.fromMoment(moment), file.path)
            moments++
        }
        assertTrue(files.isEmpty() || moments > 400, "expected the site's Moments, saw $moments")
    }

    @Test
    fun `a Moment becomes the entry the spec makes of it`() {
        // As packages/data-spec's momentToEntry: words, photos and meal as parts, hidden as private.
        val moment = Spec.json.decodeFromString(
            MomentRecord.serializer(),
            """{"uid":"01M3VDBHC042D43KWZWE67YP1T","occurredAt":"2026-10-01T12:00:00+08:00","places":["nanjing"],"text":"面","hidden":true,"food":{"dishes":["面"],"verdict":"good","bill":{"amount":32,"people":2}},"createdAt":"2026-10-01T17:41:00+08:00","updatedAt":"2026-10-01T17:41:00+08:00"}""",
        )
        val entry = Entries.fromMoment(moment, mapOf("bill" to Entries.billPart("expense", 32.0, "CNY", "餐饮", null)))
        val expected = Spec.json.parseToJsonElement(
            """{"uid":"01M3VDBHC042D43KWZWE67YP1T","at":"2026-10-01T12:00:00+08:00","place":"nanjing","parts":{"text":{"v":1,"text":"面"},"food":{"v":1,"dishes":["面"],"verdict":"good","cost":32,"people":2},"bill":{"v":1,"visibility":"public","kind":"expense","amount":32.0,"currency":"CNY","category":"餐饮"}},"visibility":"private","createdAt":"2026-10-01T17:41:00+08:00","updatedAt":"2026-10-01T17:41:00+08:00"}""",
        )
        assertEquivalent(expected, entry, "entry")
        assertEquals(moment, Entries.toMoment(entry))
    }

    @Test
    fun `a part private by its own say goes onto the entry's part and comes back`() {
        val moment = Spec.json.decodeFromString(
            MomentRecord.serializer(),
            """{"uid":"01M3VDBHC042D43KWZWE67YP1T","occurredAt":"2026-10-01","text":"面","food":{"dishes":["面"]},"partVisibility":{"food":"private"},"createdAt":"2026-10-01T17:41:00+08:00","updatedAt":"2026-10-01T17:41:00+08:00"}""",
        )
        val entry = Entries.fromMoment(moment)
        val food = (entry["parts"] as JsonObject)["food"] as JsonObject
        assertEquals(JsonPrimitive("private"), food["visibility"])
        assertTrue(Entries.PART_VISIBILITY !in entry)
        assertEquals(moment, Entries.toMoment(entry))
    }

    @Test
    fun `stable ULIDs are the spec's`() {
        // The same as packages/data-spec's getStableUlid gives.
        assertEquals("01M5N5TZ2GDW69JGJRAH18Q5JS", Entries.stableUlid("2026-10-24T04:04:34+08:00", "trip:2025-first-road-trip"))
        assertEquals("01M62F9N00NPJ5VGYWT8JNZWX0", Entries.stableUlid("2026-10-29", "x"))
    }

    @Test
    fun `trips, places and the manifest round-trip`() {
        roundTripAll(File(repository, "data/trips"), TripRecord.serializer())
        roundTrip(File(repository, "data/places.json"), MapSerializer(String.serializer(), Place.serializer()))
        roundTrip(File(repository, "data/manifest.json"), Manifest.serializer())
    }

    @Test
    fun `fields the app doesn't know survive a copy`() {
        val text = """{"uid":"01M3VDBHC042D43KWZWE67YP1T","occurredAt":"2026-10-01","text":"","mood":"sunny","createdAt":"2026-10-01T17:41:00+08:00","updatedAt":"2026-10-01T17:41:00+08:00"}"""
        val record = Spec.json.decodeFromString(MomentRecord.serializer(), text)
        val edited = record.copy(text = "Edited.")
        val written = Spec.json.encodeToString(MomentRecord.serializer(), edited)

        assertTrue("\"mood\": \"sunny\"" in written, written)
        assertTrue("\"places\": []" in written, written)
        assertEquals(listOf("uid", "occurredAt", "places", "text", "hidden", "media", "createdAt", "updatedAt", "mood"), Spec.json.parseToJsonElement(written).let { (it as JsonObject).keys.toList() })
    }

    private fun <T> roundTripAll(directory: File, serializer: KSerializer<T>): Int {
        val files = directory.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        files.forEach { roundTrip(it, serializer) }
        return files.size
    }

    private fun <T> roundTrip(file: File, serializer: KSerializer<T>) {
        val original = Spec.json.parseToJsonElement(file.readText())
        val value = Spec.json.decodeFromString(serializer, file.readText())
        val written = Spec.json.parseToJsonElement(Spec.json.encodeToString(serializer, value))
        assertEquivalent(original, written, file.path)
    }

    /** JSON equality that reads numbers as numbers, so `32` and `32.0` agree, and ignores key order. */
    private fun assertEquivalent(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals(expected.keys, actual.keys, "$path: keys")
                expected.keys.forEach { assertEquivalent(expected.getValue(it), actual.getValue(it), "$path.$it") }
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals(expected.size, actual.size, "$path: length")
                expected.indices.forEach { assertEquivalent(expected[it], actual[it], "$path[$it]") }
            }
            expected is JsonPrimitive && actual is JsonPrimitive -> {
                if (expected.isString || actual.isString || expected.doubleOrNull == null) {
                    assertEquals(expected, actual, path)
                } else {
                    assertEquals(expected.doubleOrNull, actual.doubleOrNull, path)
                }
            }
            else -> fail("$path: $expected vs $actual")
        }
    }
}
