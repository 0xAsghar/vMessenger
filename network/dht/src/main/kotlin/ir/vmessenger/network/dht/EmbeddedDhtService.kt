package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal in-memory DHT record store for embedded participation (Phase 5).
 */
@Singleton
class EmbeddedDhtRecordStore @Inject constructor(
    private val verifier: EndpointRecordVerifier,
) {
    private val records = ConcurrentHashMap<String, EndpointRecord>()
    private val nodeId = MessageDigest.getInstance("SHA-256").digest("vmessenger-android-dht".toByteArray())

    fun handle(request: DhtRpcRequest, advertisedAddress: String): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder().setNodeId(ByteString.copyFrom(nodeId)),
            )
            request.hasFindNode() -> builder.setFindNode(
                FindNodeResponse.newBuilder().addNodes(selfNode(advertisedAddress)),
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
                            .addNodes(selfNode(advertisedAddress)),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun selfNode(address: String): DhtNodeInfo =
        DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(nodeId))
            .setAddress(address)
            .build()

    private fun acceptStore(record: EndpointRecord): Boolean {
        if (!verifier.verify(record)) return false
        val key = record.identityHash.toByteArray().contentHashCode().toString()
        val existing = records[key]
        val accepted = existing == null || record.sequence > existing.sequence
        if (accepted) records[key] = record
        return accepted
    }

    private fun isExpired(record: EndpointRecord): Boolean {
        val now = System.currentTimeMillis()
        return now >= record.publishedAtUnixMs + record.ttlMs
    }

    fun storedCount(): Int = records.size
}

/**
 * TCP DHT RPC listener so online Android clients can store and return signed
 * endpoint records (docs/P2P-Phases.md Phase 5).
 */
@Singleton
class EmbeddedDhtService @Inject constructor(
    private val recordStore: EmbeddedDhtRecordStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false

    fun start(dhtPort: Int, advertisedHost: String) {
        if (!P2PConfig.dhtParticipationEnabled || running) return
        running = true
        val advertised = "$advertisedHost:$dhtPort"
        scope.launch {
            AppLogger.info("EmbeddedDht", "listening on $advertised")
            runCatching {
                ServerSocket(dhtPort).use { server ->
                    while (isActive) {
                        val socket = server.accept()
                        launch {
                            handleClient(socket, advertised)
                        }
                    }
                }
            }.onFailure {
                AppLogger.error("EmbeddedDht", "server stopped: ${it.message}")
                running = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleClient(socket: java.net.Socket, advertised: String) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val frame = LengthPrefixedFrames.readFrame(input) ?: return
                val request = DhtRpcRequest.parseFrom(frame)
                val response = recordStore.handle(request, advertised)
                LengthPrefixedFrames.writeFrame(output, response.toByteArray())
            }
        } catch (e: Exception) {
            AppLogger.warn("EmbeddedDht", "client error: ${e.message}")
        }
    }

    companion object {
        const val PORT_OFFSET = 1000
    }
}
