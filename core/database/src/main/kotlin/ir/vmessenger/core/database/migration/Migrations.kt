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
