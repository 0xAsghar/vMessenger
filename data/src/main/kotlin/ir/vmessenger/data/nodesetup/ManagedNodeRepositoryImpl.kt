package ir.vmessenger.data.nodesetup

import ir.vmessenger.core.common.network.NodeUrl
import ir.vmessenger.core.database.dao.ManagedNodeDao
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.ManagedNodeEntity
import ir.vmessenger.data.activity.ActivityLogger
import ir.vmessenger.domain.model.ManagedNode
import ir.vmessenger.domain.model.ManagedNodeStatus
import ir.vmessenger.domain.model.ManagedNodeTls
import ir.vmessenger.domain.repository.ManagedNodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManagedNodeRepositoryImpl @Inject constructor(
    private val dao: ManagedNodeDao,
    private val activityLogger: ActivityLogger,
) : ManagedNodeRepository {

    override fun observe(): Flow<List<ManagedNode>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: String): ManagedNode? = dao.getById(id)?.toDomain()

    override suspend fun getByHost(host: String, sshPort: Int): ManagedNode? = dao.getByHost(host, sshPort)?.toDomain()

    override suspend fun save(node: ManagedNode) {
        val before = dao.getById(node.id)
        dao.upsert(node.toEntity())
        // The node's address as people read it, without the pin; never the server's login.
        val detail = NodeUrl.parse(node.relayUrl)?.displayText ?: node.publicHost
        when {
            node.status != ManagedNodeStatus.READY -> Unit
            before == null || before.status != ManagedNodeStatus.READY.name ->
                activityLogger.record(ActivityKind.NodeProvisioned, detail)
            before.nodeVersion != node.nodeVersion -> activityLogger.record(ActivityKind.NodeUpdated, detail)
        }
    }

    override suspend fun forget(id: String) = dao.delete(id)

    override suspend fun markChecked(id: String, ok: Boolean) = dao.markChecked(id, System.currentTimeMillis(), ok)
}

internal fun ManagedNodeEntity.toDomain() = ManagedNode(
    id = id, host = host, sshPort = sshPort, sshUser = sshUser,
    hostKeyAlgorithm = hostKeyAlgorithm, hostKeyFingerprint = hostKeyFingerprint,
    publicHost = publicHost, publicPort = publicPort,
    tls = ManagedNodeTls.entries.firstOrNull { it.name == tlsMode } ?: ManagedNodeTls.IP_PINNED,
    domain = domain, relayUrl = relayUrl, bootstrapUrl = bootstrapUrl, nodeVersion = nodeVersion, nodeId = nodeId,
    secured = secured, keyOnlyLogin = keyOnlyLogin,
    status = ManagedNodeStatus.entries.firstOrNull { it.name == status } ?: ManagedNodeStatus.INTERRUPTED,
    lastRunId = lastRunId, createdAtUnixMs = createdAtUnixMs, updatedAtUnixMs = updatedAtUnixMs,
    lastCheckedUnixMs = lastCheckedUnixMs, lastCheckOk = lastCheckOk,
)

internal fun ManagedNode.toEntity() = ManagedNodeEntity(
    id = id, host = host, sshPort = sshPort, sshUser = sshUser,
    hostKeyAlgorithm = hostKeyAlgorithm, hostKeyFingerprint = hostKeyFingerprint,
    publicHost = publicHost, publicPort = publicPort, tlsMode = tls.name, domain = domain,
    relayUrl = relayUrl, bootstrapUrl = bootstrapUrl, nodeVersion = nodeVersion, nodeId = nodeId,
    secured = secured, keyOnlyLogin = keyOnlyLogin, status = status.name, lastRunId = lastRunId,
    createdAtUnixMs = createdAtUnixMs, updatedAtUnixMs = updatedAtUnixMs,
    lastCheckedUnixMs = lastCheckedUnixMs, lastCheckOk = lastCheckOk,
)
