package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult

@JvmInline
value class BootstrapProviderId(val value: String)

data class BootstrapNode(
    val address: String,
    val nodeId: ByteArray? = null,
    val publicKey: ByteArray? = null,
    val source: BootstrapProviderId,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BootstrapNode
        return address == other.address &&
            nodeId.contentEqualsOrNull(other.nodeId) &&
            publicKey.contentEqualsOrNull(other.publicKey) &&
            source == other.source
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (nodeId?.contentHashCode() ?: 0)
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + source.hashCode()
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

interface BootstrapProvider {
    val id: BootstrapProviderId
    val priority: Int
    suspend fun nodes(): AppResult<List<BootstrapNode>>
}
