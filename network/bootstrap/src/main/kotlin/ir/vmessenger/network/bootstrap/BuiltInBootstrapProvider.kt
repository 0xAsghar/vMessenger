package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltInBootstrapProvider @Inject constructor() : BootstrapProvider {
    override val id = BootstrapProviderId("built_in")
    override val priority = 100

    /**
     * Nothing in multi-node mode: the database provider already offers the built-in node as a row the
     * person can switch off, and adding it here as well made switching it off meaningless — every join
     * still reached it. The developer bootstrap override, and the legacy single-node mode, still use it.
     */
    override suspend fun nodes(): AppResult<List<BootstrapNode>> = AppResult.Success(
        if (P2PConfig.multiNodeEnabled && !NetworkConfig.useDevBootstrap) {
            emptyList()
        } else {
            listOf(BootstrapNode(address = NetworkConfig.effectiveBootstrapAddress(), source = id))
        },
    )

    companion object {
        const val DEFAULT_ADDRESS = NetworkConfig.DEFAULT_DHT_URL
        const val DEFAULT_PORT = 46555
        const val DEV_ADDRESS = NetworkConfig.DEV_BOOTSTRAP_ADDRESS
    }
}
