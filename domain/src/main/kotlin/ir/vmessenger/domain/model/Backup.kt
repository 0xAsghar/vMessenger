package ir.vmessenger.domain.model

/** What to include in an exported identity backup bundle. */
data class BackupOptions(
    val includeConversations: Boolean = true,
)

/** Unencrypted header fields of a backup bundle; readable without the passphrase. */
data class BackupHeaderInfo(
    val version: Int,
    val kdfOps: Int,
    val kdfMemBytes: Long,
)

/** Counts of what a successful restore inserted. */
data class RestoreSummary(
    val contacts: Int,
    val conversations: Int,
    val messages: Int,
)
