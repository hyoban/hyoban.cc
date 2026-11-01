package cc.hyoban.moments

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The copy's SQLite on the JVM, where the tests run and the app's bundled one, built for the
 * phone, doesn't load: the same calls over sqlite-jdbc's SQLite.
 */
class JdbcSQLiteDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = JdbcConnection(DriverManager.getConnection("jdbc:sqlite:$fileName"))
}

private class JdbcConnection(private val connection: Connection) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = JdbcStatement(connection.prepareStatement(sql))

    override fun close() = connection.close()
}

private class JdbcStatement(private val statement: PreparedStatement) : SQLiteStatement {
    private var results: ResultSet? = null
    private var ran = false

    override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
    override fun bindText(index: Int, value: String) = statement.setString(index, value)
    override fun bindNull(index: Int) = statement.setNull(index, Types.NULL)

    override fun getBlob(index: Int): ByteArray = rows().getBytes(index + 1)
    override fun getDouble(index: Int): Double = rows().getDouble(index + 1)
    override fun getLong(index: Int): Long = rows().getLong(index + 1)
    override fun getText(index: Int): String = rows().getString(index + 1)
    override fun isNull(index: Int): Boolean = rows().getObject(index + 1) == null
    override fun getColumnCount(): Int = rows().metaData.columnCount
    override fun getColumnName(index: Int): String = rows().metaData.getColumnName(index + 1)
    override fun getColumnType(index: Int): Int = rows().metaData.getColumnType(index + 1)

    override fun step(): Boolean {
        if (!ran) {
            ran = true
            if (statement.execute()) results = statement.resultSet
        }
        return results?.next() == true
    }

    override fun reset() {
        results?.close()
        results = null
        ran = false
    }

    override fun clearBindings() = statement.clearParameters()

    override fun close() {
        results?.close()
        statement.close()
    }

    private fun rows(): ResultSet = checkNotNull(results) { "No row" }
}
