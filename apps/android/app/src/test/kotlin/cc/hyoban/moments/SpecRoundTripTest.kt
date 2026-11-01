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
 * in: the spec is shared, and nothing may be lost on the way. Over the made-up data/ the
 * root's tests read too, or the real one at HYOBAN_DATA_ROOT, as `pnpm data:export` writes it.
 */
class SpecRoundTripTest {
    private val data = System.getenv("HYOBAN_DATA_ROOT")?.let(::File)
        ?: generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "pnpm-workspace.yaml").exists() }
            .resolve("packages/data-spec/test/fixtures/data")

    @Test
    fun `every Moment in the first layout round-trips`() {
        val files = roundTripAll(File(data, "moments"), MomentRecord.serializer())
        assertTrue(files > 0 || File(data, "entries").isDirectory, "expected Moments under $data")
    }

    /** Once data/ is in the second shape: every entry with words is a Moment and the same entry again. */
    @Test
    fun `every entry round-trips through a Moment`() {
        val files = File(data, "entries").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        var moments = 0
        for (file in files) {
            val entry = Spec.json.parseToJsonElement(file.readText()) as JsonObject
            val moment = Entries.toMoment(entry) ?: continue
            assertEquivalent(entry, Entries.fromMoment(moment), file.path)
            moments++
        }
        assertTrue(moments > 0, "expected Moments under $data, saw none")
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
    fun `a collection and its marks make the trip the spec's collectionToTrip makes`() {
        // The collection and entries, and what packages/data-spec's collectionToTrip makes of them.
        val collection = Spec.json.parseToJsonElement("""{"uid": "01M62F9N00NPJ5VGYWT8JNZWX0", "slug": "test-trip", "kind": "trip", "title": "测试", "span": {"start": "2026-10-01", "end": "2026-10-03"}, "include": [], "exclude": ["01M62F9N00NPJ5VGYWT8JNZWX3"], "covers": [{"entry": "01M62F9N00NPJ5VGYWT8JNZWX1", "media": "01M62F9N00NPJ5VGYWT8JNZWX2"}], "route": {"straightLegs": [{"from": "a", "to": "b", "by": "train"}], "sameWayBack": []}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-04T00:00:00Z", "mood": "x"}""") as JsonObject
        val entries = (Spec.json.parseToJsonElement("""[{"uid": "01M62F9N00NPJ5VGYWT8JNZWX4", "at": "2026-10-02", "place": "c", "parts": {}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}, {"uid": "01M62F9N00NPJ5VGYWT8JNZWX5", "at": "2026-10-02T09:00:00+08:00", "place": "b", "parts": {}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}, {"uid": "01M62F9N00NPJ5VGYWT8JNZWX6", "at": "2026-10-01T08:00:00+08:00", "place": "a", "parts": {}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}, {"uid": "01M62F9N00NPJ5VGYWT8JNZWX3", "at": "2026-10-02T10:00:00+08:00", "place": "x", "parts": {}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}, {"uid": "01M62F9N00NPJ5VGYWT8JNZWX7", "at": "2026-10-05T10:00:00+08:00", "place": "y", "parts": {}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}, {"uid": "01M62F9N00NPJ5VGYWT8JNZWX8", "at": "2026-10-02T11:00:00+08:00", "place": "z", "parts": {"text": {"v": 1, "text": "hi"}}, "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z"}]""") as JsonArray).map { it as JsonObject }
        val expected = Spec.json.parseToJsonElement("""{"mood":"x","id":"test-trip","title":"测试","start":"2026-10-01","end":"2026-10-03","waypoints":[{"at":"2026-10-01T08:00:00+08:00","place":"a"},{"at":"2026-10-02","place":"c"},{"at":"2026-10-02T09:00:00+08:00","place":"b"}],"straightLegs":[{"from":"a","to":"b","by":"train"}],"sameWayBack":[],"covers":[{"moment":"01M62F9N00NPJ5VGYWT8JNZWX1","media":"01M62F9N00NPJ5VGYWT8JNZWX2"}],"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-04T00:00:00Z"}""") as JsonObject
        val trip = Spec.json.encodeToJsonElement(TripRecord.serializer(), Entries.toTrip(collection, entries)) as JsonObject
        assertEquivalent(expected, trip, "toTrip")
    }

    @Test
    fun `stable ULIDs are the spec's`() {
        // The same as packages/data-spec's getStableUlid gives.
        assertEquals("01M5N5TZ2GDW69JGJRAH18Q5JS", Entries.stableUlid("2026-10-24T04:04:34+08:00", "trip:2025-first-road-trip"))
        assertEquals("01M62F9N00NPJ5VGYWT8JNZWX0", Entries.stableUlid("2026-10-29", "x"))
    }

    @Test
    fun `trips, places and the manifest round-trip`() {
        roundTripAll(File(data, "trips"), TripRecord.serializer())
        roundTrip(File(data, "places.json"), MapSerializer(String.serializer(), Place.serializer()))
        roundTrip(File(data, "manifest.json"), Manifest.serializer())
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
