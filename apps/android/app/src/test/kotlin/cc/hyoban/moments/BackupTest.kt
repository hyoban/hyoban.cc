package cc.hyoban.moments

import cc.hyoban.moments.backup.BackupArchive
import cc.hyoban.moments.backup.DavFile
import cc.hyoban.moments.backup.WebDav
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupTest {
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
