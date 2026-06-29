package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.bootstrap.BootstrapProvider
import ir.vmessenger.network.bootstrap.BootstrapProviderId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contributes the user/community/cached bootstrap nodes stored in the database,
 * ordered healthiest-first. Higher priority than the built-in provider so that
 * the broader node list is preferred while the built-in default remains as a
 * guaranteed fallback entry (docs/P2P-Phases.md Phase 1).
 */
@Singleton
class DatabaseBootstrapProvider @Inject constructor(
    private val nodeRepository: NetworkNodeRepository,
) : BootstrapProvider {
    override val id = BootstrapProviderId("database")
    override val priority = 200

    override suspend fun nodes(): AppResult<List<BootstrapNode>> {
        if (!P2PConfig.multiNodeEnabled) return AppResult.Success(emptyList())
        return AppResult.Success(nodeRepository.enabledBootstrapNodes())
    }
}
