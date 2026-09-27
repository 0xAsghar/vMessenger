package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.bootstrap.BootstrapProvider
import ir.vmessenger.network.bootstrap.BootstrapProviderId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contributes the bootstrap nodes stored in the database that are switched on, healthiest first.
 * Since 2.2.2 these are the only ones outside a debug build's developer bootstrap: the app ships no
 * node of its own, so a phone with none switched on does not join the DHT.
 */
@Singleton
class DatabaseBootstrapProvider @Inject constructor(
    private val nodeRepository: NetworkNodeRepository,
) : BootstrapProvider {
    override val id = BootstrapProviderId("database")
    override val priority = 200

    override suspend fun nodes(): AppResult<List<BootstrapNode>> {
        return AppResult.Success(nodeRepository.enabledBootstrapNodes())
    }
}
