package ir.vmessenger.core.database.migration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cascades of schema 18, on a real SQLite engine.
 *
 * They exist to stop orphans, but they are also the sharpest edge in this schema: an
 * `@Insert(OnConflictStrategy.REPLACE)` on a parent row is `INSERT OR REPLACE`, which
 * **deletes** the old row first and takes its children with it. That is not hypothetical —
 * a group's whole message history was erased that way by a re-sent membership snapshot,
 * because the handler upserted the group. These tests pin the behaviour so the next person
 * reaching for `upsert` on a parent table sees why the DAOs offer `insert` + `update` instead.
 */
class CascadeTest {
    private val database = JdbcSupportDatabase()

    @AfterTest
    fun tearDown() = database.close()

    private fun migrate() {
        UP_TO_18.forEach { it.migrate(database.db) }
        database.exec("PRAGMA foreign_keys = ON")
        database.exec("INSERT INTO chat_group VALUES ('g-1', 'Team', 'aa', 1, 1, 0, 'g-1')")
        database.exec("INSERT INTO conversation VALUES ('cv-1', NULL, 'g-1', NULL, 1, 0, 0)")
        database.exec(
            """
            INSERT INTO message (messageId, conversationId, direction, contentType, body, replyToMessageId,
                status, createdAtUnixMs, sentAtUnixMs, deliveredAtUnixMs, readAtUnixMs, attachmentEncrypted)
            VALUES ('m-1', 'cv-1', 'INCOMING', 'TEXT', 'hello', NULL, 'DELIVERED', 10, NULL, NULL, NULL, 0)
            """.trimIndent(),
        )
    }

    private fun messageCount(): Int = database.query("SELECT COUNT(*) FROM message") { it.getInt(1) }.single()

    @Test
    fun `replacing a group row erases its conversation and every message in it`() {
        migrate()

        // Exactly what @Insert(REPLACE) compiles to.
        database.exec("INSERT OR REPLACE INTO chat_group VALUES ('g-1', 'Renamed', 'aa', 1, 2, 0, 'g-1')")

        assertEquals(0, database.query("SELECT COUNT(*) FROM conversation") { it.getInt(1) }.single())
        assertEquals(0, messageCount())
    }

    @Test
    fun `updating a group row in place keeps its history`() {
        migrate()

        database.exec("UPDATE chat_group SET name = 'Renamed', version = 2 WHERE id = 'g-1'")

        assertEquals(1, messageCount())
    }

    @Test
    fun `deleting a message takes its delivery rows with it`() {
        migrate()
        database.exec("INSERT INTO message_recipient VALUES ('m-1', 'aa', 'SENT', 1, NULL, NULL)")

        database.exec("DELETE FROM message WHERE messageId = 'm-1'")

        assertEquals(0, database.query("SELECT COUNT(*) FROM message_recipient") { it.getInt(1) }.single())
    }

    @Test
    fun `deleting a group takes its membership with it`() {
        migrate()
        database.exec(
            "INSERT INTO chat_group_member VALUES ('g-1', 'aa', X'00', NULL, 'Ali', 'CREATOR', 1, NULL)",
        )

        database.exec("DELETE FROM chat_group WHERE id = 'g-1'")

        assertEquals(0, database.query("SELECT COUNT(*) FROM chat_group_member") { it.getInt(1) }.single())
    }
}
