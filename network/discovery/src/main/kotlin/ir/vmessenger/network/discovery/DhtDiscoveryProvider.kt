package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.dht.toEndpoints
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DhtDiscoveryProvider @Inject constructor(
    private val dht: Dht,
    private val signer: EndpointRecordSigner,
) : DiscoveryProvider {
    override val id = DiscoveryProviderId("dht")
    private var sequence = 0L

    override suspend fun announce(
        self: DiscoveryIdentity,
        endpoints: List<Endpoint>,
        ed25519PrivateKey: ByteArray,
    ): AppResult<Unit> {
        sequence += 1
        val now = System.currentTimeMillis()
        val record = signer.sign(
            identityHash = self.identityHash,
            identityPub = self.ed25519PublicKey,
            endpoints = endpoints,
            publishedAtUnixMs = now,
            ttlMs = DEFAULT_TTL_MS,
            sequence = sequence,
            ed25519PrivateKey = ed25519PrivateKey,
        )
        return dht.publish(record)
    }

    override suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>> =
        when (val result = dht.lookup(identityHash)) {
            is AppResult.Success -> {
                val endpoints = result.data?.toEndpoints() ?: emptyList()
                AppResult.Success(endpoints)
            }
            is AppResult.Error -> result
        }

    companion object {
        const val DEFAULT_TTL_MS = 20 * 60 * 1000L
    }
}
