package cc.hyoban.moments

import cc.hyoban.moments.data.api.AppliedChanges
import cc.hyoban.moments.data.api.ChangeSet
import cc.hyoban.moments.data.api.Changes
import cc.hyoban.moments.data.api.MomentsSource
import cc.hyoban.moments.data.api.MomentsWriter
import cc.hyoban.moments.data.api.UploadPart
import cc.hyoban.moments.data.spec.LocalMedia
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.PublishedMedia
import cc.hyoban.moments.data.spec.PublishedMoment
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.store.PublishProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The copy on the phone follows the API's change feed, page by page, and survives a restart. */
class MomentsRepositoryTest {
    /** The API's feed, as a list of changes each at its own revision, the places' among them. */
    private class FakeApi : MomentsSource {
        val changes = mutableListOf<Pair<Long, Any>>()
        var revision = 0L
        /** Each place with the revision it last changed at. */
        val places = mutableMapOf("nanjing" to (0L to Place("南京", 32.06, 118.79)))
        var calls = 0
        /** How many places the feed handed out, all told. */
        var placesSent = 0

        fun put(moment: MomentRecord) = changes.add(++revision to moment)
        fun delete(uid: String) = changes.add(++revision to uid)
        fun putPlace(id: String, place: Place) {
            places[id] = ++revision to place
        }

        override suspend fun changes(since: Long, cursor: String?): Changes {
            calls++
            val after = cursor?.toLong() ?: since
            val page = changes.filter { it.first > after }.take(2)
            val more = changes.count { it.first > after } > 2
            val changedPlaces = if (cursor == null) places.filterValues { (rev) -> rev > since || since == 0L }.mapValues { it.value.second } else emptyMap()
            placesSent += changedPlaces.size
            return Changes(
                revision = revision,
                moments = page.mapNotNull { it.second as? MomentRecord },
                deleted = page.mapNotNull { it.second as? String },
                places = changedPlaces,
                next = if (more) page.last().first.toString() else null,
            )
        }
    }

    /** The API's writes, as the Worker answers them: photos kept, places named, drafts published, all of a change set at once. */
    private class FakeWriter : MomentsWriter {
        val calls = mutableListOf<String>()
        val uploads = mutableMapOf<String, String>()
        /** How many uploads to drop, as a proxy closes a connection, before they go through. */
        var drops = 0
        /** The revision the next write says it took. */
        var revision: Long? = null

        /** A photo's original by its uid, a live photo's motion as `<uid>/video`. */
        private fun key(mediaUid: String, part: UploadPart) = if (part == UploadPart.IMAGE) mediaUid else "$mediaUid/${part.path}"

        override suspend fun uploaded(momentUid: String, mediaUid: String, part: UploadPart): String? =
            uploads[key(mediaUid, part)]?.let { text ->
                java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            }

        override suspend fun upload(momentUid: String, mediaUid: String, part: UploadPart, file: File, contentType: String, onSent: (Long) -> Unit) {
            calls += "upload ${key(mediaUid, part)} $contentType"
            if (drops > 0) {
                drops--
                onSent(2)
                throw java.io.IOException("connection closed")
            }
            uploads[key(mediaUid, part)] = file.readText()
            onSent(file.length())
        }

        override suspend fun applyChanges(changes: ChangeSet): AppliedChanges {
            calls += "changes places=${changes.places.keys} moments=${changes.moments.map { "${it.uid} ${it.places}" }} deleted=${changes.deleted}"
            val places = changes.places.keys.associateWith { if (it.startsWith("p-")) "hu-bian" else it }
            val moments = changes.moments.map { moment ->
                moment.copy(
                    places = moment.places.map { places[it] ?: it },
                    published = moment.published ?: PublishedMoment("2026-10-02T00:00:00Z"),
                    media = moment.media.map { it.copy(local = null, published = it.published ?: PublishedMedia("moments/x/${it.uid}.jpg", "image/jpeg", 5, "etag")) },
                )
            }
            return AppliedChanges(places, moments, changes.deleted.filter { it != "GONE" }, revision)
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

        // F is private: the phone's to see, marked so.
        assertEquals(listOf("C", "F", "E", "D", "A"), repository.moments.value.map { it.uid })
        assertEquals("moved to another month", repository.moment("C")?.text)
        assertEquals(2, api.calls, "four changes come in two pages")
        assertEquals(
            listOf("moments/2026/10/A.json", "moments/2026/10/D.json", "moments/2026/10/E.json", "moments/2026/10/F.json", "moments/2026/10/G.json", "moments/2026/11/C.json"),
            File(directory, "moments").walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.sorted().toList(),
            "a removed Moment's file goes, and a moved one leaves its old month",
        )

        val restarted = repository()
        restarted.load()
        assertEquals(listOf("C", "F", "E", "D", "A"), restarted.moments.value.map { it.uid })
        assertEquals("南京", restarted.places.value["nanjing"]?.name)
    }

    @Test
    fun `the Moments about tech are left out unless asked for, private ones shown to the phone`() = runBlocking {
        api.put(moment("A", "2026-10-01"))
        api.put(moment("B", "2026-10-02", topic = "tech"))
        api.put(moment("C", "2026-10-03", topic = "tech", hidden = true))
        api.put(moment("D", "2026-10-04", hidden = true))
        val repository = repository()
        repository.load()
        repository.refresh()
        assertEquals(listOf("D", "A"), repository.moments.value.map { it.uid })
        assertEquals(null, repository.moment("B"))

        repository.changeShowTech(true)
        assertEquals(listOf("D", "C", "B", "A"), repository.moments.value.map { it.uid })
        assertEquals("B", repository.moment("B")?.uid)

        repository.changeShowTech(false)
        assertEquals(listOf("D", "A"), repository.moments.value.map { it.uid })
    }

    @Test
    fun `the places come whole with the first refresh, then only those that changed`() = runBlocking {
        api.put(moment("A", "2026-10-01"))
        val repository = repository()
        repository.load()

        assertEquals(1L, repository.refresh().getOrNull())
        assertEquals(1, api.placesSent)
        assertEquals(1, api.calls, "one request for the Moments, the places and the trips")

        api.put(moment("B", "2026-10-02"))
        assertEquals(2L, repository.refresh().getOrNull())
        assertEquals(1, api.placesSent, "no place changed, so none comes again")
        assertEquals("南京", repository.places.value["nanjing"]?.name)

        api.putPlace("hu-bian", Place("湖边", 31.0, 120.0))
        assertEquals(3L, repository.refresh().getOrNull())
        assertEquals(2, api.placesSent)
        assertEquals(setOf("nanjing", "hu-bian"), repository.places.value.keys)

        val restarted = repository()
        restarted.load()
        assertEquals(setOf("nanjing", "hu-bian"), restarted.places.value.keys)
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
            override suspend fun changes(since: Long, cursor: String?): Changes = error("offline")
        }, Dispatchers.Unconfined)
        failing.load()

        assertTrue(failing.refresh().isFailure)
        assertEquals("offline", failing.error.value?.message)
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

        val progress = mutableListOf<PublishProgress>()
        restarted.publishPending { progress += it }

        assertEquals(
            listOf(
                "upload P image/jpeg",
                "changes places=[p-made] moments=[L [p-made, nanjing], M [p-made], T []] deleted=[S]",
            ),
            writer.calls,
            "the photo first, then everything else in one write, oldest first, the phone's place with it",
        )
        assertEquals(
            listOf(PublishProgress(0, 2, 0, 5), PublishProgress(0, 2, 5, 5), PublishProgress(1, 2, 5, 5), PublishProgress(2, 2)),
            progress,
            "the photo's bytes as they go, then each step",
        )
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
    fun `a site's place's kind changes on the phone and goes with the changes, under its own id`() = runBlocking {
        val writer = FakeWriter()
        fun repository() = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        val repository = repository()
        api.put(moment("S", "2026-10-01"))
        repository.load()
        repository.refresh()

        repository.setPlaceKind("nanjing", "city")
        repository.setPlaceKind("nanjing", "scenic")
        assertEquals("scenic", repository.places.value["nanjing"]?.kind, "shown as changed at once")
        assertEquals(listOf("nanjing" to "scenic"), repository.pending.value.places.map { it.id to it.after.kind })
        assertEquals(null, repository.pending.value.places.single().before.kind, "with what it was")
        assertEquals(emptyList(), writer.calls)

        val restarted = repository()
        restarted.load()
        assertEquals(1, restarted.pending.value.size, "it outlasts a restart")
        restarted.publishPending()

        assertEquals(listOf("changes places=[nanjing] moments=[] deleted=[]"), writer.calls)
        assertEquals(0, restarted.pending.value.size)
        assertEquals("scenic", restarted.places.value["nanjing"]?.kind, "the site's now")

        restarted.setPlaceKind("nanjing", "food")
        restarted.revertPlace("nanjing")
        assertEquals("scenic", restarted.places.value["nanjing"]?.kind, "taken back, it is the site's again")
        assertEquals(0, restarted.pending.value.size)
    }

    @Test
    fun `a deletion or a take-back still undoable when the app is closed is done when it starts again`() = runBlocking {
        api.put(moment("S", "2026-10-01", text = "the site's"))
        val repository = repository()
        repository.load()
        repository.refresh()
        repository.addLocal(moment("L", "2026-10-02", text = "written here", published = false))
        repository.addLocal(moment("K", "2026-10-03", text = "kept", published = false))
        repository.updateLocal(repository.moment("S")!!.copy(text = "changed here"))

        repository.setAside("L")
        repository.setAside("K")
        repository.bringBack("K")
        repository.holdRevert("S")
        assertEquals(listOf("K", "S"), repository.moments.value.map { it.uid }, "set aside, it is out of sight")
        assertEquals("the site's", repository.moments.value.first { it.uid == "S" }.text, "held, the site's shows")

        // Closed with the snackbars still up: neither went, neither was undone.
        val restarted = repository()
        restarted.load()
        assertEquals(null, restarted.moment("L"), "the deletion is done")
        assertTrue(!restarted.isLocal("L"))
        assertEquals("kept", restarted.moment("K")?.text, "one brought back stays")
        assertEquals("the site's", restarted.moment("S")?.text, "the change is taken back")
        assertTrue(!restarted.isChanged("S"))
        assertEquals(listOf("K"), restarted.pending.value.added.map { it.uid })
        assertEquals(emptyList(), restarted.pending.value.changed.map { it.after.uid })

        val again = repository()
        again.load()
        assertEquals(listOf("K", "S"), again.moments.value.map { it.uid }, "and stays done")
    }

    @Test
    fun `a write right after the copy's revision moves the copy on to it, one after another's doesn't`() = runBlocking {
        val writer = FakeWriter()
        val store = DataStore(directory)
        val repository = MomentsRepository(store, api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        api.put(moment("A", "2026-10-01"))
        repository.load()
        repository.refresh()
        assertEquals(1, store.readRevision())

        repository.addLocal(moment("L", "2026-10-02", published = false).copy(places = listOf("nanjing")))
        writer.revision = 2
        repository.publishPending()
        assertEquals(2, store.readRevision(), "the copy has all the write did")

        repository.addLocal(moment("M", "2026-10-03", published = false).copy(places = listOf("nanjing")))
        writer.revision = 5
        repository.publishPending()
        assertEquals(2, store.readRevision(), "writes in between are for the next sync to bring")
    }

    @Test
    fun `a photo the API already has, as after a publish that failed, isn't sent again`() = runBlocking {
        val writer = FakeWriter()
        val repository = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        api.put(moment("A", "2026-10-01"))
        repository.load()
        repository.refresh()
        val copy = File(localDirectory, "media/Q.jpg").apply { parentFile.mkdirs(); writeText("same photo") }
        val other = File(localDirectory, "media/R.jpg").apply { writeText("new photo") }
        repository.addLocal(
            moment("L", "2026-10-02", published = false).copy(
                places = listOf("nanjing"),
                media = listOf(
                    MomentMedia("Q", MediaType.IMAGE, width = 4, height = 3, local = LocalMedia(copy.toURI().toString(), copied = true)),
                    MomentMedia("R", MediaType.IMAGE, width = 4, height = 3, local = LocalMedia(other.toURI().toString(), copied = true)),
                ),
            ),
        )
        writer.uploads["Q"] = "same photo"
        writer.uploads["R"] = "an older photo"

        repository.publishPending()

        assertEquals(listOf("upload R image/jpeg"), writer.calls.filter { it.startsWith("upload") })
    }

    @Test
    fun `an upload whose connection drops is tried again, its bytes counted once`() = runBlocking {
        val writer = FakeWriter().apply { drops = 1 }
        val repository = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        repository.load()
        val copy = File(localDirectory, "media/D.jpg").apply { parentFile.mkdirs(); writeText("photo") }
        repository.addLocal(
            moment("L", "2026-10-02", published = false).copy(
                media = listOf(MomentMedia("D", MediaType.IMAGE, width = 4, height = 3, local = LocalMedia(copy.toURI().toString(), copied = true))),
            ),
        )
        val progress = mutableListOf<PublishProgress>()

        repository.publishPending { progress += it }

        assertEquals(listOf("upload D image/jpeg", "upload D image/jpeg"), writer.calls.filter { it.startsWith("upload") })
        assertEquals("photo", writer.uploads["D"])
        assertEquals(listOf(0L, 2L, 0L, 5L, 5L, 0L), progress.map { it.sent }, "the dropped try's bytes are taken back")
    }

    @Test
    fun `a live photo sends its still and its motion, and both copies go once published`() = runBlocking {
        val writer = FakeWriter()
        val repository = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        repository.load()
        val still = File(localDirectory, "media/V.jpg").apply { parentFile.mkdirs(); writeText("still") }
        val motion = File(localDirectory, "media/V.mp4").apply { writeText("motion") }
        repository.addLocal(
            moment("L", "2026-10-02", published = false).copy(
                media = listOf(
                    MomentMedia(
                        "V", MediaType.LIVE_PHOTO, width = 3, height = 4,
                        local = LocalMedia(still.toURI().toString(), videoUri = motion.toURI().toString(), copied = true),
                    ),
                ),
            ),
        )

        repository.publishPending()

        assertEquals(listOf("upload V image/jpeg", "upload V/video video/mp4"), writer.calls.filter { it.startsWith("upload") })
        assertEquals("motion", writer.uploads["V/video"])
        assertTrue(!still.exists() && !motion.exists(), "the copies go with the Moment")
    }

    @Test
    fun `a video sends only itself, its poster staying on the phone until it goes`() = runBlocking {
        val writer = FakeWriter()
        val repository = MomentsRepository(DataStore(directory), api, Dispatchers.Unconfined, local = DataStore(localDirectory), writer = writer)
        repository.load()
        val poster = File(localDirectory, "media/W-poster.jpg").apply { parentFile.mkdirs(); writeText("poster") }
        val video = File(localDirectory, "media/W.mov").apply { writeText("video") }
        repository.addLocal(
            moment("M", "2026-10-03", published = false).copy(
                media = listOf(
                    MomentMedia(
                        "W", MediaType.VIDEO, width = 1080, height = 1920,
                        local = LocalMedia(poster.toURI().toString(), videoUri = video.toURI().toString(), copied = true),
                    ),
                ),
            ),
        )

        repository.publishPending()

        assertEquals(listOf("upload W/video video/quicktime"), writer.calls.filter { it.startsWith("upload") })
        assertEquals("video", writer.uploads["W/video"])
        assertTrue(!poster.exists() && !video.exists(), "the copies go with the Moment")
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

    private fun moment(uid: String, day: String, text: String = "", hidden: Boolean = false, published: Boolean = true, topic: String? = null) =
        MomentRecord(
            uid = uid,
            occurredAt = day,
            text = text,
            hidden = hidden,
            topic = topic,
            published = if (published) PublishedMoment("${day}T00:00:00Z") else null,
            createdAt = "${day}T00:00:00Z",
            updatedAt = "${day}T00:00:00Z",
        )
}
