package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryManager @Inject constructor(
    providers: Set<@JvmSuppressWildcards DiscoveryProvider>,
) {
    private val providers = providers.toList()

    suspend fun announce(
        self: DiscoveryIdentity,
        endpoints: List<Endpoint>,
        ed25519PrivateKey: ByteArray,
    ): AppResult<Unit> {
        var lastError: AppResult.Error? = null
        for (provider in providers) {
            when (val result = provider.announce(self, endpoints, ed25519PrivateKey)) {
                is AppResult.Success -> return result
                is AppResult.Error -> lastError = result
            }
        }
        return lastError ?: AppResult.Error(ir.vmessenger.core.common.AppError.Network("اعلام endpoint ناموفق بود"))
    }

    suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>> {
        val merged = mutableListOf<Endpoint>()
        for (provider in providers) {
            when (val result = provider.resolve(identityHash)) {
                is AppResult.Success -> merged.addAll(result.data)
                is AppResult.Error -> Unit
            }
        }
        return AppResult.Success(merged.distinctBy { it.transport to it.address })
    }
}
