package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A server this device set up as a node ("Your servers"). No secrets: an SSH password or key is
 * never stored — updating the node asks for it again. What is kept is enough to find the server
 * again, recognise it (its host key), and show and check the node on it.
 *
 * Inside the encrypted database, excluded from backups, and erased by a wipe.
 */
@Entity(
    tableName = "managed_node",
    indices = [Index(value = ["host", "sshPort"], unique = true)],
)
data class ManagedNodeEntity(
    @PrimaryKey val id: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val hostKeyAlgorithm: String,
    val hostKeyFingerprint: String,
    val publicHost: String,
    val publicPort: Int,
    /** `IP_PINNED`, `DOMAIN_CA` or `DOMAIN_PINNED`. */
    val tlsMode: String,
    val domain: String?,
    val relayUrl: String,
    val bootstrapUrl: String,
    val nodeVersion: String,
    val nodeId: String?,
    val secured: Boolean,
    val keyOnlyLogin: Boolean,
    /** `INSTALLING`, `READY` or `INTERRUPTED`. */
    val status: String,
    val lastRunId: String?,
    val createdAtUnixMs: Long,
    val updatedAtUnixMs: Long,
    val lastCheckedUnixMs: Long?,
    val lastCheckOk: Boolean?,
)
