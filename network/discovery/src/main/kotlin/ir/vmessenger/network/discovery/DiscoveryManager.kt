package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppError
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
        return lastError ?: AppResult.Error(AppError.Network("اعلام endpoint ناموفق بود"))
    }

    /**
     * Merges the endpoints of every provider that answered. "Not found" (a
     * provider answered with nothing) is `Success(empty)`; if every provider
     * failed the last failure is returned so callers can tell an unreachable
     * network from a peer that simply has no record. With no providers at all
     * there is nothing to fail, so the result is an empty success.
     */
    suspend fun resolve(identityHash: ByteArray): AppResult<List<Endpoint>> {
        val merged = mutableListOf<Endpoint>()
        var anySucceeded = false
        var lastError: AppResult.Error? = null
        for (provider in providers) {
            when (val result = provider.resolve(identityHash)) {
                is AppResult.Success -> {
                    anySucceeded = true
                    merged.addAll(result.data)
                }
                is AppResult.Error -> lastError = result
            }
        }
        if (!anySucceeded && lastError != null) return lastError
        return AppResult.Success(merged.distinctBy { it.transport to it.address })
    }
}
