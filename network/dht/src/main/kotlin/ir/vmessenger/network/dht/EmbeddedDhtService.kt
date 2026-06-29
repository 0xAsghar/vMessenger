package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.database.dao.DhtRecordDao
import ir.vmessenger.core.database.entity.DhtRecordEntity
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreRequest
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable bounded DHT record store with routing table (Phase 5 / rc27).
 */
@Singleton
class EmbeddedDhtRecordStore @Inject constructor(
    private val verifier: EndpointRecordVerifier,
    private val dhtRecordDao: DhtRecordDao,
    private val rpcClient: DhtRpcClient,
) {
    private val nodeId = MessageDigest.getInstance("SHA-256").digest("vmessenger-android-dht".toByteArray())
    private val routingTable = EmbeddedDhtRoutingTable(nodeId)
    private val servedLookups = AtomicLong(0)
    private val rejectedRecords = AtomicLong(0)
    private val replicateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(request: DhtRpcRequest, advertisedAddress: String): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder().setNodeId(ByteString.copyFrom(nodeId)),
            )
            request.hasFindNode() -> {
                val closest = routingTable.findClosest(request.findNode.targetKey.toByteArray())
                val nodes = if (closest.isEmpty()) listOf(selfNode(advertisedAddress)) else closest
                builder.setFindNode(FindNodeResponse.newBuilder().addAllNodes(nodes))
            }
            request.hasStore() -> {
                val record = request.store.record
                val accepted = acceptStore(record, advertisedAddress)
                builder.setStore(StoreResponse.newBuilder().setAccepted(accepted))
            }
            request.hasFindValue() -> {
                servedLookups.incrementAndGet()
                val keyBytes = request.findValue.key.toByteArray()
                val record = findRecord(keyBytes)
                if (record != null && !isExpired(record)) {
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(true)
                            .setRecord(record),
                    )
                } else {
                    val closest = routingTable.findClosest(keyBytes)
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(false)
                            .addAllNodes(
                                if (closest.isEmpty()) listOf(selfNode(advertisedAddress)) else closest,
                            ),
                    )
                }
            }
        }
        updateCounters()
        return builder.build()
    }

    fun insertKnownNode(address: String) {
        val info = DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(address.toByteArray())))
            .setAddress(address)
            .build()
        routingTable.insert(info)
    }

    private fun selfNode(address: String): DhtNodeInfo =
        DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(nodeId))
            .setAddress(address)
            .build()

    private fun acceptStore(record: EndpointRecord, advertisedAddress: String): Boolean {
        if (!verifier.verify(record)) {
            rejectedRecords.incrementAndGet()
            return false
        }
        val key = recordKey(record)
        val existing = runBlocking { dhtRecordDao.active(System.currentTimeMillis()) }
            .firstOrNull { it.recordKey == key }
        val accepted = existing == null || record.sequence > existing.sequence
        if (accepted) {
            runBlocking {
                dhtRecordDao.upsert(
                    DhtRecordEntity(
                        recordKey = key,
                        recordProto = record.toByteArray(),
                        sequence = record.sequence,
                        expiresAtUnixMs = record.publishedAtUnixMs + record.ttlMs,
                        storedAtUnixMs = System.currentTimeMillis(),
                    ),
                )
                enforceLimits()
                replicateStore(record, advertisedAddress)
            }
        } else {
            rejectedRecords.incrementAndGet()
        }
        updateCounters()
        return accepted
    }

    private fun findRecord(keyBytes: ByteArray): EndpointRecord? {
        val key = keyBytes.contentHashCode().toString()
        val entity = runBlocking { dhtRecordDao.active(System.currentTimeMillis()) }
            .firstOrNull { it.recordKey == key }
        return entity?.let { EndpointRecord.parseFrom(it.recordProto) }
    }

    private fun recordKey(record: EndpointRecord): String =
        record.identityHash.toByteArray().contentHashCode().toString()

    private fun isExpired(record: EndpointRecord): Boolean {
        val now = System.currentTimeMillis()
        return now >= record.publishedAtUnixMs + record.ttlMs
    }

    private suspend fun enforceLimits() {
        val now = System.currentTimeMillis()
        dhtRecordDao.purgeExpired(now)
        val count = dhtRecordDao.count()
        if (count > MAX_RECORDS) {
            dhtRecordDao.evictOldest(count - MAX_RECORDS)
        }
    }

    private fun updateCounters() {
        val count = runBlocking { dhtRecordDao.count() }
        NetworkPathTracker.setDhtCounters(
            stored = count,
            servedLookups = servedLookups.get(),
            rejected = rejectedRecords.get(),
        )
    }

    private fun replicateStore(record: EndpointRecord, advertisedAddress: String) {
        val peers = routingTable.findClosest(record.identityHash.toByteArray(), REPLICATION_FACTOR)
            .filter { it.address != advertisedAddress }
            .take(REPLICATION_FACTOR)
        for (peer in peers) {
            replicateScope.launch {
                runCatching {
                    rpcClient.send(
                        peer.address,
                        DhtRpcRequest.newBuilder()
                            .setStore(StoreRequest.newBuilder().setRecord(record))
                            .build(),
                    )
                }.onFailure {
                    AppLogger.warn("EmbeddedDht", "replicate to ${peer.address} failed: ${it.message}")
                }
            }
        }
    }

    fun storedCount(): Int = runBlocking { dhtRecordDao.count() }

    companion object {
        const val MAX_RECORDS = 500
        private const val REPLICATION_FACTOR = 2
    }
}

/**
 * TCP DHT RPC listener so online Android clients can store and return signed
 * endpoint records (docs/P2P-Phases.md Phase 5).
 */
@Singleton
class EmbeddedDhtService @Inject constructor(
    private val recordStore: EmbeddedDhtRecordStore,
    private val dhtPolicy: EmbeddedDhtPolicy,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false

    fun start(dhtPort: Int, advertisedHost: String) {
        if (!P2PConfig.dhtParticipationEnabled || running) return
        if (!dhtPolicy.shouldParticipate()) {
            AppLogger.info("EmbeddedDht", "skipped: policy/battery/network gate")
            NetworkPathTracker.setDhtParticipating(false)
            return
        }
        if (!dhtPolicy.shouldAdvertise(advertisedHost)) {
            AppLogger.info("EmbeddedDht", "skipped: host not advertisable ($advertisedHost)")
            NetworkPathTracker.setDhtParticipating(false)
            return
        }
        running = true
        val advertised = "$advertisedHost:$dhtPort"
        NetworkPathTracker.setDhtParticipating(true)
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
                NetworkPathTracker.setDhtParticipating(false)
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
