package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable bounded DHT record store with routing table (Phase 5 / rc27).
 *
 * The local node id comes from [DhtNodeIdProvider] (per-device random, persisted)
 * and is resolved lazily on first use; every DAO access is a plain suspend call
 * so request handling never blocks a thread with `runBlocking`.
 */
@Singleton
class EmbeddedDhtRecordStore @Inject constructor(
    private val verifier: EndpointRecordVerifier,
    private val dhtRecordDao: DhtRecordDao,
    private val rpcClient: DhtRpcSender,
    private val nodeIdProvider: DhtNodeIdProvider,
    private val storeRateLimiter: StoreRateLimiter,
) {
    private class Routing(val nodeId: ByteArray) {
        val table = EmbeddedDhtRoutingTable(nodeId)
    }

    private val routingLock = Mutex()

    @Volatile
    private var routing: Routing? = null
    private val servedLookups = AtomicLong(0)
    private val rejectedRecords = AtomicLong(0)
    private val replicateScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> AppLogger.warn(TAG, "replication failed: ${e.message}") },
    )

    /**
     * Serves one RPC. [source] identifies the requesting client (its IP) and is
     * used only for the per-source STORE rate limit.
     */
    suspend fun handle(request: DhtRpcRequest, advertisedAddress: String, source: String): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        val routing = routing()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder().setNodeId(ByteString.copyFrom(routing.nodeId)),
            )
            request.hasFindNode() -> {
                val closest = routing.table.findClosest(request.findNode.targetKey.toByteArray())
                val nodes = closest.ifEmpty { listOf(selfNode(routing, advertisedAddress)) }
                builder.setFindNode(FindNodeResponse.newBuilder().addAllNodes(nodes))
            }
            request.hasStore() -> {
                val accepted = storeRateLimiter.allow(source) && acceptStore(request.store.record, advertisedAddress)
                if (!accepted) rejectedRecords.incrementAndGet()
                builder.setStore(StoreResponse.newBuilder().setAccepted(accepted))
            }
            request.hasFindValue() -> {
                servedLookups.incrementAndGet()
                val keyBytes = request.findValue.key.toByteArray()
                val record = findRecord(keyBytes)
                val response = FindValueResponse.newBuilder()
                if (record != null && !isExpired(record)) {
                    response.setFound(true).setRecord(record)
                } else {
                    val closest = routing.table.findClosest(keyBytes)
                    val nodes = closest.ifEmpty { listOf(selfNode(routing, advertisedAddress)) }
                    response.setFound(false).addAllNodes(nodes)
                }
                builder.setFindValue(response)
            }
        }
        updateCounters()
        return builder.build()
    }

    suspend fun insertKnownNode(address: String) {
        val info = DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(address.toByteArray())))
            .setAddress(address)
            .build()
        routing().table.insert(info)
    }

    suspend fun storedCount(): Int = dhtRecordDao.count()

    private suspend fun routing(): Routing =
        routing ?: routingLock.withLock {
            routing ?: Routing(nodeIdProvider.nodeId().copyOf()).also { routing = it }
        }

    private fun selfNode(routing: Routing, address: String): DhtNodeInfo =
        DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(routing.nodeId))
            .setAddress(address)
            .build()

    /** Verifies, persists and replicates [record]; false when unverifiable or not newer than the stored one. */
    private suspend fun acceptStore(record: EndpointRecord, advertisedAddress: String): Boolean {
        if (!verifier.verify(record)) return false
        val key = recordKey(record)
        val existing = dhtRecordDao.active(System.currentTimeMillis()).firstOrNull { it.recordKey == key }
        val newer = existing == null || record.sequence > existing.sequence
        if (newer) {
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
        return newer
    }

    private suspend fun findRecord(keyBytes: ByteArray): EndpointRecord? {
        // Routing-prefix key so partial (User Hash derived) lookups match too.
        val key = IdentityHashMatcher.routingKeyHex(keyBytes)
        val entity = dhtRecordDao.active(System.currentTimeMillis()).firstOrNull { it.recordKey == key }
        return entity?.let { runCatching { EndpointRecord.parseFrom(it.recordProto) }.getOrNull() }
    }

    private fun recordKey(record: EndpointRecord): String =
        IdentityHashMatcher.routingKeyHex(record.identityHash.toByteArray())

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

    private suspend fun updateCounters() {
        NetworkPathTracker.setDhtCounters(
            stored = dhtRecordDao.count(),
            servedLookups = servedLookups.get(),
            rejected = rejectedRecords.get(),
        )
    }

    private suspend fun replicateStore(record: EndpointRecord, advertisedAddress: String) {
        val peers = routing().table.findClosest(record.identityHash.toByteArray(), REPLICATION_FACTOR)
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
                    AppLogger.warn(TAG, "replicate to ${peer.address} failed: ${it.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "EmbeddedDht"
        const val MAX_RECORDS = 500
        private const val REPLICATION_FACTOR = 2
    }
}

/**
 * TCP DHT RPC listener so online Android clients can store and return signed
 * endpoint records. Off by default (`P2PConfig`);
 * when on, at most [MAX_CONCURRENT_CLIENTS] clients are served at once, each
 * with a [CLIENT_TIMEOUT_MS] socket timeout, and [stop] closes the listener.
 */
@Singleton
class EmbeddedDhtService @Inject constructor(
    private val recordStore: EmbeddedDhtRecordStore,
    private val dhtPolicy: DhtParticipationPolicy,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> AppLogger.error(TAG, "unhandled: ${e.message}") },
    )
    private val clientPermits = Semaphore(MAX_CONCURRENT_CLIENTS)

    @Volatile
    private var running = false

    @Volatile
    private var server: ServerSocket? = null

    val isRunning: Boolean
        get() = running

    /** Local port of the bound listener while running, else null. */
    fun listeningPort(): Int? = server?.takeIf { !it.isClosed }?.localPort

    /** Closes the listener (which ends the accept loop) and drops every client handler. */
    fun stop() {
        if (!running) return
        running = false
        server?.let { runCatching { it.close() } }
        server = null
        scope.coroutineContext.cancelChildren()
        NetworkPathTracker.setDhtParticipating(false)
        AppLogger.info(TAG, "stopped")
    }

    fun start(dhtPort: Int, advertisedHost: String) {
        if (!P2PConfig.dhtParticipationEnabled || running) return
        if (!dhtPolicy.shouldParticipate()) {
            AppLogger.info(TAG, "skipped: policy/battery/network gate")
            NetworkPathTracker.setDhtParticipating(false)
            return
        }
        if (!dhtPolicy.shouldAdvertise(advertisedHost)) {
            AppLogger.info(TAG, "skipped: host not advertisable ($advertisedHost)")
            NetworkPathTracker.setDhtParticipating(false)
            return
        }
        running = true
        val advertised = "$advertisedHost:$dhtPort"
        NetworkPathTracker.setDhtParticipating(true)
        scope.launch {
            AppLogger.info(TAG, "listening on $advertised")
            var bound: ServerSocket? = null
            runCatching {
                ServerSocket(dhtPort).use { listener ->
                    bound = listener
                    server = listener
                    acceptLoop(listener, advertised)
                }
            }.onFailure {
                // A listener closed by stop() (no longer the current one) is the normal end;
                // a bind failure or an error on the current listener is a real failure.
                val current = bound == null || server === bound
                if (running && current) {
                    AppLogger.error(TAG, "server stopped: ${it.message}")
                    running = false
                    NetworkPathTracker.setDhtParticipating(false)
                }
            }
        }
    }

    private suspend fun acceptLoop(listener: ServerSocket, advertised: String) = coroutineScope {
        while (isActive) {
            val socket = listener.accept()
            if (!clientPermits.tryAcquire()) {
                AppLogger.warn(TAG, "client limit reached; dropping ${socket.inetAddress?.hostAddress}")
                runCatching { socket.close() }
                continue
            }
            launch {
                try {
                    handleClient(socket, advertised)
                } finally {
                    clientPermits.release()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleClient(socket: Socket, advertised: String) {
        try {
            socket.use { s ->
                s.soTimeout = CLIENT_TIMEOUT_MS
                val source = s.inetAddress?.hostAddress ?: "unknown"
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val frame = LengthPrefixedFrames.readFrame(input, MAX_REQUEST_SIZE) ?: return
                val request = DhtRpcRequest.parseFrom(frame)
                val response = recordStore.handle(request, advertised, source)
                LengthPrefixedFrames.writeFrame(output, response.toByteArray())
            }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "client error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "EmbeddedDht"
        const val PORT_OFFSET = 1000
        const val MAX_CONCURRENT_CLIENTS = 8
        const val CLIENT_TIMEOUT_MS = 5_000

        /** Upper bound for one RPC request frame; a signed endpoint record is far smaller. */
        const val MAX_REQUEST_SIZE = 64 * 1024
    }
}
