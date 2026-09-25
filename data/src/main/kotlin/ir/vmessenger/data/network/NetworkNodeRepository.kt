package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.common.network.NodeRankKey
import ir.vmessenger.core.common.network.NodeRanking
import ir.vmessenger.core.common.network.NodeTrust
import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.RelayNodeDao
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import ir.vmessenger.core.database.entity.RelayNodeEntity
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import ir.vmessenger.data.activity.ActivityLogger
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
 * health-ranked, user-extensible list.
 *
 * Trust rules (Milestone 3d):
 * - built-in and user-added nodes are enabled; nodes learned from peers or the
 *   DHT are stored as [NodeTrust.COMMUNITY], **disabled**, priority 80, until the
 *   user enables them in the Nodes screen;
 * - a `SignedNodeRecord` signed by the operator key is [NodeTrust.OFFICIAL] and
 *   enabled with priority 100;
 * - every stored address passes [NodeAddressPolicy] (release: `wss://` only);
 * - ordering is [NodeRanking] over the unordered DAO result, so the built-in
 *   relay is displaced only by a user relay or after three consecutive failures.
 *
 * Records are never trusted with plaintext; this layer only manages *where* to
 * reach the network. Endpoint records returned by any node are still
 * signature-verified elsewhere.
 */
@Singleton
@Suppress("TooManyFunctions") // node CRUD + import + ranking surface behind one repository
class NetworkNodeRepository(
    private val bootstrapNodeDao: BootstrapNodeDao,
    private val relayNodeDao: RelayNodeDao,
    private val activityLogger: ActivityLogger,
    private val addressPolicy: () -> NodeAddressPolicy,
) : NodeManagementRepository {
    @Inject
    constructor(
        bootstrapNodeDao: BootstrapNodeDao,
        relayNodeDao: RelayNodeDao,
        activityLogger: ActivityLogger,
    ) : this(bootstrapNodeDao, relayNodeDao, activityLogger, { NodeAddressPolicy.current })

    /** Ensures the built-in defaults exist so there is always a working fallback. */
    suspend fun seedDefaults() {
        seedBootstrapNode(NetworkConfig.DEFAULT_DHT_URL, SOURCE_BUILT_IN, NodeTrust.BUILT_IN)
        seedRelayNode(NetworkConfig.DEFAULT_RELAY_URL, SOURCE_BUILT_IN, NodeTrust.BUILT_IN)
    }

    suspend fun enabledBootstrapNodes(): List<BootstrapNode> =
        rankedEnabledBootstrap().map { entity ->
            BootstrapNode(
                address = entity.address,
                publicKey = entity.publicKey,
                source = BootstrapProviderId(entity.source),
            )
        }

    /** Enabled relay URLs, best candidate first (see [NodeRanking]). */
    suspend fun enabledRelayUrls(): List<String> = rankedEnabledRelays().map { it.address }

    suspend fun recordBootstrapResult(address: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) bootstrapNodeDao.markOk(address, now) else bootstrapNodeDao.markFail(address, now)
    }

    suspend fun recordRelayResult(address: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        if (ok) relayNodeDao.markOk(address, now) else relayNodeDao.markFail(address, now)
    }

    /**
     * Phase 3: persist DHT nodes learned from the network for faster rejoin. Always
     * stored as community nodes (disabled) after passing the address policy.
     */
    suspend fun importLearnedBootstrapAddresses(
        addresses: Set<String>,
        source: String,
        learnedFromHash: ByteArray? = null,
    ) {
        addresses.forEach { address ->
            if (addressPolicy().isBootstrapAllowed(address)) {
                seedBootstrapNode(address, source, NodeTrust.COMMUNITY, learnedFromHash)
            }
        }
    }

    /** Phase 4: ingest network-node hints from a connected peer (community, disabled). */
    suspend fun importExchangedNodes(
        bootstrapAddresses: List<String>,
        relayAddresses: List<String>,
        learnedFromHash: ByteArray? = null,
    ) {
        importLearnedBootstrapAddresses(
            bootstrapAddresses.take(MAX_EXCHANGE).toSet(),
            SOURCE_PEER_EXCHANGE,
            learnedFromHash,
        )
        relayAddresses.take(MAX_EXCHANGE).forEach { address ->
            if (addressPolicy().isRelayAllowed(address)) {
                seedRelayNode(address, SOURCE_PEER_EXCHANGE, NodeTrust.COMMUNITY, learnedFromHash)
            }
        }
    }

    /**
     * Imports signed records: operator-signed ones become [NodeTrust.OFFICIAL] and
     * enabled; every other valid record is a disabled community node.
     */
    suspend fun importSignedNodeRecords(
        records: List<SignedNodeRecord>,
        verifier: SignedNodeRecordVerifier,
        learnedFromHash: ByteArray? = null,
    ) {
        val now = System.currentTimeMillis()
        for (record in records.take(MAX_EXCHANGE)) {
            val trust = verifier.verify(record, now) ?: continue
            val policy = addressPolicy()
            when (record.role) {
                NodeRole.NODE_ROLE_BOOTSTRAP -> if (policy.isBootstrapAllowed(record.address)) {
                    seedBootstrapNode(record.address, SOURCE_PEER_EXCHANGE, trust, learnedFromHash)
                }
                NodeRole.NODE_ROLE_RELAY -> if (policy.isRelayAllowed(record.address)) {
                    seedRelayNode(record.address, SOURCE_PEER_EXCHANGE, trust, learnedFromHash)
                }
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
        val bootstrap = rankedEnabledBootstrap()
            .filter { it.failCount < MAX_FAIL_FOR_EXCHANGE }
            .take(max)
            .map { it.address }
        val relay = rankedEnabledRelays()
            .filter { it.failCount < MAX_FAIL_FOR_EXCHANGE }
            .take(max)
            .map { it.address }
        return bootstrap to relay
    }

    suspend fun addBootstrapNode(address: String, source: String = SOURCE_USER) {
        seedBootstrapNode(address, source, NodeTrust.USER)
        AppLogger.info("Nodes", "added bootstrap node $address")
    }

    suspend fun addRelayNode(address: String, source: String = SOURCE_USER) {
        seedRelayNode(address, source, NodeTrust.USER)
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
        val rejection = when (role) {
            NetworkNodeRole.RELAY -> addressPolicy().checkRelay(address)
            NetworkNodeRole.BOOTSTRAP -> addressPolicy().checkBootstrap(address)
        }
        if (rejection != null) {
            return AppResult.Error(AppError.NodeAddressRejected(rejection, relay = role == NetworkNodeRole.RELAY))
        }
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
            trust = NodeTrust.USER,
        )
        // The address, not the link: a vmnode: link can carry more than the address, and only the
        // address is needed to answer "which node did I add, and when".
        activityLogger.record(ActivityKind.NodeAdded, address)
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
            return AppResult.Error(AppError.BuiltInNodeRemoval)
        }
        when (role) {
            NetworkNodeRole.BOOTSTRAP -> removeBootstrapNode(address)
            NetworkNodeRole.RELAY -> removeRelayNode(address)
        }
        activityLogger.record(ActivityKind.NodeRemoved, address)
        return AppResult.Success(Unit)
    }

    override fun exportLink(node: NetworkNode): String =
        NodeLinkCodec.encode(node.role, node.address)

    // A row stored under an older policy may not pass today's (a URL fragment that is not a pin
    // spec): it can never be dialled, so it is never chosen, and the next row — or the default —
    // is used instead of retrying it forever.
    private suspend fun rankedEnabledBootstrap(): List<BootstrapNodeEntity> =
        NodeRanking.rank(bootstrapNodeDao.getEnabled().filter { addressPolicy().isBootstrapAllowed(it.address) }) {
            it.rankKey()
        }

    private suspend fun rankedEnabledRelays(): List<RelayNodeEntity> =
        NodeRanking.rank(relayNodeDao.getEnabled().filter { addressPolicy().isRelayAllowed(it.address) }) {
            it.rankKey()
        }

    private fun BootstrapNodeEntity.rankKey() = NodeRankKey(priority, failCount, lastOkUnixMs)

    private fun RelayNodeEntity.rankKey() = NodeRankKey(priority, failCount, lastOkUnixMs)

    private fun BootstrapNodeEntity.toNetworkNode(role: NetworkNodeRole) = NetworkNode(
        address = address,
        role = role,
        source = source,
        enabled = enabled,
        lastOkUnixMs = lastOkUnixMs,
        lastFailUnixMs = lastFailUnixMs,
        failCount = failCount,
        trust = NodeTrust.fromName(trust),
    )

    private fun RelayNodeEntity.toNetworkNode(role: NetworkNodeRole) = NetworkNode(
        address = address,
        role = role,
        source = source,
        enabled = enabled,
        lastOkUnixMs = lastOkUnixMs,
        lastFailUnixMs = lastFailUnixMs,
        failCount = failCount,
        trust = NodeTrust.fromName(trust),
    )

    /**
     * Inserts a new row, or upgrades an existing community row when the operator
     * (OFFICIAL) or the user (USER) vouches for it; anything else already present
     * is left untouched (a peer can never re-enable or re-rank a node the user
     * turned off). Community inserts are capped per table so a peer cannot grow
     * the node list without bound.
     */
    private suspend fun seedBootstrapNode(
        address: String,
        source: String,
        trust: NodeTrust,
        learnedFromHash: ByteArray? = null,
    ) {
        val existing = bootstrapNodeDao.getByAddress(address)
        if (existing != null && !upgrades(existing.trust, trust)) return
        if (existing == null && communityTableFull(trust, bootstrapNodeDao.getAll().count { it.isCommunity() })) return
        bootstrapNodeDao.upsert(
            BootstrapNodeEntity(
                address = address,
                publicKey = existing?.publicKey,
                source = sourceFor(existing?.source, source, trust),
                enabled = NodeRanking.autoEnabled(trust) || existing?.enabled == true,
                lastOkUnixMs = existing?.lastOkUnixMs,
                priority = NodeRanking.defaultPriority(trust),
                lastFailUnixMs = existing?.lastFailUnixMs,
                failCount = existing?.failCount ?: 0,
                trust = trust.name,
                learnedFromHash = learnedFromHash ?: existing?.learnedFromHash,
            ),
        )
    }

    private suspend fun seedRelayNode(
        address: String,
        source: String,
        trust: NodeTrust,
        learnedFromHash: ByteArray? = null,
    ) {
        val existing = relayNodeDao.getByAddress(address)
        if (existing != null && !upgrades(existing.trust, trust)) return
        if (existing == null && communityTableFull(trust, relayNodeDao.getAll().count { it.isCommunity() })) return
        relayNodeDao.upsert(
            RelayNodeEntity(
                address = address,
                publicKey = existing?.publicKey,
                source = sourceFor(existing?.source, source, trust),
                enabled = NodeRanking.autoEnabled(trust) || existing?.enabled == true,
                lastOkUnixMs = existing?.lastOkUnixMs,
                priority = NodeRanking.defaultPriority(trust),
                lastFailUnixMs = existing?.lastFailUnixMs,
                failCount = existing?.failCount ?: 0,
                trust = trust.name,
                learnedFromHash = learnedFromHash ?: existing?.learnedFromHash,
            ),
        )
    }

    /**
     * Only a community row can be upgraded, and only by an explicit user add or an
     * operator signature; BUILT_IN/USER/OFFICIAL rows never change trust here.
     */
    private fun upgrades(existingTrust: String, incoming: NodeTrust): Boolean =
        NodeTrust.fromName(existingTrust) == NodeTrust.COMMUNITY &&
            (incoming == NodeTrust.OFFICIAL || incoming == NodeTrust.USER)

    /** A user add re-attributes the row; an operator promotion keeps where we learned it. */
    private fun sourceFor(existingSource: String?, source: String, trust: NodeTrust): String =
        if (existingSource == null || trust == NodeTrust.USER) source else existingSource

    private fun communityTableFull(trust: NodeTrust, communityRows: Int): Boolean {
        val full = trust == NodeTrust.COMMUNITY && communityRows >= MAX_COMMUNITY_ROWS
        if (full) AppLogger.debug("Nodes", "community node table full ($communityRows); ignoring new hint")
        return full
    }

    private fun BootstrapNodeEntity.isCommunity() = NodeTrust.fromName(trust) == NodeTrust.COMMUNITY

    private fun RelayNodeEntity.isCommunity() = NodeTrust.fromName(trust) == NodeTrust.COMMUNITY

    companion object {
        const val SOURCE_BUILT_IN = "BUILT_IN"
        const val SOURCE_USER = "USER"
        const val SOURCE_PEER_EXCHANGE = "PEER_EXCHANGE"
        const val SOURCE_CACHED_DHT = "CACHED_DHT"
        private const val MAX_EXCHANGE = 20
        private const val MAX_FAIL_FOR_EXCHANGE = 5

        /** Upper bound on peer/DHT-learned (community) rows per table, whatever the number of peers. */
        const val MAX_COMMUNITY_ROWS = 50
    }
}
