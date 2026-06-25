package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.domain.model.DiscoveryStatus
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.bootstrap.BootstrapManager
import ir.vmessenger.network.dht.Dht
import ir.vmessenger.network.dht.toEndpoints
import ir.vmessenger.network.discovery.DhtDiscoveryProvider
import ir.vmessenger.network.discovery.DiscoveryIdentity
import ir.vmessenger.network.discovery.DiscoveryManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    private val bootstrapManager: BootstrapManager,
    private val dht: Dht,
    private val discoveryManager: DiscoveryManager,
    private val dhtDiscoveryProvider: DhtDiscoveryProvider,
    private val identityRepository: IdentityRepository,
    private val endpointCacheDao: EndpointCacheDao,
    private val bootstrapNodeDao: BootstrapNodeDao,
) : DiscoveryRepository {
    private val _status = MutableStateFlow(
        DiscoveryStatus(bootstrapped = false, knownNodes = 0, publishedEndpoint = null, lastError = null),
    )

    override fun observeStatus(): Flow<DiscoveryStatus> = _status.asStateFlow()

    override suspend fun joinNetwork(): AppResult<Unit> {
        seedBootstrapNodes()
        val bootstrapAddress = NetworkConfig.effectiveBootstrapAddress()
        AppLogger.info("Discovery", "joinNetwork bootstrap=$bootstrapAddress")
        return when (val nodes = bootstrapManager.collectNodes()) {
            is AppResult.Success -> {
                when (val boot = dht.bootstrap(nodes.data)) {
                    is AppResult.Success -> {
                        _status.value = _status.value.copy(
                            bootstrapped = true,
                            knownNodes = nodes.data.size,
                            lastError = null,
                        )
                        AppLogger.info("Discovery", "joinNetwork OK nodes=${nodes.data.size}")
                        AppResult.Success(Unit)
                    }
                    is AppResult.Error -> {
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

    override suspend fun resolveEndpoints(identityHash: ByteArray): AppResult<List<Endpoint>> {
        val cached = endpointCacheDao.get(identityHash)
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAtUnixMs > now) {
            val record = EndpointRecord.parseFrom(cached.endpointsProto)
            return AppResult.Success(record.toEndpoints())
        }
        return when (val result = discoveryManager.resolve(identityHash)) {
            is AppResult.Success -> result
            is AppResult.Error -> result
        }
    }

    override suspend fun getPublishedEndpoint(): String? = _status.value.publishedEndpoint

    private suspend fun seedBootstrapNodes() {
        bootstrapNodeDao.upsert(
            BootstrapNodeEntity(
                address = NetworkConfig.effectiveBootstrapAddress(),
                publicKey = null,
                source = "BUILT_IN",
                enabled = true,
                lastOkUnixMs = null,
            ),
        )
    }
}
