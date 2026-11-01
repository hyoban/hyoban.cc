package cc.hyoban.moments.data.copy

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/** One row of D1's, as `GET /v2/rows` hands it out: its table, key, revision, deletion and record's JSON. */
data class CopyRow(val table: String, val key: String, val rev: Long, val deletedAt: String?, val record: String)

/**
 * The phone's copy of the API's records, row for row in the tables D1 keeps them in
 * ([CopyTables], generated from `@hyoban/data-spec/tables`), with the revision it was last
 * brought up to. Only the rows feed writes it; the phone's own edits are kept elsewhere until
 * they are published. A copy made for other tables than these is dropped and read again.
 */
class CopyStore(private val file: File, private val driver: SQLiteDriver = AndroidSQLiteDriver()) {
    private var opened: SQLiteConnection? = null

    private val connection: SQLiteConnection
        get() = opened ?: open().also { opened = it }

    private fun open(): SQLiteConnection {
        file.parentFile?.mkdirs()
        val connection = driver.open(file.path)
        val version = CopyTables.statements.joinToString("\n").hashCode()
        if (connection.single("PRAGMA user_version") != version.toLong()) {
            val tables = connection.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            connection.transaction {
                tables.forEach { execSQL("DROP TABLE \"$it\"") }
                CopyTables.statements.forEach(::execSQL)
                execSQL("CREATE TABLE copy_state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                execSQL("PRAGMA user_version = $version")
            }
        }
        return connection
    }

    /** The API's revision the copy was last brought up to; 0 before the first sync. */
    @Synchronized fun revision(): Long = connection.single("SELECT value FROM copy_state WHERE key = 'revision'") ?: 0

    /** Which of the API's databases the copy is of, null before an API said. */
    @Synchronized fun database(): Long? = connection.single("SELECT value FROM copy_state WHERE key = 'database'")

    /**
     * Writes [rows] over the copy's and keeps [revision] and the [database] they are of, all at
     * once: after a sync from the start ([fresh]), the copy holds those rows and no others.
     */
    @Synchronized fun apply(rows: List<CopyRow>, revision: Long, fresh: Boolean = false, database: Long? = null) {
        val connection = connection
        connection.transaction {
            if (fresh) {
                CopyTables.names.forEach { execSQL("DELETE FROM $it") }
                execSQL("DELETE FROM copy_state WHERE key = 'database'")
            }
            for ((table, tableRows) in rows.groupBy { it.table }) {
                require(table in CopyTables.names) { "No table $table" }
                // One statement a table, bound again for each row.
                prepare(
                    "INSERT INTO $table (key, rev, deleted_at, record) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (key) DO UPDATE SET rev = excluded.rev, deleted_at = excluded.deleted_at, record = excluded.record",
                ).use { statement ->
                    for (row in tableRows) {
                        statement.bindText(1, row.key)
                        statement.bindLong(2, row.rev)
                        if (row.deletedAt == null) statement.bindNull(3) else statement.bindText(3, row.deletedAt)
                        statement.bindText(4, row.record)
                        statement.step()
                        statement.reset()
                    }
                }
            }
            keep("revision", revision)
            if (database != null) keep("database", database)
        }
    }

    /** A table's records still there, by key. */
    @Synchronized fun live(table: String): Map<String, String> {
        require(table in CopyTables.names) { "No table $table" }
        return connection.prepare("SELECT key, record FROM $table WHERE deleted_at IS NULL ORDER BY key").use { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), statement.getText(1)) }
        }
    }

    /** Every row of [table], deleted ones too, as the feed handed them out. */
    @Synchronized fun rows(table: String): List<CopyRow> {
        require(table in CopyTables.names) { "No table $table" }
        return connection.prepare("SELECT key, rev, deleted_at, record FROM $table ORDER BY key").use { statement ->
            buildList {
                while (statement.step()) {
                    add(CopyRow(table, statement.getText(0), statement.getLong(1), if (statement.isNull(2)) null else statement.getText(2), statement.getText(3)))
                }
            }
        }
    }

    /** Empties the copy, revision and all, as when the records are to come from another API. */
    @Synchronized fun clear() = apply(emptyList(), 0, fresh = true)

    @Synchronized fun close() {
        opened?.close()
        opened = null
    }

    private fun SQLiteConnection.keep(key: String, value: Long) =
        prepare("INSERT INTO copy_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
            .use { it.bindText(1, key); it.bindLong(2, value); it.step() }

    /** Runs [block] in one transaction, rolled back if it throws. */
    private inline fun SQLiteConnection.transaction(block: SQLiteConnection.() -> Unit) {
        execSQL("BEGIN IMMEDIATE")
        try {
            block()
            execSQL("COMMIT")
        } catch (error: Throwable) {
            execSQL("ROLLBACK")
            throw error
        }
    }

    private fun SQLiteConnection.single(sql: String): Long? = prepare(sql).use { if (it.step() && !it.isNull(0)) it.getLong(0) else null }

    private fun SQLiteConnection.strings(sql: String): List<String> = prepare(sql).use { buildList { while (it.step()) add(it.getText(0)) } }
}
