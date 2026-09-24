package ir.vmessenger.core.database.migration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A database exactly as 1.1.2 left it, upgraded to what 2.0 opens.
 *
 * Exactly as it left it means created by Room from 1.1.2's own schema (20), the way every fresh
 * 1.1.2 install's was — not rebuilt through the migration chain from version 1, which is the path
 * [MigrationTest] replays and not one a 1.1.2 user necessarily took. One row in every table, every
 * column filled, then the migrations 2.0 ships for 20 → 24.
 */
class V1DatabaseUpgradeTest {
    private val database = JdbcSupportDatabase()
    private val released = RoomSchema(RELEASED_1_1_2)
    private val expected = RoomSchema(CURRENT)

    @AfterTest
    fun tearDown() = database.close()

    @Test
    fun `every row of a 1_1_2 database survives the upgrade, value for value`() {
        val before = populatedRelease()

        upgrade()

        before.forEach { (table, rows) ->
            assertEquals(rows, snapshot(table, released.tables.getValue(table)), "rows of $table")
        }
    }

    @Test
    fun `the upgraded database is exactly the schema 2_0 expects`() {
        populatedRelease()

        upgrade()

        val tables = database.tables().filterNot { it in BOOKKEEPING }.toSet()
        assertEquals(expected.tables.keys, tables)
        expected.tables.forEach { (table, columns) ->
            assertEquals(columns.map { it.name }.toSet(), database.columns(table).toSet(), "columns of $table")
            columns.forEach { column ->
                val notNull = database.isNotNull(table, column.name)
                assertEquals(column.notNull, notNull, "NOT NULL on $table.${column.name}")
            }
        }
    }

    @Test
    fun `what 2_0 added reads as absent on a 1_1_2 message, not as zero`() {
        populatedRelease()

        upgrade()

        val added = database.query("SELECT `expiresAtUnixMs`, `albumId`, `albumIndex` FROM `message`") {
            listOf(it.getString("expiresAtUnixMs"), it.getString("albumId"), it.getString("albumIndex"))
        }
        assertEquals(listOf(listOf<String?>(null, null, null)), added)
    }

    /** Schema 20 as Room created it, one fully filled row per table; returns what each table holds. */
    private fun populatedRelease(): Map<String, List<List<String?>>> {
        released.create(database)
        released.tables.forEach { (table, columns) ->
            val names = columns.joinToString { "`${it.name}`" }
            val values = columns.joinToString { valueFor(table, it) }
            database.exec("INSERT INTO `$table` ($names) VALUES ($values)")
        }
        return released.tables.mapValues { (table, columns) -> snapshot(table, columns) }
    }

    private fun upgrade() = listOf(MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
        .forEach { it.migrate(database.db) }

    private fun snapshot(table: String, columns: List<RoomColumn>): List<List<String?>> =
        database.query("SELECT ${columns.joinToString { "`${it.name}`" }} FROM `$table`") { row ->
            columns.map { row.getString(it.name) }
        }

    private fun valueFor(table: String, column: RoomColumn): String = when (column.affinity) {
        "INTEGER" -> "1"
        "REAL" -> "1.5"
        "BLOB" -> "X'0A0B0C'"
        else -> "'$table.${column.name}'"
    }

    private companion object {
        /** The schema 1.1.2 shipped. */
        const val RELEASED_1_1_2 = 20
        const val CURRENT = 24

        /** Tables SQLite and Room keep for themselves. */
        val BOOKKEEPING = setOf("room_master_table", "sqlite_sequence", "android_metadata")
    }
}
