package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltInBootstrapProvider @Inject constructor() : BootstrapProvider {
    override val id = BootstrapProviderId("built_in")
    override val priority = 100

    override suspend fun nodes(): AppResult<List<BootstrapNode>> = AppResult.Success(
        listOf(
            BootstrapNode(
                address = NetworkConfig.effectiveBootstrapAddress(),
                source = id,
            ),
        ),
    )

    companion object {
        const val DEFAULT_ADDRESS = NetworkConfig.DEFAULT_DHT_URL
        const val DEFAULT_PORT = 46555
        const val DEV_ADDRESS = NetworkConfig.DEV_BOOTSTRAP_ADDRESS
    }
}
