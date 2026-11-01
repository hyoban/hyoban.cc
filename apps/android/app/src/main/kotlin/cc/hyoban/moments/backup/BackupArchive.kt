package cc.hyoban.moments.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// What a backup holds: every database the app keeps and the files beside them, in one zip,
// the photos and videos left out. Each database is copied whole by SQLite itself (VACUUM
// INTO), so a write made while it is read leaves the copy as it was before or after, never
// half of each; restoring is putting the files back where the zip's paths say.

object BackupArchive {
    /** The databases, by their names in `noBackupFilesDir`: the ledger, the footprints and the copy of the API's records. */
    private val DATABASES = listOf("ledger.sqlite", "footprints.sqlite", "copy.db")

    /** The footprints' reminders and companions, kept as JSON beside the databases. */
    private val FILES = listOf("connection-reminders.json", "companion-connections.json")

    /** The directory of `local/` that holds the photos and videos, which a backup leaves out. */
    private const val MEDIA = "media"

    private const val PREFIX = "here-backup-"
    private const val SUFFIX = ".zip"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The name of a backup made at [at]; sorted by name, the backups are sorted by time. */
    fun name(at: LocalDateTime = LocalDateTime.now()) = "$PREFIX${at.format(STAMP)}$SUFFIX"

    fun isBackup(name: String) = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** Of the backups among [names], those past the newest [keep]. */
    fun beyond(names: List<String>, keep: Int): List<String> = names.filter(::isBackup).sortedDescending().drop(keep)

    /** Writes the backup to [into], working in [scratch], which it leaves empty. */
    fun write(context: Context, into: File, scratch: File) {
        scratch.deleteRecursively()
        scratch.mkdirs()
        try {
            ZipOutputStream(into.outputStream().buffered()).use { zip ->
                for (name in DATABASES) {
                    val source = File(context.noBackupFilesDir, name).takeIf(File::isFile) ?: continue
                    val copy = File(scratch, name)
                    snapshot(source, copy)
                    zip.add("databases/$name", copy)
                    copy.delete()
                }
                for (name in FILES) {
                    File(context.noBackupFilesDir, name).takeIf(File::isFile)?.let { zip.add("databases/$name", it) }
                }
                val local = File(context.filesDir, "local")
                local.walkTopDown()
                    .onEnter { it == local || it.relativeTo(local).path != MEDIA }
                    .filter { it.isFile && !it.name.endsWith(".tmp") }
                    .sortedBy { it.path }
                    .forEach { zip.add("local/${it.relativeTo(local).invariantSeparatorsPath}", it) }
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** A consistent copy of the database at [source], through a connection of its own that only reads. */
    private fun snapshot(source: File, into: File) {
        into.delete()
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.execSQL("VACUUM INTO ?", arrayOf(into.path))
        }
    }

    private fun ZipOutputStream.add(path: String, file: File) {
        putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
