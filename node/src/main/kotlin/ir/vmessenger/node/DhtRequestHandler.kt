package ir.vmessenger.node

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class DhtRequestHandler(
    private val port: Int,
    private val publicHost: String,
    private val nodeId: ByteArray = MessageDigest.getInstance("SHA-256").digest("vmessenger-node".toByteArray()),
    private val verifier: NodeEndpointRecordVerifier = NodeEndpointRecordVerifier(),
) {
    private val records = ConcurrentHashMap<String, EndpointRecord>()

    private val advertisedAddress: String
        get() = "$publicHost:$port"

    fun handle(request: DhtRpcRequest): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder()
                    .setNodeId(ByteString.copyFrom(nodeId)),
            )
            request.hasFindNode() -> builder.setFindNode(
                FindNodeResponse.newBuilder()
                    .addNodes(selfNodeInfo()),
            )
            request.hasStore() -> {
                val record = request.store.record
                val accepted = acceptStore(record)
                builder.setStore(StoreResponse.newBuilder().setAccepted(accepted))
            }
            request.hasFindValue() -> {
                val keyBytes = request.findValue.key.toByteArray()
                val record = records.values.find { entry ->
                    entry.identityHash.toByteArray().contentEquals(keyBytes)
                }
                if (record != null && !isExpired(record)) {
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(true)
                            .setRecord(record),
                    )
                } else {
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(false)
                            .addNodes(selfNodeInfo()),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun selfNodeInfo(): DhtNodeInfo =
        DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(nodeId))
            .setAddress(advertisedAddress)
            .build()

    private fun acceptStore(record: EndpointRecord): Boolean {
        if (!verifier.verify(record)) return false
        val key = record.identityHash.toByteArray().contentHashCode().toString()
        val existing = records[key]
        val accepted = existing == null || record.sequence > existing.sequence
        if (accepted) {
            records[key] = record
        }
        return accepted
    }

    private fun isExpired(record: EndpointRecord): Boolean {
        val now = System.currentTimeMillis()
        return now >= record.publishedAtUnixMs + record.ttlMs
    }
}
