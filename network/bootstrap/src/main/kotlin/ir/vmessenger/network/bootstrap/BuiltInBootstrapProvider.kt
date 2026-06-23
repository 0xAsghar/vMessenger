package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltInBootstrapProvider @Inject constructor() : BootstrapProvider {
    override val id = BootstrapProviderId("built_in")
    override val priority = 100

    override suspend fun nodes(): AppResult<List<BootstrapNode>> = AppResult.Success(
        listOf(
            BootstrapNode(
                address = DEFAULT_ADDRESS,
                source = id,
            ),
        ),
    )

    companion object {
        const val DEFAULT_ADDRESS = "10.0.2.2:46555"
        const val DEFAULT_PORT = 46555
    }
}
