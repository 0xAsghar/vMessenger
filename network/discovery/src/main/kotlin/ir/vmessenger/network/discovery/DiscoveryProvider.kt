package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint

@JvmInline
value class DiscoveryProviderId(val value: String)

data class DiscoveryIdentity(
    val identityHash: ByteArray,
    val ed25519PublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiscoveryIdentity
        return identityHash.contentEquals(other.identityHash) &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey)
    }

    override fun hashCode(): Int =
        31 * identityHash.contentHashCode() + ed25519PublicKey.contentHashCode()
}

interface DiscoveryProvider {
    val id: DiscoveryProviderId
    suspend fun announce(
        self: DiscoveryIdentity,
        endpoints: List<Endpoint>,
        ed25519PrivateKey: ByteArray,
    ): AppResult<Unit>
    suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>>
}
