package cc.hyoban.moments.ledger

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import cc.hyoban.moments.data.local.Ulid
import java.io.File
import java.time.LocalDate
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The ledger on this phone: an SQLite database in `noBackupFilesDir`, outside the API's sync and
 * Android's backup, as the footprints are. A bill is private unless its book says new bills are
 * public or it says so itself; only then does what is public of it (its amount, kind, category
 * and note) go out with 全部发布到网站, and [Txn.published] keeps what last went. What it holds is read whole
 * into [ledger] after each change, a few thousand bills being quick to read. Its own JSON
 * backup is the way to keep a copy elsewhere.
 */
class LedgerStore(private val context: Context) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "ledger.sqlite").absolutePath, null, 3,
) {
    private val _ledger = MutableStateFlow(Ledger())
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, name TEXT NOT NULL, currency TEXT NOT NULL, position INTEGER NOT NULL, archived INTEGER NOT NULL, visibility TEXT NOT NULL DEFAULT 'private')")
        db.execSQL(
            "CREATE TABLE accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, currency TEXT NOT NULL, opening INTEGER NOT NULL, " +
                "counted INTEGER NOT NULL, archived INTEGER NOT NULL, note TEXT NOT NULL, position INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, book TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, parent TEXT, position INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE txns (id TEXT PRIMARY KEY, book TEXT NOT NULL, kind TEXT NOT NULL, transfer TEXT NOT NULL, time INTEGER NOT NULL, offset INTEGER NOT NULL, " +
                "amount INTEGER NOT NULL, currency TEXT NOT NULL, category TEXT, account TEXT, account_amount INTEGER, splits TEXT, to_account TEXT, to_amount INTEGER, " +
                "fee INTEGER NOT NULL, fee_account TEXT, discount INTEGER NOT NULL, discount_account TEXT, note TEXT NOT NULL, tags TEXT NOT NULL, related TEXT, " +
                "reimburse TEXT NOT NULL, excluded INTEGER NOT NULL, moment TEXT, place TEXT, trip TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                "visibility TEXT, published TEXT)",
        )
        db.execSQL("CREATE INDEX txns_time ON txns(time)")
        db.execSQL("CREATE TABLE rates (currency TEXT NOT NULL, day TEXT NOT NULL, per_cny REAL NOT NULL, PRIMARY KEY (currency, day))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Tags went into the notes; the column stays, empty.
        if (oldVersion < 2) {
            db.rawQuery("SELECT id, note, tags FROM txns WHERE tags != ''", null).use { rows ->
                while (rows.moveToNext()) {
                    val note = noteWithTags(rows.getString(1).orEmpty(), rows.getString(2).orEmpty().split('\n').filter { it.isNotBlank() })
                    db.update("txns", ContentValues().apply { put("note", note); put("tags", "") }, "id = ?", arrayOf(rows.getString(0)))
                }
            }
        }
        // Books and bills became public or private, every one private until said otherwise.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'")
            db.execSQL("ALTER TABLE txns ADD COLUMN visibility TEXT")
            db.execSQL("ALTER TABLE txns ADD COLUMN published TEXT")
        }
    }

    /** Reads the ledger, the bundled rates going in first the first time. */
    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@LedgerStore) {
            val empty = readableDatabase.rawQuery("SELECT COUNT(*) FROM rates", null).use { it.moveToFirst(); it.getInt(0) == 0 }
            if (empty) writeRates(bundledRates())
            publish()
        }
        _loaded.value = true
    }

    private fun bundledRates(): Map<String, Map<LocalDate, Double>> =
        runCatching { context.assets.open("ledger-rates.json").bufferedReader().use { Rates.parseSeries(it.readText()) } }.getOrDefault(emptyMap())

    private fun publish() {
        _ledger.value = read()
    }

    private fun read(): Ledger {
        val db = readableDatabase
        val books = db.rawQuery("SELECT id, name, currency, position, archived, visibility FROM books ORDER BY position, rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(Book(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0, c.getString(5))) }
        }
        val accounts = db.rawQuery("SELECT id, name, kind, currency, opening, counted, archived, note, position FROM accounts ORDER BY position, rowid", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Account(c.getString(0), c.getString(1), AccountKind.of(c.getString(2)), c.getString(3), c.getLong(4), c.getInt(5) != 0, c.getInt(6) != 0, c.getString(7), c.getInt(8)),
                )
            }
        }
        val categories = db.rawQuery("SELECT id, book, type, name, parent, position FROM categories ORDER BY position, rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(Category(c.getString(0), c.getString(1), CategoryType.of(c.getString(2)), c.getString(3), c.stringOrNull(4), c.getInt(5))) }
        }
        val txns = db.rawQuery("SELECT * FROM txns ORDER BY time DESC, created DESC", null).use { c -> buildList { while (c.moveToNext()) add(c.txn()) } }
        val rates = db.rawQuery("SELECT currency, day, per_cny FROM rates", null).use { c ->
            val out = mutableMapOf<String, TreeMap<LocalDate, Double>>()
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { TreeMap() }[LocalDate.parse(c.getString(1))] = c.getDouble(2)
            Rates(out)
        }
        val current = db.rawQuery("SELECT value FROM meta WHERE key='book'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        val all = db.rawQuery("SELECT value FROM meta WHERE key='all'", null).use { it.moveToFirst() && it.getString(0) == "1" }
        return Ledger(books, accounts, categories, txns, rates, current, all)
    }

    private suspend fun write(block: (SQLiteDatabase) -> Unit) = withContext(Dispatchers.IO) {
        synchronized(this@LedgerStore) {
            val db = writableDatabase
            db.transaction {
                block(db)
            }
            publish()
        }
    }

    suspend fun saveTxn(txn: Txn) = write { it.replace("txns", txn.values()) }

    /** Deletes bills, and the refunds and reimbursements of any of them with them. */
    suspend fun deleteTxns(ids: Collection<String>) = write { db ->
        val all = ids.toSet() + _ledger.value.txns.filter { it.related in ids }.map { it.id }
        all.forEach { db.delete("txns", "id=?", arrayOf(it)) }
    }

    suspend fun saveAccount(account: Account) = write { it.replace("accounts", account.values()) }

    /** Deletes an account no bill uses. */
    suspend fun deleteAccount(id: String) = write { db ->
        check(_ledger.value.txns.none { it.uses(id) }) { "Account in use" }
        db.delete("accounts", "id=?", arrayOf(id))
    }

    suspend fun saveBook(book: Book) = write { it.replace("books", book.values()) }

    /** Waits for disk state before checking, then creates at most one initial book. */
    internal suspend fun ensureDefaultBook(defaultName: String, chinese: Boolean): Book {
        loaded.first { it }
        return withContext(Dispatchers.IO) {
            synchronized(this@LedgerStore) {
                _ledger.value.book ?: run {
                    val book = Book(Ulid.next(), defaultName, "CNY")
                    val db = writableDatabase
                    db.transaction {
                        db.replace("books", book.values())
                        defaultCategories(book.id, chinese).forEach { db.replace("categories", it.values()) }
                        db.replace("meta", ContentValues().apply { put("key", "book"); put("value", book.id) })
                        db.replace("meta", ContentValues().apply { put("key", "all"); put("value", "0") })
                    }
                    publish()
                    book
                }
            }
        }
    }

    /** Deletes a book with its bills and categories. */
    suspend fun deleteBook(id: String) = write { db ->
        db.delete("txns", "book=?", arrayOf(id))
        db.delete("categories", "book=?", arrayOf(id))
        db.delete("books", "id=?", arrayOf(id))
    }

    suspend fun setCurrentBook(id: String) = write {
        it.replace("meta", ContentValues().apply { put("key", "book"); put("value", id) })
        it.replace("meta", ContentValues().apply { put("key", "all"); put("value", "0") })
    }

    /** Shows every book at once, a new bill still going to the one chosen last. */
    suspend fun showAllBooks() = write { it.replace("meta", ContentValues().apply { put("key", "all"); put("value", "1") }) }

    suspend fun saveCategory(category: Category) = write { it.replace("categories", category.values()) }

    /** Deletes a category no bill uses, and its own second-level ones, which no bill may use either. */
    suspend fun deleteCategory(id: String) = write { db ->
        val ids = setOf(id) + _ledger.value.categories.filter { it.parent == id }.map { it.id }
        check(_ledger.value.txns.none { it.category in ids }) { "Category in use" }
        ids.forEach { db.delete("categories", "id=?", arrayOf(it)) }
    }

    /** Moves the bills of one category to another, as merging two does. */
    suspend fun moveCategory(from: String, to: String) = write { db ->
        db.update("txns", ContentValues().apply { put("category", to) }, "category=?", arrayOf(from))
        db.update("categories", ContentValues().apply { put("parent", to) }, "parent=?", arrayOf(from))
        db.delete("categories", "id=?", arrayOf(from))
    }

    suspend fun putRates(rates: Map<String, Map<LocalDate, Double>>) = write { writeRatesIn(it, rates) }

    private fun writeRates(rates: Map<String, Map<LocalDate, Double>>) {
        val db = writableDatabase
        db.transaction {
            writeRatesIn(db, rates)
        }
    }

    private fun writeRatesIn(db: SQLiteDatabase, rates: Map<String, Map<LocalDate, Double>>) {
        rates.forEach { (currency, days) ->
            days.forEach { (day, rate) ->
                db.replace("rates", null, ContentValues().apply { put("currency", currency); put("day", day.toString()); put("per_cny", rate) })
            }
        }
    }

    /**
     * Writes what an import or a backup brought: in place of everything when [replace], else
     * beside it, a book, an account or a category taking the place of one of the same id.
     */
    suspend fun putAll(books: List<Book>, accounts: List<Account>, categories: List<Category>, txns: List<Txn>, replace: Boolean) = write { db ->
        if (replace) listOf("books", "accounts", "categories", "txns").forEach { db.delete(it, null, null) }
        books.forEach { db.replace("books", it.values()) }
        accounts.forEach { db.replace("accounts", it.values()) }
        categories.forEach { db.replace("categories", it.values()) }
        txns.forEach { db.replace("txns", it.values()) }
    }

    /** Sets several bills' links to Moments, places and trips at once. */
    suspend fun saveTxns(txns: List<Txn>) = write { db -> txns.forEach { db.replace("txns", it.values()) } }

    /** Notes what of each bill the site now has, by id: its public part as JSON, or null when it has none. */
    suspend fun markPublished(parts: Map<String, String?>) = write { db ->
        parts.forEach { (id, part) -> db.update("txns", ContentValues().apply { put("published", part) }, "id = ?", arrayOf(id)) }
    }

    private fun SQLiteDatabase.replace(table: String, values: ContentValues) {
        replaceOrThrow(table, null, values)
    }
}

/** Whether a bill moves money in or out of [account] in any way. */
fun Txn.uses(account: String): Boolean =
    this.account == account || toAccount == account || feeAccount == account || discountAccount == account || splits.any { it.account == account }

private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun Cursor.txn(): Txn {
    fun s(name: String) = stringOrNull(getColumnIndexOrThrow(name))
    fun l(name: String) = longOrNull(getColumnIndexOrThrow(name))
    return Txn(
        id = s("id")!!,
        book = s("book")!!,
        kind = TxnKind.of(s("kind")),
        transfer = TransferType.of(s("transfer")),
        time = l("time")!!,
        offsetMinutes = l("offset")!!.toInt(),
        amount = l("amount")!!,
        currency = s("currency")!!,
        category = s("category"),
        account = s("account"),
        accountAmount = l("account_amount"),
        splits = decodeSplits(s("splits")),
        toAccount = s("to_account"),
        toAmount = l("to_amount"),
        fee = l("fee") ?: 0,
        feeAccount = s("fee_account"),
        discount = l("discount") ?: 0,
        discountAccount = s("discount_account"),
        note = s("note").orEmpty(),
        related = s("related"),
        reimburse = Reimburse.of(s("reimburse")),
        excluded = l("excluded") == 1L,
        moment = s("moment"),
        place = s("place"),
        trip = s("trip"),
        createdAt = l("created")!!,
        updatedAt = l("updated")!!,
        visibility = s("visibility"),
        published = s("published"),
    )
}

/** Splits as `account\tcents` lines. */
internal fun encodeSplits(splits: List<Split>): String? = splits.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.account}\t${it.amount}" }

internal fun decodeSplits(text: String?): List<Split> = text.orEmpty().lines().mapNotNull { line ->
    val parts = line.split('\t')
    if (parts.size == 2) parts[1].toLongOrNull()?.let { Split(parts[0], it) } else null
}

private fun Txn.values() = ContentValues().apply {
    put("id", id); put("book", book); put("kind", kind.value); put("transfer", transfer.value)
    put("time", time); put("offset", offsetMinutes); put("amount", amount); put("currency", currency)
    put("category", category); put("account", account); put("account_amount", accountAmount); put("splits", encodeSplits(splits))
    put("to_account", toAccount); put("to_amount", toAmount); put("fee", fee); put("fee_account", feeAccount)
    put("discount", discount); put("discount_account", discountAccount); put("note", note); put("tags", "")
    put("related", related); put("reimburse", reimburse.value); put("excluded", if (excluded) 1 else 0)
    put("moment", moment); put("place", place); put("trip", trip); put("created", createdAt); put("updated", updatedAt)
    put("visibility", visibility); put("published", published)
}

private fun Account.values() = ContentValues().apply {
    put("id", id); put("name", name); put("kind", kind.value); put("currency", currency); put("opening", opening)
    put("counted", if (counted) 1 else 0); put("archived", if (archived) 1 else 0); put("note", note); put("position", position)
}

private fun Book.values() = ContentValues().apply {
    put("id", id); put("name", name); put("currency", currency); put("position", position); put("archived", if (archived) 1 else 0)
    put("visibility", visibility)
}

private fun Category.values() = ContentValues().apply {
    put("id", id); put("book", book); put("type", type.value); put("name", name); put("parent", parent); put("position", position)
}
