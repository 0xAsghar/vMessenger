package ir.vmessenger.core.database.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * A [SupportSQLiteDatabase] backed by an in-memory SQLite through JDBC, so the
 * migrations can be replayed on a real SQLite engine in a plain JVM test — no
 * emulator, no Room, no SQLCipher.
 *
 * Only `execSQL(String)` is implemented, because that is all a migration uses;
 * anything else fails loudly rather than silently doing nothing. A dynamic proxy
 * keeps this to a few lines instead of stubbing the ~50-method interface.
 */
class JdbcSupportDatabase : AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    val db: SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> exec(args[0] as String)
            "toString" -> "JdbcSupportDatabase"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> error("SupportSQLiteDatabase.${method.name} is not supported by the migration test")
        }
    } as SupportSQLiteDatabase

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** Column names of [table], in declaration order. */
    fun columns(table: String): List<String> = query("PRAGMA table_info(`$table`)") { it.getString("name") }

    /** True when [column] of [table] is declared NOT NULL. */
    fun isNotNull(table: String, column: String): Boolean =
        query("PRAGMA table_info(`$table`)") { it.getString("name") to (it.getInt("notnull") == 1) }
            .first { it.first == column }
            .second

    fun tables(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString("name") }

    fun primaryKeyOf(table: String): List<String> =
        query("PRAGMA table_info(`$table`)") { Triple(it.getString("name"), it.getInt("pk"), 0) }
            .filter { it.second > 0 }
            .sortedBy { it.second }
            .map { it.first }

    fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(row(rs)) }
            }
        }

    override fun close() = connection.close()
}
