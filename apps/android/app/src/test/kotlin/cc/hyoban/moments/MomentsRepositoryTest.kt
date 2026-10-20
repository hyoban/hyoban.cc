package cc.hyoban.moments

import cc.hyoban.moments.data.api.MomentChanges
import cc.hyoban.moments.data.api.AppliedChanges
import cc.hyoban.moments.data.api.ChangeSet
import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.api.MomentsWriter
import cc.hyoban.moments.data.api.PlacesResponse
import cc.hyoban.moments.data.api.TripsResponse
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PublishedMedia
import cc.hyoban.moments.data.spec.PublishedMoment
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The copy on the phone follows the API's change feed, page by page, and survives a restart. */
class MomentsRepositoryTest {
    /** The API's feed, as a list of changes each at its own revision. */
    private class FakeApi : MomentsSource {
        val changes = mutableListOf<Pair<Long, Any>>()
        var revision = 0L
        var places = mapOf("nanjing" to Place("南京", 32.06, 118.79))
        var calls = 0

        fun put(moment: MomentRecord) = changes.add(++revision to moment)
        fun delete(uid: String) = changes.add(++revision to uid)

        override suspend fun moments(since: Long, cursor: String?): MomentChanges {
            calls++
            val after = cursor?.toLong() ?: since
            val page = changes.filter { it.first > after }.take(2)
            val more = changes.count { it.first > after } > 2
            return MomentChanges(
                revision = revision,
                moments = page.mapNotNull { it.second as? MomentRecord },
                deleted = page.mapNotNull { it.second as? String },
                next = if (more) page.last().first.toString() else null,
            )
        }

        override suspend fun places() = PlacesResponse(revision, places)

        override suspend fun trips() = TripsResponse(revision, emptyList())
    }

    /** The API's writes, as the Worker answers them: photos kept, places named, drafts published, all of a change set at once. */
    private class FakeWriter : MomentsWriter {
        val calls = mutableListOf<String>()
        val uploads = mutableMapOf<String, String>()

        override suspend fun uploadImage(momentUid: String, mediaUid: String, file: File, contentType: String) {
            calls += "upload $mediaUid $contentType"
            uploads[mediaUid] = file.readText()
        }

        override suspend fun applyChanges(changes: ChangeSet): AppliedChanges {
            calls += "changes places=${changes.places.keys} moments=${changes.moments.map { "${it.uid} ${it.places}" }} deleted=${changes.deleted}"
            val places = changes.places.keys.associateWith { if (it.startsWith("p-")) "hu-bian" else it }
            val moments = changes.moments.map { moment ->
                moment.copy(
                    places = moment.places.map { places[it] ?: it },
                    published = moment.published ?: PublishedMoment("2026/10/02-01-${moment.uid.lowercase()}", moment.uid.lowercase(), 1, "2026-10-02T00:00:00Z"),
                    media = moment.media.map { it.copy(local = null, published = it.published ?: PublishedMedia("moments/x/${it.uid}.jpg", "image/jpeg", 5, "etag")) },
                )
            }
            return AppliedChanges(places, moments, changes.deleted.filter { it != "GONE" })
        }
    }

    private val directory: File = Files.createTempDirectory("moments-cache").toFile()
    private val api = FakeApi()

    private val localDirectory: File = Files.createTempDirectory("moments-local").toFile()

    private fun repository() = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory))

    @Test
    fun `the first refresh reads every page, later ones only what changed`() = runBlocking {
        listOf("A", "B", "C", "D", "E").forEach { api.put(moment(it, "2026-10-0${it[0] - 'A' + 1}")) }
        val repository = repository()

        repository.load()
        assertTrue(repository.refresh().isSuccess)
        assertEquals(listOf("E", "D", "C", "B", "A"), repository.moments.value.map { it.uid })

        api.calls = 0
        api.delete("B")
        api.put(moment("C", "2026-11-20", text = "moved to another month"))
        api.put(moment("F", "2026-10-09", hidden = true))
        api.put(moment("G", "2026-10-10", published = false))
        repository.refresh()

        assertEquals(listOf("C", "E", "D", "A"), repository.moments.value.map { it.uid })
        assertEquals("moved to another month", repository.moment("C")?.text)
        assertEquals(2, api.calls, "four changes come in two pages")
        assertEquals(
            listOf("moments/2026/10/A.json", "moments/2026/10/D.json", "moments/2026/10/E.json", "moments/2026/10/F.json", "moments/2026/10/G.json", "moments/2026/11/C.json"),
            File(directory, "moments").walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.sorted().toList(),
            "a removed Moment's file goes, and a moved one leaves its old month",
        )

        val restarted = repository()
        restarted.load()
        assertEquals(listOf("C", "E", "D", "A"), restarted.moments.value.map { it.uid })
        assertEquals("南京", restarted.places.value["nanjing"]?.name)
    }

    @Test
    fun `a database restored to an earlier revision is read again from the start`() = runBlocking {
        listOf("A", "B", "C").forEach { api.put(moment(it, "2026-10-01")) }
        val repository = repository()
        repository.load()
        repository.refresh()

        api.changes.clear()
        api.revision = 0
        api.put(moment("Z", "2026-10-05"))
        repository.refresh()

        assertEquals(listOf("Z"), repository.moments.value.map { it.uid })
    }

    @Test
    fun `a failed refresh keeps the copy and says why`() = runBlocking {
        api.put(moment("A", "2026-10-01"))
        val repository = repository()
        repository.load()
        repository.refresh()

        val failing = MomentsRepository(DataStore(directory), object : MomentsSource by api {
            override suspend fun moments(since: Long, cursor: String?): MomentChanges = error("offline")
        }, Dispatchers.Unconfined)
        failing.load()

        assertTrue(failing.refresh().isFailure)
        assertEquals("offline", failing.error.value)
        assertEquals(listOf("A"), failing.moments.value.map { it.uid })
    }

    @Test
    fun `the phone's own Moments sit among the API's and outlast a sync, a restart and another API`() = runBlocking {
        api.put(moment("A", "2026-10-01"))
        val repository = repository()
        repository.load()
        repository.refresh()

        val photo = File(localDirectory, "media/P.jpg").apply { parentFile.mkdirs(); writeText("jpeg") }
        val mine = moment("L", "2026-10-02", text = "written here", published = false).copy(
            places = listOf("p-new", "p-kept"),
            media = listOf(MomentMedia("P", MediaType.IMAGE, local = LocalMedia(photo.toURI().toString(), copied = true))),
        )
        repository.addLocal(mine, "p-new" to Place("湖边", 32.0, 118.8, createdAt = mine.createdAt))
        repository.addLocalPlace("p-kept", Place("书店", 32.1, 118.9, createdAt = "2026-10-01T09:00:00+08:00"))

        assertEquals(listOf("L", "A"), repository.moments.value.map { it.uid })
        assertTrue(repository.isLocal("L"))
        assertEquals("湖边", repository.places.value["p-new"]?.name)

        api.put(moment("B", "2026-10-03"))
        repository.refresh()
        assertEquals(listOf("B", "L", "A"), repository.moments.value.map { it.uid })
        assertEquals("湖边", repository.places.value["p-new"]?.name, "a sync keeps the phone's places")

        val restarted = repository()
        restarted.load()
        assertEquals(listOf("B", "L", "A"), restarted.moments.value.map { it.uid })

        restarted.clear()
        assertEquals(listOf("L"), restarted.moments.value.map { it.uid }, "another API drops only its own copy")

        restarted.delete("L")
        assertEquals(emptyList(), restarted.moments.value.map { it.uid })
        assertEquals(null, restarted.places.value["p-new"], "a place no other of the phone's Moments is at goes with it")
        assertEquals("书店", restarted.places.value["p-kept"]?.name, "a place kept on its own stays")
        assertTrue(!photo.exists(), "and so does the photo copied for it")
    }

    @Test
    fun `a Moment of the phone's changed keeps its file, photos and places in step`() = runBlocking {
        val repository = repository()
        repository.load()
        val kept = File(localDirectory, "media/K.jpg").apply { parentFile.mkdirs(); writeText("kept") }
        val dropped = File(localDirectory, "media/D.jpg").apply { writeText("dropped") }
        fun photo(uid: String, file: File) = MomentMedia(uid, MediaType.IMAGE, local = LocalMedia(file.toURI().toString(), copied = true))
        val mine = moment("L", "2026-10-02", text = "before", published = false).copy(
            places = listOf("p-made"),
            media = listOf(photo("K", kept), photo("D", dropped)),
        )
        repository.addLocal(mine, "p-made" to Place("湖边", 32.0, 118.8))
        repository.addLocalPlace("p-saved", Place("书店", 32.1, 118.9))

        repository.updateLocal(mine.copy(occurredAt = "2026-11-03", text = "after", places = listOf("p-saved"), media = listOf(photo("K", kept))))

        assertEquals("after", repository.moment("L")?.text)
        assertEquals(
            listOf("moments/2026/11/L.json"),
            File(localDirectory, "moments").walkTopDown().filter { it.isFile }.map { it.relativeTo(localDirectory).path }.toList(),
            "moved to another month, its file moves with it",
        )
        assertTrue(kept.exists())
        assertTrue(!dropped.exists(), "a photo taken out goes")
        assertEquals(null, repository.places.value["p-made"], "the place it made, now unused, goes")

        repository.updateLocal(repository.moment("L")!!.copy(places = emptyList()))
        assertEquals("书店", repository.places.value["p-saved"]?.name, "a place kept on its own stays when unused")
        val restarted = repository()
        restarted.load()
        assertEquals("after", restarted.moment("L")?.text)
        assertEquals("书店", restarted.places.value["p-saved"]?.name)
    }

    @Test
    fun `nothing reaches the API until the changes are published, all of them at once`() = runBlocking {
        val writer = FakeWriter()
        fun repository() = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        val repository = repository()
        api.put(moment("S", "2026-10-01"))
        api.put(moment("T", "2026-10-04", text = "the site's"))
        api.put(moment("U", "2026-10-05"))
        repository.load()
        repository.refresh()
        val copy = File(localDirectory, "media/P.jpg").apply { parentFile.mkdirs(); writeText("photo") }
        val mine = moment("L", "2026-10-02", text = "湖边", published = false).copy(
            places = listOf("p-made", "nanjing"),
            media = listOf(MomentMedia("P", MediaType.IMAGE, width = 4, height = 3, local = LocalMedia(copy.toURI().toString(), copied = true))),
        )
        val other = moment("M", "2026-10-03", published = false).copy(places = listOf("p-made"))
        repository.addLocal(mine, "p-made" to Place("湖边", 32.0, 118.8))
        repository.addLocal(other)
        repository.updateLocal(repository.moment("T")!!.copy(text = "changed here"))
        repository.updateLocal(repository.moment("U")!!.copy(text = "changed, then taken back"))
        repository.delete("S")

        assertEquals(emptyList(), writer.calls, "saving and deleting stay on the phone")
        assertEquals("changed here", repository.moment("T")?.text)
        assertTrue(repository.isChanged("T") && !repository.isLocal("T"))
        assertEquals(null, repository.moment("S"), "a deleted Moment is gone from the lists at once")
        assertEquals(listOf("L", "M"), repository.pending.value.added.map { it.uid })
        assertEquals(listOf("T", "U"), repository.pending.value.changed.map { it.after.uid })
        assertEquals("the site's", repository.pending.value.changed.first().before?.text, "a change shows what it was")
        assertEquals(listOf("S"), repository.pending.value.removed.map { it.uid })

        repository.revert("U")
        assertEquals("", repository.moment("U")?.text, "a change taken back is the site's again")

        val restarted = repository()
        restarted.load()
        assertEquals(4, restarted.pending.value.size, "the changes outlast a restart")

        val progress = mutableListOf<Pair<Int, Int>>()
        restarted.publishPending { done, total -> progress += done to total }

        assertEquals(
            listOf(
                "upload P image/jpeg",
                "changes places=[p-made] moments=[L [p-made, nanjing], M [p-made], T []] deleted=[S]",
            ),
            writer.calls,
            "the photo first, then everything else in one write, oldest first, the phone's place with it",
        )
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        assertEquals("photo", writer.uploads["P"])
        assertEquals(0, restarted.pending.value.size)
        assertTrue(!restarted.isLocal("L"), "it leaves the phone's")
        assertTrue(!copy.exists(), "its copied photo goes")
        assertEquals("湖边", restarted.places.value["hu-bian"]?.name)
        assertEquals(null, restarted.places.value["p-made"], "the place is the API's now")
        assertEquals(listOf("hu-bian"), restarted.moment("M")?.places)
        assertEquals(listOf("U", "T", "M", "L"), restarted.moments.value.map { it.uid })

        val again = repository()
        again.load()
        assertEquals(listOf("U", "T", "M", "L"), again.moments.value.map { it.uid }, "the copy on the phone has it too")
        assertEquals(0, again.pending.value.size)
    }

    @Test
    fun `a deletion the API no longer has the Moment for is done already`() = runBlocking {
        val writer = FakeWriter()
        val repository = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        api.put(moment("GONE", "2026-10-01"))
        repository.load()
        repository.refresh()
        repository.delete("GONE")

        repository.publishPending()
        repository.publishPending()

        assertEquals(listOf("changes places=[] moments=[] deleted=[GONE]"), writer.calls)
        assertEquals(0, repository.pending.value.size)
        assertEquals(emptyList(), repository.moments.value.map { it.uid })
    }

    private fun moment(uid: String, day: String, text: String = "", hidden: Boolean = false, published: Boolean = true) =
        MomentRecord(
            uid = uid,
            occurredAt = day,
            text = text,
            hidden = hidden,
            published = if (published) PublishedMoment("${day.replace('-', '/')}-01-$uid".lowercase(), uid.lowercase(), 1, "${day}T00:00:00Z") else null,
            createdAt = "${day}T00:00:00Z",
            updatedAt = "${day}T00:00:00Z",
        )
}
