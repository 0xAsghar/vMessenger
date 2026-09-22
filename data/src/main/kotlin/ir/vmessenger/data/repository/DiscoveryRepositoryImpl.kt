package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.datastore.NodeSetupChoice
import ir.vmessenger.core.datastore.NodeSetupPreferences
import ir.vmessenger.data.network.NetworkNodeRepository
import ir.vmessenger.domain.model.DiscoveryStatus
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.bootstrap.BootstrapManager
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.discovery.DhtDiscoveryProvider
import ir.vmessenger.network.discovery.DiscoveryIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Joins the DHT and publishes this device's endpoints. Peer resolution lives in
 * `EndpointResolveService` (cache-first, verified, routing-hash keyed); the old
 * unverified `resolveEndpoints` path keyed on the raw hash was removed.
 */
@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    private val bootstrapManager: BootstrapManager,
    private val dht: Dht,
    private val dhtDiscoveryProvider: DhtDiscoveryProvider,
    private val identityRepository: IdentityRepository,
    private val networkNodeRepository: NetworkNodeRepository,
    private val nodeSetupPreferences: NodeSetupPreferences,
) : DiscoveryRepository {
    private val _status = MutableStateFlow(
        DiscoveryStatus(bootstrapped = false, knownNodes = 0, publishedEndpoint = null, lastError = null),
    )

    override fun observeStatus(): Flow<DiscoveryStatus> = _status.asStateFlow()

    override suspend fun joinNetwork(): AppResult<Unit> {
        // Only for a user who did not decline them. Seeding unconditionally is what made "skip"
        // meaningless: the built-in nodes came back on the next join, so the choice was cosmetic.
        // An install that predates the question reads NotAsked and still gets them, so upgrading
        // changes nothing.
        if (nodeSetupPreferences.current() != NodeSetupChoice.Skipped) {
            networkNodeRepository.seedDefaults()
        }
        val bootstrapAddress = NetworkConfig.effectiveBootstrapAddress()
        AppLogger.info("Discovery", "joinNetwork bootstrap=$bootstrapAddress")
        return when (val nodes = bootstrapManager.collectNodes()) {
            is AppResult.Success -> {
                when (val boot = dht.bootstrap(nodes.data)) {
                    is AppResult.Success -> {
                        recordBootstrapHealth(candidates = nodes.data, responders = boot.data)
                        networkNodeRepository.importLearnedBootstrapAddresses(
                            dht.knownNodeAddresses(),
                            NetworkNodeRepository.SOURCE_CACHED_DHT,
                        )
                        _status.value = _status.value.copy(
                            bootstrapped = true,
                            knownNodes = boot.data.size,
                            lastError = null,
                        )
                        AppLogger.info(
                            "Discovery",
                            "joinNetwork OK reachable=${boot.data.size}/${nodes.data.size}",
                        )
                        AppResult.Success(Unit)
                    }
                    is AppResult.Error -> {
                        recordBootstrapHealth(candidates = nodes.data, responders = emptyList())
                        AppLogger.error("Discovery", "joinNetwork bootstrap failed: ${boot.error.message}")
                        _status.value = _status.value.copy(lastError = boot.error.message)
                        boot
                    }
                }
            }
            is AppResult.Error -> {
                _status.value = _status.value.copy(lastError = nodes.error.message)
                nodes
            }
        }
    }

    private suspend fun recordBootstrapHealth(
        candidates: List<BootstrapNode>,
        responders: List<BootstrapNode>,
    ) {
        val reachable = responders.map { it.address }.toSet()
        candidates.forEach { node ->
            networkNodeRepository.recordBootstrapResult(node.address, ok = node.address in reachable)
        }
    }

    override suspend fun publishEndpoint(endpoint: Endpoint): AppResult<Unit> =
        publishEndpoints(listOf(endpoint))

    override suspend fun publishEndpoints(endpoints: List<Endpoint>): AppResult<Unit> {
        AppLogger.info("Discovery", "publish ${endpoints.joinToString { "${it.transport}:${it.address}" }}")
        val identity = identityRepository.getIdentity()
        val privateKey = identityRepository.getEd25519PrivateKey()
        val result = when {
            identity == null -> AppResult.Error(AppError.NotFound("هویت یافت نشد"))
            privateKey == null -> AppResult.Error(AppError.Crypto("کلید خصوصی در دسترس نیست"))
            endpoints.isEmpty() -> AppResult.Error(AppError.Validation("هیچ endpointی برای انتشار وجود ندارد"))
            else -> {
                val discoveryIdentity = DiscoveryIdentity(identity.identityHash, identity.ed25519PublicKey)
                dhtDiscoveryProvider.announce(discoveryIdentity, endpoints, privateKey)
            }
        }
        when (result) {
            is AppResult.Success -> {
                _status.value = _status.value.copy(
                    publishedEndpoint = endpoints.joinToString { "${it.transport.value}:${it.address}" },
                    lastError = null,
                )
                AppLogger.info("Discovery", "publish OK")
            }
            is AppResult.Error -> {
                AppLogger.error("Discovery", "publish failed: ${result.error.message}")
                _status.value = _status.value.copy(lastError = result.error.message)
            }
        }
        return result
    }

    override suspend fun getPublishedEndpoint(): String? = _status.value.publishedEndpoint
}
