package cc.hyoban.moments.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import cc.hyoban.moments.Prefs
import cc.hyoban.moments.Setting
import cc.hyoban.moments.Token
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.footprints.FixExtras
import cc.hyoban.moments.footprints.Footprints
import cc.hyoban.moments.footprints.RecordingState
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.TrackShare
import cc.hyoban.moments.footprints.refinedTrack
import cc.hyoban.moments.ledger.Account
import cc.hyoban.moments.ledger.AccountKind
import cc.hyoban.moments.ledger.Book
import cc.hyoban.moments.ledger.Category
import cc.hyoban.moments.ledger.CategoryType
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.LedgerSource
import cc.hyoban.moments.ledger.Rates
import cc.hyoban.moments.ledger.TXN_COLUMNS
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.txnOf
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The last backup read, when the phone made it; or why it couldn't be read. */
data class BackupRead(val madeAt: LocalDateTime?, val failure: String? = null)

/**
 * The phone's latest backup, read on a desktop or a tablet from the owner's WebDAV directory:
 * the ledger and the footprints in it, read-only. Nothing is written back, to the backup or the
 * phone; a bill is written, and the footprints recorded, on the phone alone. The databases are
 * kept under [folder] until the next read replaces them.
 */
class PhoneBackup(prefs: Prefs, secrets: File, private val folder: File, private val driver: SQLiteDriver) {
    /** The WebDAV directory the phone backs up to; none until set. */
    val url = Setting(prefs, "phone_backup_url", "")
    val user = Setting(prefs, "phone_backup_user", "")
    val password = Token(File(secrets, "phone-backup-password"))

    val configured get() = url.isSet

    private val _read = MutableStateFlow(readMade())
    /** The backup read last, or why the last read failed; null before any. */
    val read: StateFlow<BackupRead?> = _read.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Mutex()

    /** Keeps the directory and who signs in; a blank [password] keeps the one there is. */
    fun change(url: String, user: String, password: String) {
        this.url.set(url.trim())
        this.user.set(user.trim())
        if (password.isNotEmpty()) this.password.set(password)
    }

    /** Fetches the newest backup in the directory and reads its ledger and footprints. */
    suspend fun refresh() = lock.withLock {
        if (!configured) return@withLock
        _busy.value = true
        try {
            withContext(Dispatchers.IO) {
                val dav = WebDav(Http.client, url.value, user.value, password.value)
                val newest = dav.list().map { it.name }.filter(BackupNames::isBackup).maxOrNull() ?: error("No backup in the directory")
                folder.mkdirs()
                val zip = File(folder, "backup.zip.tmp")
                try {
                    dav.get(newest, zip)
                    unpack(zip)
                } finally {
                    zip.delete()
                }
                File(folder, MADE).writeText(newest)
            }
            _read.value = readMade()
            ledger.reload()
            footprints.reload()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            _read.value = BackupRead(_read.value?.madeAt, error.message ?: error::class.simpleName)
        } finally {
            _busy.value = false
        }
    }

    /** Puts the ledger's and the footprints' databases of [zip] in place of those read before. */
    private fun unpack(zip: File) {
        val wanted = setOf(LEDGER, FOOTPRINTS)
        val fresh = mutableMapOf<String, File>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                val name = entry.name.removePrefix("databases/").takeIf { entry.name.startsWith("databases/") && it in wanted } ?: return@forEach
                val file = File(folder, "$name.tmp")
                file.outputStream().use { input.copyTo(it) }
                fresh[name] = file
            }
        }
        check(fresh.isNotEmpty()) { "Not a backup of the app" }
        synchronized(this) {
            fresh.forEach { (name, file) ->
                val into = File(folder, name)
                listOf("", "-wal", "-shm", "-journal").forEach { File(folder, name + it).delete() }
                file.renameTo(into)
            }
        }
    }

    private fun readMade(): BackupRead? = File(folder, MADE).takeIf(File::isFile)?.readText()?.trim()?.let { BackupRead(BackupNames.madeAt(it)) }

    /** Reads [block] from the database [name] of the backup, or [empty] when there is none. */
    private fun <T> read(name: String, empty: T, block: SQLiteConnection.() -> T): T = synchronized(this) {
        val file = File(folder, name)
        if (!file.isFile) return empty
        runCatching { driver.open(file.path).let { connection -> try { connection.block() } finally { connection.close() } } }.getOrDefault(empty)
    }

    /** The phone's ledger as its backup holds it, which pages here only show. */
    val ledger = BackupLedger()

    inner class BackupLedger : LedgerSource {
        override val writable: Boolean get() = false
        private val _ledger = MutableStateFlow(Ledger())
        override val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

        fun reload() {
            _ledger.value = read(LEDGER, Ledger()) { readLedger() }
        }

        // Choosing a book only changes what is shown here; the backup stays as it is.
        override suspend fun setCurrentBook(id: String) {
            _ledger.value = _ledger.value.copy(currentBook = id, allBooks = false)
        }

        override suspend fun showAllBooks() {
            _ledger.value = _ledger.value.copy(allBooks = true)
        }

        override suspend fun saveBook(book: Book) {}
        override suspend fun saveTxn(txn: Txn) {}
        override suspend fun saveCategory(category: Category) {}
        override suspend fun saveAccount(account: Account) {}
        override suspend fun ensureBook(defaultName: String, chinese: Boolean) {}
    }

    /** The phone's footprints as its backup holds them, shown here and never recorded. */
    val footprints = BackupFootprints()

    inner class BackupFootprints : Footprints {
        private val _revision = MutableStateFlow(0L)
        override val revision: StateFlow<Long> = _revision.asStateFlow()

        fun reload() {
            _revision.value++
        }

        override fun state() = RecordingState()

        override fun days(): List<String> = read(FOOTPRINTS, emptyList()) {
            prepare("SELECT DISTINCT day FROM points ORDER BY day DESC").use { buildList { while (it.step()) add(it.getText(0)) } }
        }

        override fun points(selection: TrackSelection): List<TrackPoint> = read(FOOTPRINTS, emptyList()) {
            val (where, args) = when {
                selection.session != null -> "session=?" to listOf(selection.session)
                selection.day != null -> "day=?" to listOf(selection.day)
                selection.from != null && selection.to != null -> "time BETWEEN ? AND ?" to listOf(selection.from.toString(), selection.to.toString())
                else -> "1=1" to emptyList()
            }
            prepare(
                "SELECT time,lat,lon,accuracy,altitude,segment,speed,speed_accuracy,bearing,bearing_accuracy,vertical_accuracy,msl_altitude," +
                    "provider,sats_used,sats_visible,cn0 FROM points WHERE $where ORDER BY time,id",
            ).use { statement ->
                args.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
                refinedTrack(sequence { while (statement.step()) yield(statement.point()) }).toList()
            }
        }

        override fun shares(): List<TrackShare> = read(FOOTPRINTS, emptyList()) {
            prepare("SELECT uid, start, end, collection, simplify, removed, published FROM track_shares ORDER BY start").use { c ->
                buildList {
                    while (c.step()) add(TrackShare(c.getText(0), c.getLong(1), c.getLong(2), c.textOrNull(3), c.getDouble(4), c.getLong(5) != 0L, c.textOrNull(6)))
                }
            }
        }

        override fun saveShare(share: TrackShare) {}
        override fun removeShare(uid: String) {}
    }

    init {
        ledger.reload()
    }

    private companion object {
        const val LEDGER = "ledger.sqlite"
        const val FOOTPRINTS = "footprints.sqlite"

        /** The name of the backup read last, beside its databases. */
        const val MADE = "backup-name"
    }
}

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
private fun SQLiteStatement.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun SQLiteStatement.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

private fun SQLiteStatement.point() = TrackPoint(
    getLong(0), getDouble(1), getDouble(2), getDouble(3), doubleOrNull(4), getText(5), doubleOrNull(6), doubleOrNull(7),
    FixExtras(doubleOrNull(8), doubleOrNull(9), doubleOrNull(10), doubleOrNull(11), textOrNull(12), longOrNull(13)?.toInt(), longOrNull(14)?.toInt(), doubleOrNull(15)),
)

/** The ledger in [this] database, as the phone's store reads its own. */
private fun SQLiteConnection.readLedger(): Ledger {
    val books = prepare("SELECT id, name, currency, position, archived, visibility FROM books ORDER BY position, rowid").use { c ->
        buildList { while (c.step()) add(Book(c.getText(0), c.getText(1), c.getText(2), c.getLong(3).toInt(), c.getLong(4) != 0L, c.getText(5))) }
    }
    val accounts = prepare("SELECT id, name, kind, currency, opening, counted, archived, note, position FROM accounts ORDER BY position, rowid").use { c ->
        buildList {
            while (c.step()) add(
                Account(c.getText(0), c.getText(1), AccountKind.of(c.getText(2)), c.getText(3), c.getLong(4), c.getLong(5) != 0L, c.getLong(6) != 0L, c.getText(7), c.getLong(8).toInt()),
            )
        }
    }
    val categories = prepare("SELECT id, book, type, name, parent, position FROM categories ORDER BY position, rowid").use { c ->
        buildList { while (c.step()) add(Category(c.getText(0), c.getText(1), CategoryType.of(c.getText(2)), c.getText(3), c.textOrNull(4), c.getLong(5).toInt())) }
    }
    val columns = TXN_COLUMNS.split(",").map { it.trim() }
    val txns = prepare("SELECT $TXN_COLUMNS FROM txns ORDER BY time DESC, created DESC").use { c ->
        buildList {
            while (c.step()) add(txnOf({ c.textOrNull(columns.indexOf(it)) }, { c.longOrNull(columns.indexOf(it)) }))
        }
    }
    val rates = prepare("SELECT currency, day, per_cny FROM rates").use { c ->
        val out = mutableMapOf<String, TreeMap<LocalDate, Double>>()
        while (c.step()) out.getOrPut(c.getText(0)) { TreeMap() }[LocalDate.parse(c.getText(1))] = c.getDouble(2)
        Rates(out)
    }
    val current = prepare("SELECT value FROM meta WHERE key='book'").use { if (it.step()) it.getText(0) else null }
    return Ledger(books, accounts, categories, txns, rates, current, true)
}
