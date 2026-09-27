package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The developer bootstrap (`10.0.2.2:46555`, a `:node:run --args="--tcp"` on the machine running the
 * emulator) while a debug build asks for it, and nothing otherwise. Every other bootstrap node comes
 * from the database ([BootstrapProvider] in `data`): the app ships none of its own since 2.2.2.
 */
@Singleton
class DevBootstrapProvider @Inject constructor() : BootstrapProvider {
    override val id = BootstrapProviderId("dev")
    override val priority = 100

    override suspend fun nodes(): AppResult<List<BootstrapNode>> = AppResult.Success(
        listOfNotNull(NetworkConfig.devBootstrapAddress()?.let { BootstrapNode(address = it, source = id) }),
    )
}
