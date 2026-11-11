package cc.hyoban.moments.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// What a backup holds: every database the app keeps and the files beside them, in one zip,
// the photos and videos left out. Each database is copied whole by SQLite itself (VACUUM
// INTO), so a write made while it is read leaves the copy as it was before or after, never
// half of each. Restoring unpacks a backup beside the app's files and puts it in place the
// next time the app starts, before anything opens them; what it replaced is kept aside.

object BackupArchive {
    /** The databases, by their names in `noBackupFilesDir`: the ledger, the footprints and the copy of the API's records. */
    private val DATABASES = listOf("ledger.sqlite", "footprints.sqlite", "copy.db")

    /** The footprints' reminders and companions, kept as JSON beside the databases. */
    private val FILES = listOf("connection-reminders.json", "companion-connections.json")

    /** The directory of `local/` that holds the photos and videos, which a backup leaves out. */
    private const val MEDIA = "media"

    /** The name of a backup made at [at]; sorted by name, the backups are sorted by time. */
    fun name(at: LocalDateTime = LocalDateTime.now()) = BackupNames.name(at)

    fun isBackup(name: String) = BackupNames.isBackup(name)

    /** When the backup called [name] was made, or null for a name that isn't a backup's. */
    fun madeAt(name: String): LocalDateTime? = BackupNames.madeAt(name)

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

    /** Where a backup waits, unpacked, for the app to start again; in `noBackupFilesDir`. */
    private const val RESTORE = "restore"

    /** What the last restore replaced, kept until the next one; in `noBackupFilesDir`. */
    const val REPLACED = "restore-replaced"

    /** Written in [RESTORE] once a backup is wholly unpacked, and in [REPLACED] once everything it replaces is moved there. */
    private const val READY = ".ready"
    private const val ASIDE = ".aside"

    /** The files SQLite keeps beside a database. */
    private val SIDE = listOf("-wal", "-shm", "-journal")

    /**
     * Where an entry of a backup goes, as a path under the restore: a database or file the app
     * keeps, or a file of `local/` outside its photos and videos; null for anything else.
     */
    fun restoredPath(entry: String): String? {
        if (entry.startsWith("databases/")) return entry.takeIf { it.removePrefix("databases/") in DATABASES + FILES }
        if (!entry.startsWith("local/") || entry.endsWith("/") || entry.contains('\\')) return null
        val parts = entry.removePrefix("local/").split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." } || parts.first() == MEDIA) return null
        return entry
    }

    /**
     * Unpacks the backup [zip] for the app to put in place when it next starts. A zip that
     * holds none of the app's databases isn't one of its backups, and nothing is left to restore.
     */
    fun stage(context: Context, zip: File) {
        val into = File(context.noBackupFilesDir, RESTORE)
        into.deleteRecursively()
        var databases = 0
        try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    val path = restoredPath(entry.name) ?: return@forEach
                    val file = File(into, path)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                    if (entry.time > 0) file.setLastModified(entry.time)
                    if (path.removePrefix("databases/") in DATABASES) databases++
                }
            }
            check(databases > 0) { "Not a backup of the app" }
            File(into, READY).createNewFile()
        } catch (error: Exception) {
            into.deleteRecursively()
            throw error
        }
    }

    /**
     * Puts a backup [stage] unpacked in place of the app's databases and `local/`, its photos
     * and videos left where they are; what it replaces goes to [REPLACED]. Run as the app starts,
     * before anything opens them. Started again after being cut off, it carries on.
     */
    fun restoreStaged(context: Context) {
        val data = context.noBackupFilesDir
        val staged = File(data, RESTORE)
        if (!File(staged, READY).isFile) {
            staged.deleteRecursively()
            return
        }
        val replaced = File(data, REPLACED)
        val local = File(context.filesDir, "local")
        if (!File(replaced, ASIDE).isFile) {
            if (!File(replaced, READY).isFile) {
                replaced.deleteRecursively()
                replaced.mkdirs()
                File(replaced, READY).createNewFile()
            }
            for (name in DATABASES + FILES) {
                for (file in listOf(name) + SIDE.map { name + it }) aside(File(data, file), File(replaced, "databases/$file"))
            }
            local.listFiles()?.filter { it.name != MEDIA }?.forEach { aside(it, File(replaced, "local/${it.name}")) }
            File(replaced, ASIDE).createNewFile()
        }
        File(staged, "databases").listFiles()?.forEach { it.renameTo(File(data, it.name)) }
        local.mkdirs()
        File(staged, "local").listFiles()?.forEach { it.renameTo(File(local, it.name)) }
        staged.deleteRecursively()
        File(replaced, READY).delete()
        File(replaced, ASIDE).delete()
    }

    private fun aside(file: File, to: File) {
        if (!file.exists() || to.exists()) return
        to.parentFile?.mkdirs()
        check(file.renameTo(to)) { "Couldn't move ${file.name} aside" }
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
