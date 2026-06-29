package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.RelayNodeDao
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import ir.vmessenger.core.database.entity.DEFAULT_NODE_PRIORITY
import ir.vmessenger.core.database.entity.RelayNodeEntity
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.network.NodeLinkCodec
import ir.vmessenger.domain.repository.NodeManagementRepository
import ir.vmessenger.network.bootstrap.BootstrapNode
import ir.vmessenger.network.bootstrap.BootstrapProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the bootstrap/DHT and relay nodes the app knows
 * about. Replaces the single hardcoded relay/bootstrap dependency with a
 * health-ranked, user-extensible list (docs/P2P-Phases.md Phase 1 & 2).
 *
 * Records are never trusted with plaintext; this layer only manages *where* to
 * reach the network. Endpoint records returned by any node are still
 * signature-verified elsewhere.
 */
@Singleton
class NetworkNodeRepository @Inject constructor(
    private val bootstrapNodeDao: BootstrapNodeDao,
    private val relayNodeDao: RelayNodeDao,
) : NodeManagementRepository {
    /** Ensures the built-in defaults exist so there is always a working fallback. */
    suspend fun seedDefaults() {
        seedBootstrapNode(NetworkConfig.DEFAULT_DHT_URL, SOURCE_BUILT_IN, priority = DEFAULT_NODE_PRIORITY)
        seedRelayNode(NetworkConfig.DEFAULT_RELAY_URL, SOURCE_BUILT_IN, priority = DEFAULT_NODE_PRIORITY)
    }

    suspend fun enabledBootstrapNodes(): List<BootstrapNode> =
        bootstrapNodeDao.getEnabledOrdered().map { entity ->
            BootstrapNode(
                address = entity.address,
                publicKey = entity.publicKey,
                source = BootstrapProviderId(entity.source),
            )
        }

    suspend fun enabledRelayUrls(): List<String> =
        relayNodeDao.getEnabledOrdered().map { it.address }

    suspend fun recordBootstrapResult(address: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) bootstrapNodeDao.markOk(address, now) else bootstrapNodeDao.markFail(address, now)
    }

    suspend fun recordRelayResult(address: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) relayNodeDao.markOk(address, now) else relayNodeDao.markFail(address, now)
    }

    /** Phase 3: persist DHT nodes learned from the network for faster rejoin. */
    suspend fun importLearnedBootstrapAddresses(addresses: Set<String>, source: String) {
        addresses.forEach { address ->
            if (isValidBootstrapAddress(address)) {
                seedBootstrapNode(address, source, priority = CACHED_NODE_PRIORITY)
            }
        }
    }

    /** Phase 4: ingest network-node hints from a connected peer. */
    suspend fun importExchangedNodes(
        bootstrapAddresses: List<String>,
        relayAddresses: List<String>,
    ) {
        importLearnedBootstrapAddresses(
            bootstrapAddresses.take(MAX_EXCHANGE).toSet(),
            SOURCE_PEER_EXCHANGE,
        )
        relayAddresses.take(MAX_EXCHANGE).forEach { address ->
            if (address.startsWith("ws://") || address.startsWith("wss://")) {
                seedRelayNode(address, SOURCE_PEER_EXCHANGE, priority = CACHED_NODE_PRIORITY)
            }
        }
    }

    suspend fun importSignedNodeRecords(
        records: List<ir.vmessenger.core.proto.app.v1.SignedNodeRecord>,
        verifier: SignedNodeRecordVerifier,
    ) {
        val now = System.currentTimeMillis()
        for (record in records.take(MAX_EXCHANGE)) {
            if (!verifier.verify(record, now)) continue
            when (record.role) {
                ir.vmessenger.core.proto.app.v1.NodeRole.NODE_ROLE_BOOTSTRAP ->
                    seedBootstrapNode(record.address, SOURCE_PEER_EXCHANGE, priority = CACHED_NODE_PRIORITY)
                ir.vmessenger.core.proto.app.v1.NodeRole.NODE_ROLE_RELAY ->
                    seedRelayNode(record.address, SOURCE_PEER_EXCHANGE, priority = CACHED_NODE_PRIORITY)
                else -> Unit
            }
        }
    }

    suspend fun promoteNodeOnSuccess(address: String) {
        val now = System.currentTimeMillis()
        bootstrapNodeDao.markOk(address, now)
        relayNodeDao.markOk(address, now)
    }

    suspend fun healthyNodesForExchange(max: Int = MAX_EXCHANGE): Pair<List<String>, List<String>> {
        val bootstrap = bootstrapNodeDao.getEnabledOrdered()
            .filter { it.failCount < MAX_FAIL_FOR_EXCHANGE }
            .take(max)
            .map { it.address }
        val relay = relayNodeDao.getEnabledOrdered()
            .filter { it.failCount < MAX_FAIL_FOR_EXCHANGE }
            .take(max)
            .map { it.address }
        return bootstrap to relay
    }

    suspend fun addBootstrapNode(address: String, source: String = SOURCE_USER) {
        seedBootstrapNode(address, source, priority = USER_NODE_PRIORITY)
        AppLogger.info("Nodes", "added bootstrap node $address")
    }

    suspend fun addRelayNode(address: String, source: String = SOURCE_USER) {
        seedRelayNode(address, source, priority = USER_NODE_PRIORITY)
        AppLogger.info("Nodes", "added relay node $address")
    }

    suspend fun setBootstrapEnabled(address: String, enabled: Boolean) =
        bootstrapNodeDao.setEnabled(address, enabled)

    suspend fun setRelayEnabled(address: String, enabled: Boolean) =
        relayNodeDao.setEnabled(address, enabled)

    suspend fun removeBootstrapNode(address: String) = bootstrapNodeDao.deleteByAddress(address)

    suspend fun removeRelayNode(address: String) = relayNodeDao.deleteByAddress(address)

    // --- NodeManagementRepository (user-facing) ---

    override fun observeNodes(): Flow<List<NetworkNode>> =
        combine(bootstrapNodeDao.observeAll(), relayNodeDao.observeAll()) { bootstrap, relays ->
            bootstrap.map { it.toNetworkNode(NetworkNodeRole.BOOTSTRAP) } +
                relays.map { it.toNetworkNode(NetworkNodeRole.RELAY) }
        }

    override suspend fun addNode(
        input: String,
        fallbackRole: NetworkNodeRole,
    ): AppResult<NetworkNode> {
        val link = NodeLinkCodec.decode(input)
        val role = link?.role ?: fallbackRole
        val address = (link?.address ?: input).trim()
        val validationError = validateAddress(address, role)
        if (validationError != null) return AppResult.Error(AppError.Validation(validationError))
        when (role) {
            NetworkNodeRole.BOOTSTRAP -> addBootstrapNode(address)
            NetworkNodeRole.RELAY -> addRelayNode(address)
        }
        val node = NetworkNode(
            address = address,
            role = role,
            source = NetworkNode.SOURCE_USER,
            enabled = true,
            lastOkUnixMs = null,
            lastFailUnixMs = null,
            failCount = 0,
        )
        return AppResult.Success(node)
    }

    override suspend fun setEnabled(address: String, role: NetworkNodeRole, enabled: Boolean) {
        when (role) {
            NetworkNodeRole.BOOTSTRAP -> setBootstrapEnabled(address, enabled)
            NetworkNodeRole.RELAY -> setRelayEnabled(address, enabled)
        }
    }

    override suspend fun removeNode(address: String, role: NetworkNodeRole): AppResult<Unit> {
        val source = when (role) {
            NetworkNodeRole.BOOTSTRAP -> bootstrapNodeDao.getByAddress(address)?.source
            NetworkNodeRole.RELAY -> relayNodeDao.getByAddress(address)?.source
        }
        if (source == SOURCE_BUILT_IN) {
            return AppResult.Error(
                AppError.Validation("نود پیش‌فرض قابل حذف نیست؛ می‌توانید آن را غیرفعال کنید"),
            )
        }
        when (role) {
            NetworkNodeRole.BOOTSTRAP -> removeBootstrapNode(address)
            NetworkNodeRole.RELAY -> removeRelayNode(address)
        }
        return AppResult.Success(Unit)
    }

    override fun exportLink(node: NetworkNode): String =
        NodeLinkCodec.encode(node.role, node.address)

    private fun validateAddress(address: String, role: NetworkNodeRole): String? = when {
        address.isBlank() -> "آدرس نود خالی است"
        role == NetworkNodeRole.RELAY && !address.startsWith("ws://") && !address.startsWith("wss://") ->
            "آدرس رله باید با ws:// یا wss:// شروع شود"
        role == NetworkNodeRole.BOOTSTRAP && !isValidBootstrapAddress(address) ->
            "آدرس بوت‌استرپ باید ws://، wss:// یا host:port باشد"
        else -> null
    }

    private fun isValidBootstrapAddress(address: String): Boolean =
        address.startsWith("ws://") || address.startsWith("wss://") || hostPortPattern.matches(address)

    private fun BootstrapNodeEntity.toNetworkNode(role: NetworkNodeRole) = NetworkNode(
        address = address,
        role = role,
        source = source,
        enabled = enabled,
        lastOkUnixMs = lastOkUnixMs,
        lastFailUnixMs = lastFailUnixMs,
        failCount = failCount,
    )

    private fun RelayNodeEntity.toNetworkNode(role: NetworkNodeRole) = NetworkNode(
        address = address,
        role = role,
        source = source,
        enabled = enabled,
        lastOkUnixMs = lastOkUnixMs,
        lastFailUnixMs = lastFailUnixMs,
        failCount = failCount,
    )

    private suspend fun seedBootstrapNode(address: String, source: String, priority: Int) {
        if (bootstrapNodeDao.getByAddress(address) != null) return
        bootstrapNodeDao.upsert(
            BootstrapNodeEntity(
                address = address,
                publicKey = null,
                source = source,
                enabled = true,
                lastOkUnixMs = null,
                priority = priority,
            ),
        )
    }

    private suspend fun seedRelayNode(address: String, source: String, priority: Int) {
        if (relayNodeDao.getByAddress(address) != null) return
        relayNodeDao.upsert(
            RelayNodeEntity(
                address = address,
                publicKey = null,
                source = source,
                enabled = true,
                lastOkUnixMs = null,
                priority = priority,
            ),
        )
    }

    companion object {
        const val SOURCE_BUILT_IN = "BUILT_IN"
        const val SOURCE_USER = "USER"
        const val SOURCE_PEER_EXCHANGE = "PEER_EXCHANGE"
        const val SOURCE_CACHED_DHT = "CACHED_DHT"
        private const val USER_NODE_PRIORITY = 150
        private const val CACHED_NODE_PRIORITY = 120
        private const val MAX_EXCHANGE = 20
        private const val MAX_FAIL_FOR_EXCHANGE = 5
        private val hostPortPattern = Regex("""^[A-Za-z0-9.\-]+:\d{1,5}$""")
    }
}
