package cc.hyoban.moments

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
 * Every record the site has, and the sample bundle, must come back out of the app's
 * models exactly as it went in: the spec is shared, and nothing may be lost on the way.
 */
class SpecRoundTripTest {
    private val repository = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/manifest.json").exists() }

    @Test
    fun `every Moment in the repository round-trips`() {
        val files = roundTripAll(File(repository, "data/moments"), MomentRecord.serializer())
        assertTrue(files > 400, "expected the site's Moments, saw $files")
    }

    @Test
    fun `trips, places and the manifest round-trip`() {
        roundTripAll(File(repository, "data/trips"), TripRecord.serializer())
        roundTrip(File(repository, "data/places.json"), MapSerializer(String.serializer(), Place.serializer()))
        roundTrip(File(repository, "data/manifest.json"), Manifest.serializer())
    }

    @Test
    fun `the sample bundle round-trips, including the slug it proposes`() {
        val bundle = File(repository, "scripts/fixtures/bundle")
        roundTripAll(File(bundle, "moments"), MomentRecord.serializer())
        roundTripAll(File(bundle, "trips"), TripRecord.serializer())
        roundTrip(File(bundle, "places.json"), MapSerializer(String.serializer(), Place.serializer()))
        roundTrip(File(bundle, "manifest.json"), Manifest.serializer())

        val proposing = File(bundle, "moments").walkTopDown()
            .filter { it.extension == "json" }
            .map { Spec.json.decodeFromString(MomentRecord.serializer(), it.readText()) }
            .first { "slug" in it.extra }
        assertEquals(JsonPrimitive("ferry-morning"), proposing.extra["slug"])
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
