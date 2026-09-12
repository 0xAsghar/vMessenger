package ir.vmessenger.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS identity (
                id INTEGER NOT NULL PRIMARY KEY,
                ed25519Public BLOB NOT NULL,
                identityHash BLOB NOT NULL,
                userHash TEXT NOT NULL,
                x25519StaticPublic BLOB NOT NULL,
                createdAtUnixMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS key_material (
                alias TEXT NOT NULL PRIMARY KEY,
                wrappedPrivateKey BLOB NOT NULL,
                updatedAtUnixMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact (
                id TEXT NOT NULL PRIMARY KEY,
                identityHash BLOB NOT NULL,
                ed25519Public BLOB NOT NULL,
                userHash TEXT NOT NULL,
                displayName TEXT NOT NULL,
                verified INTEGER NOT NULL,
                blocked INTEGER NOT NULL,
                createdAtUnixMs INTEGER NOT NULL,
                lastSeenUnixMs INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contact_identityHash ON contact(identityHash)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS endpoint_cache (
                identityHash BLOB NOT NULL PRIMARY KEY,
                endpointsProto BLOB NOT NULL,
                sequence INTEGER NOT NULL,
                fetchedAtUnixMs INTEGER NOT NULL,
                expiresAtUnixMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_endpoint_cache_identityHash ON endpoint_cache(identityHash)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bootstrap_node (
                address TEXT NOT NULL PRIMARY KEY,
                publicKey BLOB,
                source TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                lastOkUnixMs INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bootstrap_node_address ON bootstrap_node(address)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateConversationTables(db)
        migrateMessagingTables(db)
    }
}

private fun migrateConversationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS conversation (
            id TEXT NOT NULL PRIMARY KEY,
            contactId TEXT NOT NULL,
            lastMessageId TEXT,
            lastActivityUnixMs INTEGER NOT NULL,
            unreadCount INTEGER NOT NULL,
            muted INTEGER NOT NULL,
            FOREIGN KEY(contactId) REFERENCES contact(id) ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_contactId ON conversation(contactId)")
}

private fun migrateMessagingTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS message (
            messageId TEXT NOT NULL PRIMARY KEY,
            conversationId TEXT NOT NULL,
            direction TEXT NOT NULL,
            contentType TEXT NOT NULL,
            body TEXT,
            replyToMessageId TEXT,
            status TEXT NOT NULL,
            createdAtUnixMs INTEGER NOT NULL,
            sentAtUnixMs INTEGER,
            deliveredAtUnixMs INTEGER,
            readAtUnixMs INTEGER,
            FOREIGN KEY(conversationId) REFERENCES conversation(id) ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_message_conversationId ON message(conversationId)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_messageId ON message(messageId)")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS outbox (
            messageId TEXT NOT NULL PRIMARY KEY,
            conversationId TEXT NOT NULL,
            sealedPayload BLOB,
            attemptCount INTEGER NOT NULL,
            nextAttemptUnixMs INTEGER NOT NULL,
            lastError TEXT
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_conversationId ON outbox(conversationId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_nextAttemptUnixMs ON outbox(nextAttemptUnixMs)")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS session (
            contactId TEXT NOT NULL PRIMARY KEY,
            sealedState BLOB NOT NULL,
            updatedAtUnixMs INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_session_contactId ON session(contactId)")
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_share (
                shareId TEXT NOT NULL PRIMARY KEY,
                contactId TEXT NOT NULL,
                direction TEXT NOT NULL,
                active INTEGER NOT NULL,
                startedAtUnixMs INTEGER NOT NULL,
                endedAtUnixMs INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_location_share_contactId ON location_share(contactId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_sample (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                shareId TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracyM REAL NOT NULL,
                speedMps REAL,
                headingDeg REAL,
                batteryPct INTEGER,
                sampledAtUnixMs INTEGER NOT NULL,
                FOREIGN KEY(shareId) REFERENCES location_share(shareId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_sample_shareId ON location_sample(shareId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_sample_sampledAtUnixMs " +
                "ON location_sample(sampledAtUnixMs)",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN x25519StaticPublic BLOB")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bootstrap_node ADD COLUMN priority INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE bootstrap_node ADD COLUMN lastFailUnixMs INTEGER")
        db.execSQL("ALTER TABLE bootstrap_node ADD COLUMN failCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS relay_node (
                address TEXT NOT NULL PRIMARY KEY,
                publicKey BLOB,
                source TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                lastOkUnixMs INTEGER,
                priority INTEGER NOT NULL DEFAULT 100,
                lastFailUnixMs INTEGER,
                failCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_relay_node_address ON relay_node(address)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mailbox_blob (
                blobId TEXT NOT NULL PRIMARY KEY,
                recipientIdentityHash BLOB NOT NULL,
                sealedPayload BLOB NOT NULL,
                expiresAtUnixMs INTEGER NOT NULL,
                createdAtUnixMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_blob_recipient ON mailbox_blob(recipientIdentityHash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mailbox_blob_expires ON mailbox_blob(expiresAtUnixMs)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dht_record (
                recordKey TEXT NOT NULL PRIMARY KEY,
                recordProto BLOB NOT NULL,
                sequence INTEGER NOT NULL,
                expiresAtUnixMs INTEGER NOT NULL,
                storedAtUnixMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identity ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE contact ADD COLUMN relationshipStatus TEXT NOT NULL DEFAULT 'APPROVED'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_request (
                requestId TEXT NOT NULL PRIMARY KEY,
                requesterIdentityHash BLOB NOT NULL,
                requesterUserHash TEXT NOT NULL,
                requesterDisplayName TEXT NOT NULL,
                requesterEd25519Public BLOB NOT NULL,
                requesterX25519StaticPublic BLOB,
                receivedAtUnixMs INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_contact_request_requesterIdentityHash " +
                "ON contact_request(requesterIdentityHash)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_access (
                contactId TEXT NOT NULL PRIMARY KEY,
                canSeeMyLocation INTEGER NOT NULL,
                updatedAtUnixMs INTEGER NOT NULL,
                FOREIGN KEY(contactId) REFERENCES contact(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentName TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentMimeType TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentSizeBytes INTEGER")
        db.execSQL("ALTER TABLE message ADD COLUMN attachmentPath TEXT")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_request ADD COLUMN rejectCount INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox ADD COLUMN receiptWaitCount INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Protocol v2 schema (version 16). Statements are appended here by the v2 batches:
 * - static-key pinning: `contact.pendingX25519StaticPublic`, `contact.keyChangedAtUnixMs`
 * - mailbox per-sender quota: `mailbox_blob.senderIdentityHash`
 * - attachments: `message.attachmentSha256`, `message.attachmentEncrypted`, index `(conversationId, direction, status)`
 * - node trust: `relay_node`/`bootstrap_node` `trust` + `learnedFromHash`; peer-learned nodes are disabled
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_15_16_STATEMENTS.forEach(db::execSQL)
    }
}

/** Exposed so a plain-SQLite test can replay the migration without Room. */
val MIGRATION_15_16_STATEMENTS: List<String> = listOf(
    "ALTER TABLE contact ADD COLUMN pendingX25519StaticPublic BLOB",
    "ALTER TABLE contact ADD COLUMN keyChangedAtUnixMs INTEGER",
    "ALTER TABLE mailbox_blob ADD COLUMN senderIdentityHash BLOB",
    "ALTER TABLE message ADD COLUMN attachmentSha256 BLOB",
    "ALTER TABLE message ADD COLUMN attachmentEncrypted INTEGER NOT NULL DEFAULT 0",
    "CREATE INDEX IF NOT EXISTS index_message_conv_dir_status ON message(conversationId, direction, status)",
    "ALTER TABLE relay_node ADD COLUMN trust TEXT NOT NULL DEFAULT 'COMMUNITY'",
    "ALTER TABLE relay_node ADD COLUMN learnedFromHash BLOB",
    "ALTER TABLE bootstrap_node ADD COLUMN trust TEXT NOT NULL DEFAULT 'COMMUNITY'",
    "ALTER TABLE bootstrap_node ADD COLUMN learnedFromHash BLOB",
    "UPDATE relay_node SET trust='BUILT_IN' WHERE source='BUILT_IN'",
    "UPDATE relay_node SET trust='USER' WHERE source='USER'",
    "UPDATE bootstrap_node SET trust='BUILT_IN' WHERE source='BUILT_IN'",
    "UPDATE bootstrap_node SET trust='USER' WHERE source='USER'",
    "UPDATE relay_node SET enabled=0 WHERE source IN ('PEER_EXCHANGE','CACHED_DHT')",
    "UPDATE bootstrap_node SET enabled=0 WHERE source IN ('PEER_EXCHANGE','CACHED_DHT')",
)

/**
 * Version 17: the index the conversation's windowed paging query needs.
 *
 * `MessageDao.observeConversation(cid, limit)` runs
 * `WHERE conversationId = ? ORDER BY createdAtUnixMs DESC LIMIT ?`; without a
 * composite index SQLite sorts the whole conversation on every emission.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_16_17_STATEMENTS.forEach(db::execSQL)
    }
}

/** Exposed so a plain-SQLite test can replay the migration without Room. */
val MIGRATION_16_17_STATEMENTS: List<String> = listOf(
    "CREATE INDEX IF NOT EXISTS index_message_conv_created ON message(conversationId, createdAtUnixMs)",
)
