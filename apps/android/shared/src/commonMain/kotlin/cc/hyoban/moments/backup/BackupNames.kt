package cc.hyoban.moments.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** What the phone's backups are called in the WebDAV directory: `here-backup-<when>.zip`, sorted by name as by time. */
object BackupNames {
    private const val PREFIX = "here-backup-"
    private const val SUFFIX = ".zip"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The name of a backup made at [at]. */
    fun name(at: LocalDateTime = LocalDateTime.now()) = "$PREFIX${at.format(STAMP)}$SUFFIX"

    fun isBackup(name: String) = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** When the backup called [name] was made, or null for a name that isn't a backup's. */
    fun madeAt(name: String): LocalDateTime? = if (!isBackup(name)) null else try {
        LocalDateTime.parse(name.removePrefix(PREFIX).removeSuffix(SUFFIX), STAMP)
    } catch (_: DateTimeParseException) {
        null
    }
}
