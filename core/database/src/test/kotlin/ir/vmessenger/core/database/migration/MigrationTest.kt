package ir.vmessenger.core.database.migration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every migration up to 17, in order; [UP_TO_18] and [UP_TO_19] add the later ones. */
internal val UP_TO_17 = listOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
    MIGRATION_15_16, MIGRATION_16_17,
)

internal val UP_TO_18 = UP_TO_17 + MIGRATION_17_18

internal val UP_TO_19 = UP_TO_18 + MIGRATION_18_19

/**
 * Replays every migration on a real SQLite engine (JDBC, in memory), because a
 * broken migration is only discovered on a user's device otherwise: Room does not
 * type-check migration SQL, and the v18 step recreates three tables.
 *
 * The DDL asserted here is the DDL Room generates for version 18
 * (`schemas/…/18.json`); if an entity changes without its migration, the column
 * and primary-key assertions below fail.
 */
class MigrationTest {
    private val database = JdbcSupportDatabase()

    @AfterTest
    fun tearDown() = database.close()

    private fun migrateTo17() = UP_TO_17.forEach { it.migrate(database.db) }

    private fun migrateTo18() {
        migrateTo17()
        MIGRATION_17_18.migrate(database.db)
    }

    private fun migrateTo19() {
        migrateTo18()
        MIGRATION_18_19.migrate(database.db)
    }

    @Test
    fun `message edit, delete and profile photos add their columns without rewriting a table`() {
        migrateTo19()

        assertContains(database.columns("message"), "editedAtUnixMs")
        assertContains(database.columns("contact"), "avatarPath")
        assertContains(database.columns("contact"), "avatarRevision")
        assertContains(database.columns("identity"), "avatarPath")
        assertContains(database.columns("identity"), "avatarRevision")
    }

    @Test
    fun `an existing message survives the 19 migration with no edit recorded`() {
        migrateTo18()
        database.db.execSQL(
            """
            INSERT INTO `message` (
                `messageId`, `conversationId`, `direction`, `contentType`, `body`,
                `replyToMessageId`, `status`, `createdAtUnixMs`, `sentAtUnixMs`,
                `deliveredAtUnixMs`, `readAtUnixMs`, `attachmentEncrypted`
            ) VALUES ('m1', 'c1', 'OUTGOING', 'TEXT', 'salam', NULL, 'SENT', 1, 1, NULL, NULL, 0)
            """.trimIndent(),
        )

        MIGRATION_18_19.migrate(database.db)

        // The row is still there and reads as never edited, rather than as edited at epoch zero.
        val edited = database.query("SELECT `editedAtUnixMs` FROM `message` WHERE `messageId` = 'm1'") {
            it.getString("editedAtUnixMs")
        }
        assertEquals(listOf<String?>(null), edited)
    }

    @Test
    fun `every migration up to 18 replays on a real sqlite engine`() {
        migrateTo18()

        val tables = database.tables()
        assertContains(tables, "chat_group")
        assertContains(tables, "chat_group_member")
        assertContains(tables, "message_recipient")
        assertContains(tables, "conversation")
        assertContains(tables, "outbox")
    }

    @Test
    fun `the dead session table is gone`() {
        migrateTo17()
        assertContains(database.tables(), "session", "fixture check: v17 still has the dead table")

        MIGRATION_17_18.migrate(database.db)

        assertFalse("session" in database.tables())
    }

    @Test
    fun `conversation keeps its rows and gains a nullable contactId plus groupId`() {
        migrateTo17()
        seedContactAndConversation()

        MIGRATION_17_18.migrate(database.db)

        assertEquals(
            listOf("id" to "c-1", "contactId" to "contact-1"),
            database.query("SELECT id, contactId FROM conversation") {
                listOf("id" to it.getString("id"), "contactId" to it.getString("contactId"))
            }.single(),
        )
        assertContains(database.columns("conversation"), "groupId")
        assertFalse(database.isNotNull("conversation", "contactId"), "a group conversation has no contact")
    }

    @Test
    fun `a group conversation is unique per group while 1 to 1 rows stay unconstrained`() {
        migrateTo18()
        database.exec(
            "INSERT INTO chat_group VALUES ('g-1', 'Team', 'aa', 1, 1, 0, 'g-1')",
        )
        database.exec("INSERT INTO conversation VALUES ('cv-1', NULL, 'g-1', NULL, 1, 0, 0)")
        // Many 1:1 conversations have a NULL groupId; SQLite treats NULLs as distinct.
        database.exec("INSERT INTO conversation VALUES ('cv-2', NULL, NULL, NULL, 1, 0, 0)")
        database.exec("INSERT INTO conversation VALUES ('cv-3', NULL, NULL, NULL, 1, 0, 0)")

        assertFailsWith<java.sql.SQLException> {
            database.exec("INSERT INTO conversation VALUES ('cv-4', NULL, 'g-1', NULL, 1, 0, 0)")
        }
    }

    @Test
    fun `a queued 1 to 1 message is re-keyed to its recipient`() {
        migrateTo17()
        seedContactAndConversation()
        database.exec(
            """
            INSERT INTO message (messageId, conversationId, direction, contentType, body, replyToMessageId,
                status, createdAtUnixMs, sentAtUnixMs, deliveredAtUnixMs, readAtUnixMs, attachmentEncrypted)
            VALUES ('m-1', 'c-1', 'OUTGOING', 'TEXT', 'hello', NULL, 'QUEUED', 10, NULL, NULL, NULL, 0)
            """.trimIndent(),
        )
        database.exec(
            """
            INSERT INTO outbox (messageId, conversationId, sealedPayload, attemptCount, nextAttemptUnixMs,
                lastError, receiptWaitCount)
            VALUES ('m-1', 'c-1', NULL, 3, 42, 'send_failed', 1)
            """.trimIndent(),
        )

        MIGRATION_17_18.migrate(database.db)

        val row = database.query(
            "SELECT messageId, recipientIdentityHash, attemptCount, nextAttemptUnixMs, lastError FROM outbox",
        ) {
            listOf(
                it.getString(1),
                it.getString(2),
                it.getInt(3).toString(),
                it.getLong(4).toString(),
                it.getString(5),
            )
        }.single()
        assertEquals(listOf("m-1", CONTACT_ROUTING_KEY, "3", "42", "send_failed"), row)
        assertEquals(listOf("messageId", "recipientIdentityHash"), database.primaryKeyOf("outbox"))
    }

    @Test
    fun `a queued message whose contact vanished is dropped rather than re-keyed to nothing`() {
        migrateTo17()
        database.exec("INSERT INTO conversation VALUES ('c-9', 'ghost', NULL, 1, 0, 0)")
        database.exec(
            """
            INSERT INTO outbox (messageId, conversationId, sealedPayload, attemptCount, nextAttemptUnixMs,
                lastError, receiptWaitCount)
            VALUES ('m-9', 'c-9', NULL, 0, 0, NULL, 0)
            """.trimIndent(),
        )

        MIGRATION_17_18.migrate(database.db)

        assertTrue(database.query("SELECT messageId FROM outbox") { it.getString(1) }.isEmpty())
    }

    @Test
    fun `message gains the sender, caption and voice columns`() {
        migrateTo18()

        val columns = database.columns("message")
        assertContains(columns, "senderIdentityHash")
        assertContains(columns, "caption")
        assertContains(columns, "attachmentDurationMs")
        assertContains(columns, "attachmentWaveform")
        assertContains(columns, "attachmentPlayedAtUnixMs")
    }

    @Test
    fun `message_recipient is keyed per member`() {
        migrateTo18()

        assertEquals(listOf("messageId", "identityHash"), database.primaryKeyOf("message_recipient"))
    }

    /** A v17-shaped contact and its conversation (`conversation.contactId` was NOT NULL then). */
    private fun seedContactAndConversation() {
        database.exec(
            """
            INSERT INTO contact (id, identityHash, ed25519Public, userHash, displayName, verified, blocked,
                createdAtUnixMs, lastSeenUnixMs)
            VALUES ('contact-1', X'$CONTACT_HASH_HEX', X'00', 'vm2-x', 'Ali', 0, 0, 1, NULL)
            """.trimIndent(),
        )
        database.exec("INSERT INTO conversation VALUES ('c-1', 'contact-1', NULL, 1, 0, 0)")
    }

    private companion object {
        /**
         * A full 32-byte identity hash. The recipient key keeps only its 16-byte
         * routing prefix, lowercased — SQLite's `hex()` uppercases.
         */
        const val CONTACT_HASH_HEX = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        const val CONTACT_ROUTING_KEY = "0102030405060708090a0b0c0d0e0f10"
    }
}
