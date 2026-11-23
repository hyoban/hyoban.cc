package cc.hyoban.moments

import cc.hyoban.moments.backup.BackupArchive
import cc.hyoban.moments.backup.DavFile
import cc.hyoban.moments.backup.WebDav
import cc.hyoban.moments.plugins.HOST_KEPT
import cc.hyoban.moments.plugins.PhonePlugins
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupTest {
    // The phone's plugins, as the app installs them when it starts.
    init { Plugins.install(PhonePlugins.all) }

    @Test
    fun holdsWhatItHeldBeforeThePluginsSaidSo() {
        // The backup's databases and files, in the order they were written before each plugin declared its own.
        val databases = listOf("ledger.sqlite", "footprints.sqlite", "copy.db")
        val files = listOf("connection-reminders.json", "companion-connections.json", "plan-chats.json")
        assertEquals(databases, BackupArchive.databaseNames)
        assertEquals(files, BackupArchive.fileNames)
        assertEquals((databases + files).map { "databases/$it" }, BackupArchive.entries)
        // A restore moves aside the same, with SQLite's files beside each, and takes back only those.
        assertEquals((databases + files).flatMap { listOf(it, "$it-wal", "$it-shm", "$it-journal") }, BackupArchive.replacedFiles)
        for (name in databases + files) assertEquals("databases/$name", BackupArchive.restoredPath("databases/$name"))
        for (name in listOf("camera.json", "nas-password", "webdav-password", "travel-times.json")) assertNull(BackupArchive.restoredPath("databases/$name"))
    }

    /**
     * Every file the app keeps in `noBackupFilesDir` (or the container's private folder, which is it
     * on the phone), named where it is opened, by a literal or a constant, is one a plugin or the
     * host declares in `Points.kept`, backed up or left out; and each declared one is kept somewhere.
     * The archive itself, which handles what is declared, is not read.
     */
    @Test
    fun everyKeptFileIsDeclared() {
        val sources = listOf(File("src/main/kotlin"), File("../shared/src/commonMain/kotlin"), File("../shared/src/androidMain/kotlin"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val constants = sources.flatMap { file ->
            Regex("""const val (\w+)\s*=\s*"([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
        val opened = Regex("""\bFile\(\s*(?:[\w.]*noBackupFilesDir|private)\s*,\s*(?:"([^"]+)"|([A-Za-z_][\w.]*))\s*\)""")
        val kept = sources.filter { it.name != "BackupArchive.kt" }.flatMap { file ->
            opened.findAll(file.readText()).map { match ->
                match.groupValues[1].ifEmpty {
                    val name = match.groupValues[2].substringAfterLast('.')
                    constants[name] ?: error("${file.name} keeps a file named by $name, which isn't a constant")
                }
            }.toList()
        }.toSet()
        val declared = (Plugins.registry.all(Points.kept) + HOST_KEPT).flatMap { it.databases + it.files + it.left }.toSet()
        assertEquals(emptySet(), kept - declared, "kept in noBackupFilesDir without being declared in Points.kept")
        assertEquals(emptySet(), declared - kept, "declared in Points.kept but kept nowhere")
    }
    @Test
    fun namesSortByTime() {
        val name = BackupArchive.name(LocalDateTime.of(2026, 3, 4, 5, 6, 7))
        assertEquals("here-backup-20260304-050607.zip", name)
    }

    @Test
    fun keepsTheNewestAndLeavesOtherFiles() {
        val names = (1..9).map { "here-backup-2026010$it-030000.zip" } + listOf("notes.txt", "here-backup-old.txt")
        assertEquals(listOf("here-backup-20260102-030000.zip", "here-backup-20260101-030000.zip"), BackupArchive.beyond(names, 7))
        assertEquals(emptyList(), BackupArchive.beyond(names.take(3), 7))
    }

    @Test
    fun readsWhenABackupWasMade() {
        assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7), BackupArchive.madeAt("here-backup-20260304-050607.zip"))
        assertNull(BackupArchive.madeAt("here-backup-old.zip"))
        assertNull(BackupArchive.madeAt("notes.txt"))
    }

    @Test
    fun restoresOnlyWhatABackupHolds() {
        assertEquals("databases/ledger.sqlite", BackupArchive.restoredPath("databases/ledger.sqlite"))
        assertEquals("local/places/a.json", BackupArchive.restoredPath("local/places/a.json"))
        for (entry in listOf("databases/other.db", "databases/../ledger.sqlite", "local/../shared_prefs/x.xml", "local/media/a.jpg", "local/a//b", "local/dir/", "other/a", "/local/a")) {
            assertNull(BackupArchive.restoredPath(entry), entry)
        }
    }

    @Test
    fun listsTheFilesOfAPropfind() {
        // A Nextcloud-like answer: the directory itself, a directory in it, and files by path and by URL, one escaped.
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/here/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/dav/here/old/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/dav/here/here-backup-20260101-030000.zip</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>2048</d:getcontentlength></d:prop></d:propstat></d:response>
              <D:response xmlns:D="DAV:"><D:href>https://dav.example.com/dav/here/a%20note.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals(
            listOf(DavFile("here-backup-20260101-030000.zip", 2048), DavFile("a note.txt", null)),
            WebDav.files(xml.byteInputStream(), "/dav/here/"),
        )
    }
}
