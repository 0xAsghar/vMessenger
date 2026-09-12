package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "endpoint_cache",
    indices = [Index("identityHash")],
)
data class EndpointCacheEntity(
    @PrimaryKey val identityHash: ByteArray,
    val endpointsProto: ByteArray,
    val sequence: Long,
    val fetchedAtUnixMs: Long,
    val expiresAtUnixMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EndpointCacheEntity
        return identityHash.contentEquals(other.identityHash) &&
            endpointsProto.contentEquals(other.endpointsProto) &&
            sequence == other.sequence &&
            fetchedAtUnixMs == other.fetchedAtUnixMs &&
            expiresAtUnixMs == other.expiresAtUnixMs
    }

    override fun hashCode(): Int {
        var result = identityHash.contentHashCode()
        result = 31 * result + endpointsProto.contentHashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + fetchedAtUnixMs.hashCode()
        result = 31 * result + expiresAtUnixMs.hashCode()
        return result
    }
}

@Entity(
    tableName = "bootstrap_node",
    indices = [Index(value = ["address"], unique = true)],
)
data class BootstrapNodeEntity(
    @PrimaryKey val address: String,
    val publicKey: ByteArray?,
    val source: String,
    val enabled: Boolean,
    val lastOkUnixMs: Long?,
    val priority: Int = DEFAULT_NODE_PRIORITY,
    val lastFailUnixMs: Long? = null,
    val failCount: Int = 0,
    /** [ir.vmessenger.core.common.network.NodeTrust] name; community nodes are stored disabled. */
    val trust: String = DEFAULT_NODE_TRUST,
    /** Identity hash of the peer that told us about this node (peer exchange), if any. */
    val learnedFromHash: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BootstrapNodeEntity
        return address == other.address &&
            publicKey.contentEqualsOrNull(other.publicKey) &&
            source == other.source &&
            enabled == other.enabled &&
            lastOkUnixMs == other.lastOkUnixMs &&
            priority == other.priority &&
            lastFailUnixMs == other.lastFailUnixMs &&
            failCount == other.failCount &&
            trust == other.trust &&
            learnedFromHash.contentEqualsOrNull(other.learnedFromHash)
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + source.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + (lastOkUnixMs?.hashCode() ?: 0)
        result = 31 * result + priority
        result = 31 * result + (lastFailUnixMs?.hashCode() ?: 0)
        result = 31 * result + failCount
        result = 31 * result + trust.hashCode()
        result = 31 * result + (learnedFromHash?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

/**
 * A relay endpoint the device can use to forward encrypted frames. Mirrors
 * [BootstrapNodeEntity] but for the relay role, so the app no longer depends on a
 * single hardcoded relay.
 */
@Entity(
    tableName = "relay_node",
    indices = [Index(value = ["address"], unique = true)],
)
data class RelayNodeEntity(
    @PrimaryKey val address: String,
    val publicKey: ByteArray?,
    val source: String,
    val enabled: Boolean,
    val lastOkUnixMs: Long?,
    val priority: Int = DEFAULT_NODE_PRIORITY,
    val lastFailUnixMs: Long? = null,
    val failCount: Int = 0,
    /** [ir.vmessenger.core.common.network.NodeTrust] name; community nodes are stored disabled. */
    val trust: String = DEFAULT_NODE_TRUST,
    /** Identity hash of the peer that told us about this node (peer exchange), if any. */
    val learnedFromHash: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RelayNodeEntity
        return address == other.address &&
            publicKey.contentEqualsOrNull(other.publicKey) &&
            source == other.source &&
            enabled == other.enabled &&
            lastOkUnixMs == other.lastOkUnixMs &&
            priority == other.priority &&
            lastFailUnixMs == other.lastFailUnixMs &&
            failCount == other.failCount &&
            trust == other.trust &&
            learnedFromHash.contentEqualsOrNull(other.learnedFromHash)
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + source.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + (lastOkUnixMs?.hashCode() ?: 0)
        result = 31 * result + priority
        result = 31 * result + (lastFailUnixMs?.hashCode() ?: 0)
        result = 31 * result + failCount
        result = 31 * result + trust.hashCode()
        result = 31 * result + (learnedFromHash?.contentHashCode() ?: 0)
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

const val DEFAULT_NODE_PRIORITY = 100

/** Matches `NodeTrust.COMMUNITY` and the migration-16 column default. */
const val DEFAULT_NODE_TRUST = "COMMUNITY"
