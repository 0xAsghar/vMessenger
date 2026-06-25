package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
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
    private val sequenceStore: PublishSequenceStore,
) : DiscoveryProvider {
    override val id = DiscoveryProviderId("dht")

    override suspend fun announce(
        self: DiscoveryIdentity,
        endpoints: List<Endpoint>,
        ed25519PrivateKey: ByteArray,
    ): AppResult<Unit> {
        var sequence = sequenceStore.nextSequence(self.identityHash)
        var result = publishAtSequence(self, endpoints, ed25519PrivateKey, sequence)
        if (result is AppResult.Error && result.error.message?.contains("Store rejected") == true) {
            val remoteSequence = when (val lookup = dht.lookup(self.identityHash)) {
                is AppResult.Success -> lookup.data?.sequence ?: 0L
                is AppResult.Error -> 0L
            }
            if (remoteSequence >= sequence) {
                sequence = sequenceStore.bumpToAtLeast(self.identityHash, remoteSequence + 1)
                AppLogger.info(
                    "Discovery",
                    "retry publish with seq=$sequence (remote had $remoteSequence)",
                )
                result = publishAtSequence(self, endpoints, ed25519PrivateKey, sequence)
            }
        }
        return result
    }

    private suspend fun publishAtSequence(
        self: DiscoveryIdentity,
        endpoints: List<Endpoint>,
        ed25519PrivateKey: ByteArray,
        sequence: Long,
    ): AppResult<Unit> {
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
