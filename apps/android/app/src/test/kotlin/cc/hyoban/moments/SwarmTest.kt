package cc.hyoban.moments

import cc.hyoban.moments.data.copy.CopyStore
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.store.DataStore
import cc.hyoban.moments.data.store.MomentsRepository
import cc.hyoban.moments.data.swarm.Swarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swarm's export, made up in its shape: what it brings in, and that bringing it in again adds nothing. */
class SwarmTest {
    private val checkins = """
        {"count":3,"items":[
          {"id":"c3","createdAt":"2026-10-03 04:00:00.000000","type":"checkin","timeZoneOffset":480,"venue":{"id":"v3","name":"Book Shop"},"lat":30.1,"lng":120.1},
          {"id":"c1","createdAt":"2026-10-01 01:30:00.000000","type":"checkin","visibility":"closeFriends","entities":[],"shout":"面很好吃\n下次再来","timeZoneOffset":480,"venue":{"id":"v1","name":"Noodle House","canonicalName":"面馆"},"lat":30.25,"lng":120.16,"hacc":12},
          {"id":"c2","createdAt":"2026-10-02 23:10:00.000000","type":"checkin","private":true,"visibility":"private","timeZoneOffset":480,"venue":{"id":"v2","name":"Lake","canonicalName":"Lake"},"lat":30.2,"lng":120.2,"hacc":5}
        ]}
    """.trimIndent()
    private val photos = """
        {"count":2,"items":[
          {"id":"p2","createdAt":"2026-10-02 23:12:00.000000","prefix":"https://example.com/img/","suffix":"/1_b.jpg","width":1200,"height":900,"visibility":"friends","relatedItemUrl":"https://swarmapp.com/checkin/c2"},
          {"id":"p1","createdAt":"2026-10-02 23:11:00.000000","prefix":"https://example.com/img/","suffix":"/1_a.jpg","width":900,"height":1200,"visibility":"public","relatedItemUrl":"https://swarmapp.com/checkin/c2"}
        ]}
    """.trimIndent()

    private fun zip(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("export/pix/1_a.jpg", "photo a")
            put("export/checkins1.json", checkins)
            put("export/users.json", """{"self":{"firstName":"x"}}""")
            put("export/photos1.json", photos)
            put("export/pix/1_b.jpg", "photo b")
            put("export/pix/1_c.jpg", "not of a check-in")
        }
    }.toByteArray()

    private val now = "2026-11-02T00:00:00Z"

    @Test
    fun `the check-ins come in their own offset, oldest first, with their photos in order`() {
        val files = Swarm.readFiles(zip().inputStream())
        assertEquals(setOf("checkins1.json", "photos1.json"), files.keys)
        val read = Swarm.checkins(files)
        assertEquals(listOf("c1", "c2", "c3"), read.map { it.id })
        val (noodles, lake, shop) = read
        assertEquals("2026-10-01T09:30:00+08:00", noodles.at)
        assertEquals("面很好吃\n下次再来", noodles.shout)
        assertEquals("面馆", noodles.alias)
        assertEquals("面馆", Entries.checkinName(Swarm.part(noodles)), "the local name shows before the export's")
        assertEquals("Lake", Entries.checkinName(Swarm.part(lake)))
        assertNull(lake.alias, "an alias the same as the name says nothing")
        assertEquals("2026-10-03T07:10:00+08:00", lake.at)
        assertEquals(listOf("1_a.jpg", "1_b.jpg"), lake.photos.map { it.file })
        assertEquals("2026-10-03T07:11:00+08:00", lake.photos.first().at)
        assertTrue(noodles.isMoment && lake.isMoment && !shop.isMoment)
        assertEquals(Entries.stableUlid(noodles.at, "swarm:c1"), noodles.uid)
    }

    @Test
    fun `words or photos make a private Moment that keeps its point and check-in on the way out`() {
        val directory = Files.createTempDirectory("swarm-media").toFile()
        val (noodles, lake, shop) = Swarm.checkins(Swarm.readFiles(zip().inputStream()))
        val wanted = lake.photos.associate { it.file to Swarm.mediaUid(it) }
        val copies = Swarm.copyPhotos(zip().inputStream(), wanted, directory)
        assertEquals(setOf("1_a.jpg", "1_b.jpg"), copies.keys)
        assertEquals("photo a", copies.getValue("1_a.jpg").first.readText())

        val moment = Swarm.moment(lake, copies, now, null)
        assertTrue(moment.hidden)
        assertEquals("", moment.text)
        assertEquals(listOf(900 to 1200, 1200 to 900), moment.media.map { it.width to it.height })
        assertTrue(moment.media.all { it.local?.copied == true && it.local?.sha256?.length == 64 })

        val entry = Entries.fromMoment(Swarm.moment(noodles, emptyMap(), now, null))
        assertEquals("private", (entry["visibility"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(12), entry.getValue("point").jsonObject["accuracy"])
        val parts = entry.getValue("parts").jsonObject
        assertEquals(setOf("text", "checkin"), parts.keys)
        assertEquals(JsonPrimitive("面很好吃\n下次再来"), parts.getValue("text").jsonObject["text"])
        assertEquals(JsonPrimitive("c1"), parts.getValue("checkin").jsonObject.getValue("source").jsonObject["checkin"])
        assertEquals(entry, Entries.fromMoment(Entries.toMoment(entry)!!), "and comes back the same entry")

        val alone = Swarm.entry(shop, now, null)
        assertEquals(setOf("checkin"), alone.getValue("parts").jsonObject.keys)
        assertNull(Entries.toMoment(alone))
        assertFalse(Entries.isMark(alone))
        assertFalse(Entries.isPrivate(alone), "a check-in alone comes in public")
        val kept = lake.copy(shout = null, photos = emptyList())
        assertTrue(kept.private)
        assertTrue(Entries.isPrivate(Swarm.entry(kept, now, null)), "unless Swarm kept it private")
    }

    @Test
    fun `each venue is a place of its own, found again by its venue`() {
        val read = Swarm.checkins(Swarm.readFiles(zip().inputStream()))
        val (ids, places) = Swarm.places(read, emptyMap(), now)
        assertEquals(setOf("v1", "v2", "v3"), ids.keys)
        assertEquals(3, places.size)
        val noodles = places.getValue(ids.getValue("v1"))
        assertEquals("面馆", noodles.name, "named by its local name")
        assertEquals(30.25 to 120.16, noodles.latitude to noodles.longitude)
        assertEquals("v1", Swarm.venueIdOf(noodles))
        assertTrue(ids.values.all { Regex("p-[0-9a-hjkmnp-tv-z]{26}").matches(it) })
        assertEquals(ids, Swarm.places(read, emptyMap(), now).first, "named the same each time")

        // Once published its place has the site's id; importing again finds it by its venue.
        val (again, none) = Swarm.places(read, mapOf("mian-guan" to noodles) + places.filterKeys { it != ids["v1"] }, now)
        assertEquals("mian-guan", again["v1"])
        assertTrue(none.isEmpty())

        val lake = read.single { it.id == "c2" }
        assertEquals(listOf(ids["v2"]), Swarm.moment(lake, emptyMap(), now, ids["v2"]).places)
        val shop = read.single { it.id == "c3" }
        assertEquals(JsonPrimitive(ids["v3"]), Swarm.entry(shop, now, ids["v3"])["place"])
    }

    @Test
    fun `importing again adds nothing, and the places go with the check-ins when published`() = runBlocking {
        val root = Files.createTempDirectory("swarm-local").toFile()
        val writer = MomentsRepositoryTest.FakeWriter()
        fun repository() = MomentsRepository(
            CopyStore(File(root, "copy.db"), JdbcSQLiteDriver()),
            MomentsRepositoryTest.FakeApi(),
            Dispatchers.Unconfined,
            local = DataStore(File(root, "local")),
            writer = writer,
        )
        val read = Swarm.checkins(Swarm.readFiles(zip().inputStream()))
        val (ids, places) = Swarm.places(read, emptyMap(), now)
        val moments = read.filter { it.isMoment }.map { Swarm.moment(it, emptyMap(), now, ids[it.venueId]) }
        val entries = read.filterNot { it.isMoment }.map { Swarm.entry(it, now, ids[it.venueId]) }

        val first = repository().apply { load() }
        assertEquals(3, first.addImported(moments, entries, places))
        assertEquals(2, first.pending.value.added.size)
        assertEquals(1, first.pending.value.entries.size)
        assertEquals("Book Shop", first.places.value[ids["v3"]]?.name)

        val again = repository().apply { load() }
        assertEquals(0, again.addImported(moments, entries, places))
        assertEquals(2, again.moments.value.size)
        assertEquals(setOf((entries.single()["uid"] as JsonPrimitive).content), again.entries.value.keys)
        assertEquals("Book Shop", again.places.value[ids["v3"]]?.name, "a place only a check-in is at stays")

        again.publishPending()
        val sent = writer.calls.single { it.startsWith("changes") }
        assertTrue(ids.getValue("v3") in sent, "the place of a check-in alone goes with it: $sent")
        val shop = again.entries.value.getValue((entries.single()["uid"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive("hu-bian"), shop["place"], "and it moves to the site's id")
    }

    @Test
    fun `the check-ins alone are found once, by day and by place newest first`() {
        val read = Swarm.checkins(Swarm.readFiles(zip().inputStream()))
        val ids = Swarm.places(read, emptyMap(), now).first
        val entries = read.map { checkin ->
            if (checkin.isMoment) Entries.fromMoment(Swarm.moment(checkin, emptyMap(), now, ids[checkin.venueId]))
            else Swarm.entry(checkin, now, ids[checkin.venueId])
        }
        val shop = read.single { it.id == "c3" }
        val again = Swarm.entry(shop.copy(id = "c4", at = "2026-10-04T09:00:00+08:00"), now, ids["v3"])
        val checkins = cc.hyoban.moments.data.things.Checkins.of(entries + again)
        assertEquals(setOf("2026-10-03", "2026-10-04"), checkins.byDay.keys, "Moments aren't among them")
        assertEquals(listOf("2026-10-04", "2026-10-03"), checkins.byPlace.getValue(ids.getValue("v3")).map { it.at.take(10) })
        assertEquals("Book Shop", checkins.byDay.getValue("2026-10-03").single().venue)
    }

    /** A real export at SWARM_EXPORT, never committed: every check-in reads, each its own uid, every photo found. */
    @Test
    fun `a real export reads whole`() {
        val path = System.getenv("SWARM_EXPORT") ?: return
        val read = Swarm.checkins(Swarm.readFiles(File(path).inputStream()))
        assertTrue(read.isNotEmpty())
        assertEquals(read.size, read.map { it.uid }.toSet().size)
        val photos = read.flatMap { it.photos }
        assertEquals(photos.size, photos.map(Swarm::mediaUid).toSet().size)
        val directory = Files.createTempDirectory("swarm-real").toFile()
        val copies = Swarm.copyPhotos(File(path).inputStream(), photos.associate { it.file to Swarm.mediaUid(it) }, directory)
        assertEquals(photos.map { it.file }.toSet(), copies.keys)
        directory.deleteRecursively()
        println("swarm: ${read.size} check-ins, ${read.count { it.isMoment }} as Moments, ${photos.size} photos")
    }
}
