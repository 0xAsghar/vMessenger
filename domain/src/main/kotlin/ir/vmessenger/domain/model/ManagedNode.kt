package ir.vmessenger.domain.model

import ir.vmessenger.core.common.version.SemVer

/** How a set-up node's certificate is trusted. */
enum class ManagedNodeTls { IP_PINNED, DOMAIN_CA, DOMAIN_PINNED }

enum class ManagedNodeStatus { INSTALLING, READY, INTERRUPTED }

/**
 * A server this device set up as a node ("Your servers"). Holds no secret: updating the node asks
 * for the SSH password or key again.
 */
data class ManagedNode(
    val id: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val hostKeyAlgorithm: String,
    val hostKeyFingerprint: String,
    val publicHost: String,
    val publicPort: Int,
    val tls: ManagedNodeTls,
    val domain: String?,
    val relayUrl: String,
    val bootstrapUrl: String,
    val nodeVersion: String,
    val nodeId: String?,
    val secured: Boolean,
    val keyOnlyLogin: Boolean,
    val status: ManagedNodeStatus,
    val lastRunId: String?,
    val createdAtUnixMs: Long,
    val updatedAtUnixMs: Long,
    val lastCheckedUnixMs: Long? = null,
    val lastCheckOk: Boolean? = null,
) {
    /** The app carries a newer node than this server runs. */
    fun canUpdateTo(bundledVersion: String): Boolean {
        val running = SemVer.parse(nodeVersion)
        val bundled = SemVer.parse(bundledVersion)
        return running != null && bundled != null && bundled > running
    }
}
