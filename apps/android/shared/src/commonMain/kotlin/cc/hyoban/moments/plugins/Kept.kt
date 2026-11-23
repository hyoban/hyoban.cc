package cc.hyoban.moments.plugins

/**
 * What a plugin keeps of its own in the device's private folder (the phone's `noBackupFilesDir`,
 * out of Android's backup), by name, and what of it the phone's WebDAV backup carries and puts back
 * on restore. The backup holds every plugin's, whatever modules are on, in the plugins' order, the
 * host's own (the records' copy) after them ([HOST_KEPT]).
 */
class KeptFiles(
    /** Its SQLite databases, copied whole by SQLite into the backup's `databases/`. */
    val databases: List<String> = emptyList(),
    /** Its files, copied as they are beside the databases. */
    val files: List<String> = emptyList(),
    /** What it keeps there that the backup leaves out: secrets, what is worked out again, the device's own. */
    val left: List<String> = emptyList(),
)

/** The ledger's database, in the phone's private folder and its backup, which the Mac reads. */
const val LEDGER_DATABASE = "ledger.sqlite"

/** The footprints' database, in the phone's private folder and its backup, which the Mac reads. */
const val FOOTPRINTS_DATABASE = "footprints.sqlite"

/** The footprints' connection reminders and their companions' last states, kept as JSON beside it. */
const val CONNECTION_REMINDERS_FILE = "connection-reminders.json"
const val COMPANION_CONNECTIONS_FILE = "companion-connections.json"

/** What the host keeps of its own there: the copy of the API's records, backed up, and the routers' answers, worked out again. */
val HOST_KEPT = KeptFiles(
    databases = listOf(cc.hyoban.moments.COPY_DATABASE),
    left = listOf(cc.hyoban.moments.TRAVEL_CACHE_FILE),
)
