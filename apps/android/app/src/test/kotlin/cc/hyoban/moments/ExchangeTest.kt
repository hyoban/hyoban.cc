package cc.hyoban.moments

import cc.hyoban.moments.data.exchange.BundleWriter
import cc.hyoban.moments.data.exchange.MediaFiles
import cc.hyoban.moments.data.exchange.ReceiptImporter
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.Manifest
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PublishedMedia
import cc.hyoban.moments.data.spec.PublishedMoment
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.Ulid
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeTest {
    private val repository = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/manifest.json").exists() }
    private val fixture = File(repository, "scripts/fixtures/bundle")

    /** The sample bundle, loaded into a fresh store as if the phone had recorded it. */
    private fun storeWithFixture(): MomentsRepository = runBlocking {
        val root = Files.createTempDirectory("moments-test").toFile()
        fixture.copyRecursively(File(root, "data"))
        File(root, "data/README.md").delete()
        MomentsRepository(DataStore(File(root, "data")), "test", Dispatchers.Unconfined).also { it.load() }
    }

    @Test
    fun `a bundle carries the records without local blocks and the unpublished originals`() = runBlocking {
        val repository = storeWithFixture()
        val withPhoto = repository.moments.value.first { it.media.isNotEmpty() && it.places.size == 2 }
        val missingPhoto = repository.moments.value.first { it.media.isNotEmpty() && it.places.size == 1 }
        repository.saveMoment(withPhoto.copy(media = withPhoto.media.map { it.copy(local = LocalMedia("content://photos/1")) }))
        repository.saveMoment(missingPhoto.copy(media = missingPhoto.media.map { it.copy(local = LocalMedia("content://photos/gone")) }))
        val files = object : MediaFiles {
            override fun open(uri: String): InputStream? = if (uri == "content://photos/1") ByteArrayInputStream("jpeg".toByteArray()) else null
            override fun extensionOf(uri: String) = "jpg"
        }
        val output = ByteArrayOutputStream()

        val result = BundleWriter(files, "test").write(output, repository.places.value, repository.trips.value, repository.moments.value)

        assertEquals(3, result.moments)
        assertEquals(1, result.mediaFiles)
        assertEquals(listOf("${missingPhoto.uid}/${missingPhoto.media[0].uid}"), result.missing)
        val entries = readZip(output.toByteArray())
        assertEquals(
            (listOf("manifest.json", "media/${withPhoto.media[0].uid}.jpg", "places.json", "trips/2026-fixture-trip.json") +
                repository.moments.value.map { BundleWriter.fileOf(it) }).sorted(),
            entries.keys.sorted(),
        )
        val written = Spec.json.decodeFromString(MomentRecord.serializer(), entries.getValue(BundleWriter.fileOf(withPhoto)).decodeToString())
        assertNull(written.media[0].local)
        assertEquals(setOf("nanjing-2", "yantai-port"), (Spec.json.parseToJsonElement(entries.getValue("places.json").decodeToString()) as JsonObject).keys)

        val backup = ByteArrayOutputStream()
        BundleWriter(files, "test").write(backup, repository.places.value, repository.trips.value, repository.moments.value, BundleWriter.Options(includeLocal = true, allMedia = true))
        val kept = Spec.json.decodeFromString(MomentRecord.serializer(), readZip(backup.toByteArray()).getValue(BundleWriter.fileOf(withPhoto)).decodeToString())
        assertEquals("content://photos/1", kept.media[0].local?.uri)
    }

    @Test
    fun `a receipt renames places, fills published blocks and adopts empty alt text, once`() = runBlocking {
        val repository = storeWithFixture()
        val withPhoto = repository.moments.value.first { it.media.isNotEmpty() && it.places.size == 2 }
        val media = withPhoto.media[0]
        val publishedMedia = PublishedMedia("moments/2026/10/01-02-nanjing/nanjing.webp", "image/webp", 1, "0".repeat(32), 4032, 3024, variants = emptyList())
        val theirs = withPhoto.copy(
            places = listOf("nanjing", "yantai-port"),
            text = "Edited on the computer, which the phone ignores.",
            media = listOf(media.copy(alt = "The pier.", published = publishedMedia)),
            published = PublishedMoment("2026/10/01-02-nanjing", "nanjing", 2, "2026-10-07T09:12:00+08:00"),
        )
        val receipt = ReceiptImporter.Receipt(
            manifest = Manifest(1, "hyoban.cc data:publish", "2026-10-07T09:12:00+08:00", renamedPlaces = mapOf("nanjing-2" to "nanjing")),
            places = mapOf("nanjing" to Place("南京", 32.0603, 118.7969), "yantai-port" to repository.places.value.getValue("yantai-port")),
            trips = emptyList(),
            moments = listOf(theirs),
        )

        val summary = ReceiptImporter(repository).apply(receipt)

        assertEquals(ReceiptImporter.Summary(renamed = 1, published = 1, added = 0), summary)
        assertEquals(setOf("nanjing", "yantai-port"), repository.places.value.keys)
        val mine = repository.moment(withPhoto.uid)!!
        assertEquals(listOf("nanjing", "yantai-port"), mine.places)
        assertEquals(withPhoto.text, mine.text)
        assertEquals(theirs.published, mine.published)
        assertEquals(publishedMedia, mine.media[0].published)
        assertEquals("The pier.", mine.media[0].alt)
        assertEquals("nanjing", repository.trips.value.single().waypoints.single().place)
        assertEquals("nanjing", repository.trips.value.single().straightLegs.single().from)

        assertEquals(ReceiptImporter.Summary(0, 0, 0), ReceiptImporter(repository).apply(receipt))
    }

    @Test
    fun `a receipt zip is read by entry name`() {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write("""{"schemaVersion":1,"generator":"x","updatedAt":"2026-10-07T09:12:00+08:00","renamedPlaces":{"a":"b"}}""".toByteArray())
            zip.closeEntry()
        }

        val receipt = ReceiptImporter.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(mapOf("a" to "b"), receipt.manifest.renamedPlaces)
        assertEquals(emptyList(), receipt.moments)
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
        buildMap {
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes())
            }
        }
    }

    @Suppress("unused")
    private fun sample() = MomentMedia(Ulid.new(), MediaType.IMAGE)
}
