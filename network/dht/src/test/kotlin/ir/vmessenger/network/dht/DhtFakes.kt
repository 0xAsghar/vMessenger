package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.dao.DhtRecordDao
import ir.vmessenger.core.database.entity.DhtRecordEntity
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse

/** In-memory [DhtRecordDao] for JVM tests. */
class FakeDhtRecordDao : DhtRecordDao {
    val records = linkedMapOf<String, DhtRecordEntity>()

    override suspend fun active(nowMs: Long): List<DhtRecordEntity> =
        records.values.filter { it.expiresAtUnixMs > nowMs }.sortedByDescending { it.sequence }

    override suspend fun count(): Int = records.size

    override suspend fun upsert(entity: DhtRecordEntity) {
        records[entity.recordKey] = entity
    }

    override suspend fun purgeExpired(nowMs: Long) {
        records.values.removeAll { it.expiresAtUnixMs <= nowMs }
    }

    override suspend fun evictOldest(excess: Int) {
        records.values.sortedBy { it.storedAtUnixMs }.take(excess).forEach { records.remove(it.recordKey) }
    }
}

/**
 * Scripted [DhtRpcSender]: answers every request with ping/find-value results
 * built from [nodes]; requests to an address in [failing] throw like an
 * unreachable (or policy-rejected) node would.
 */
class FakeDhtRpcSender(
    @Volatile var nodes: List<String> = emptyList(),
    private val storeAccepted: Boolean = true,
    failing: Set<String> = emptySet(),
) : DhtRpcSender {
    val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DhtRpcRequest>>()
    val failing: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet(failing)

    override suspend fun send(address: String, request: DhtRpcRequest): DhtRpcResponse {
        sent += address to request
        check(address !in failing) { "unreachable $address" }
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder().setNodeId(ByteString.copyFrom(ByteArray(32))),
            )
            request.hasStore() -> builder.setStore(StoreResponse.newBuilder().setAccepted(storeAccepted))
            request.hasFindValue() -> builder.setFindValue(
                FindValueResponse.newBuilder()
                    .setFound(false)
                    .addAllNodes(
                        nodes.map { DhtNodeInfo.newBuilder().setAddress(it).setNodeId(ByteString.EMPTY).build() },
                    ),
            )
        }
        return builder.build()
    }
}
