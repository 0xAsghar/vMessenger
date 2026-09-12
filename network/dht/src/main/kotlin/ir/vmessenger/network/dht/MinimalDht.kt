package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindValueRequest
import ir.vmessenger.core.proto.dht.v1.PingRequest
import ir.vmessenger.core.proto.dht.v1.StoreRequest
import ir.vmessenger.network.bootstrap.BootstrapNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DhtRpcClient @Inject constructor() : DhtRpcSender {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun send(address: String, request: DhtRpcRequest): DhtRpcResponse = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            // Policy gate: release builds only ever dial wss://; ws:// and host:port need a debug build + local host.
            require(NodeAddressPolicy.current.isBootstrapAllowed(address)) { "address rejected by NodeAddressPolicy" }
            val response = if (address.startsWith("ws://") || address.startsWith("wss://")) {
                val responseBytes = WebSocketFrameClient.sendBinary(address, request.toByteArray())
                DhtRpcResponse.parseFrom(responseBytes)
            } else {
                sendTcp(address, request)
            }
            AppLogger.debug("DhtRpc", "OK $address in ${System.currentTimeMillis() - started}ms")
            NetworkPathTracker.reportConnectionSuccess()
            response
        } catch (e: Exception) {
            AppLogger.error("DhtRpc", "FAIL $address: ${e.message}")
            NetworkPathTracker.reportConnectionError(e)
            throw e
        }
    }

    private fun sendTcp(address: String, request: DhtRpcRequest): DhtRpcResponse {
        val (host, port) = address.splitHostPort()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            LengthPrefixedFrames.writeFrame(out, request.toByteArray())
            val responseBytes = LengthPrefixedFrames.readFrame(input)
                ?: error("Empty DHT response from $address")
            return DhtRpcResponse.parseFrom(responseBytes)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
    }
}

private fun String.splitHostPort(): Pair<String, Int> {
    val idx = lastIndexOf(':')
    require(idx > 0) { "Invalid address: $this" }
    return substring(0, idx) to substring(idx + 1).toInt()
}

interface Dht {
    /**
     * Pings each candidate node and returns the subset that responded, so callers
     * can record per-node health and rotate away from unreachable nodes.
     */
    suspend fun bootstrap(nodes: List<BootstrapNode>): AppResult<List<BootstrapNode>>

    suspend fun publish(record: EndpointRecord): AppResult<Unit>

    suspend fun lookup(identityHash: ByteArray): AppResult<EndpointRecord?>

    /** Addresses of DHT nodes currently known to this client (for caching/persistence). */
    fun knownNodeAddresses(): Set<String>
}

/**
 * Client side of the DHT: talks to the bootstrap/DHT nodes it was given plus the
 * nodes those return. Both sets are concurrent because bootstrap, publish and
 * lookups run from independent coroutines.
 */
@Singleton
class MinimalDht @Inject constructor(
    private val rpcClient: DhtRpcSender,
    private val verifier: EndpointRecordVerifier,
) : Dht {
    private val knownNodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Addresses of the (enabled) bootstrap nodes handed to [bootstrap]; always acceptable RPC targets. */
    private val bootstrapAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Consecutive RPC failures per target; reset on the first success. */
    private val failures = ConcurrentHashMap<String, Int>()

    override suspend fun bootstrap(nodes: List<BootstrapNode>): AppResult<List<BootstrapNode>> =
        runCatching {
            AppLogger.info("Dht", "bootstrap ${nodes.size} node(s): ${nodes.joinToString { it.address }}")
            bootstrapAddresses.addAll(nodes.map { it.address })
            val responders = mutableListOf<BootstrapNode>()
            for (node in nodes) {
                if (pingNode(node.address)) {
                    knownNodes.add(node.address)
                    responders.add(node)
                }
            }
            check(knownNodes.isNotEmpty()) { "Bootstrap failed" }
            AppLogger.info("Dht", "bootstrap OK, knownNodes=${knownNodes.size}")
            responders.toList()
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppLogger.error("Dht", "bootstrap failed: ${it.message}")
                AppResult.Error(AppError.Network(it.message ?: "Bootstrap failed"))
            },
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pingNode(address: String): Boolean = try {
        val response = rpcClient.send(
            address,
            DhtRpcRequest.newBuilder()
                .setPing(PingRequest.newBuilder().setNodeId(ByteString.EMPTY))
                .build(),
        )
        response.hasPing()
    } catch (e: Exception) {
        AppLogger.warn("Dht", "ping failed $address: ${e.message}")
        false
    }

    /**
     * Stores at every reachable target; one unreachable (or policy-rejected)
     * learned node never aborts the store at the others. Fails only when no
     * target accepted the record.
     */
    override suspend fun publish(record: EndpointRecord): AppResult<Unit> =
        runCatching {
            val targets = rpcTargets().ifEmpty { throw IllegalStateException("Not bootstrapped") }
            val request = DhtRpcRequest.newBuilder()
                .setStore(StoreRequest.newBuilder().setRecord(record))
                .build()
            var stored = false
            var attempted = 0
            for (address in targets) {
                val response = sendTo(address, request) ?: continue
                attempted++
                when {
                    response.hasStore() && response.store.accepted -> stored = true
                    response.hasStore() -> AppLogger.warn("Dht", "store rejected seq=${record.sequence} at $address")
                    else -> AppLogger.warn("Dht", "store response missing at $address")
                }
            }
            check(attempted > 0) { "No DHT node reachable" }
            check(stored) { "Store rejected (seq=${record.sequence})" }
            AppLogger.info("Dht", "publish/store OK seq=${record.sequence}")
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                AppLogger.error("Dht", "publish failed: ${it.message}")
                AppResult.Error(AppError.Network(it.message ?: "Publish failed"))
            },
        )

    /** Asks each target in turn; a failing target is skipped, and only an all-fail run is an error. */
    override suspend fun lookup(identityHash: ByteArray): AppResult<EndpointRecord?> =
        runCatching {
            val targets = rpcTargets().ifEmpty { throw IllegalStateException("Not bootstrapped") }
            val request = DhtRpcRequest.newBuilder()
                .setFindValue(FindValueRequest.newBuilder().setKey(ByteString.copyFrom(identityHash)))
                .build()
            var answered = 0
            for (address in targets) {
                val response = sendTo(address, request) ?: continue
                answered++
                if (response.hasFindValue() && response.findValue.found) {
                    val record = response.findValue.record
                    if (verifier.verify(record)) return@runCatching record
                }
                if (response.hasFindValue()) {
                    response.findValue.nodesList.forEach { node ->
                        normalizeDhtRpcAddress(node.address, bootstrapAddresses)?.let { knownNodes.add(it) }
                    }
                }
            }
            check(answered > 0) { "No DHT node reachable" }
            null
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error(AppError.Network(it.message ?: "Lookup failed")) },
        )

    /**
     * One RPC to one target: failures are logged and counted, never thrown, and a
     * learned node that failed [MAX_TARGET_FAILURES] times in a row is forgotten
     * (bootstrap nodes stay, their health is tracked by the node repository).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendTo(address: String, request: DhtRpcRequest): DhtRpcResponse? = try {
        rpcClient.send(address, request).also { failures.remove(address) }
    } catch (e: Exception) {
        AppLogger.warn("Dht", "rpc failed $address: ${e.message}")
        val count = failures.merge(address, 1, Int::plus) ?: 1
        if (count >= MAX_TARGET_FAILURES && address !in bootstrapAddresses) {
            knownNodes.remove(address)
            failures.remove(address)
            AppLogger.info("Dht", "dropped unreachable learned node $address")
        }
        null
    }

    fun knownNodeCount(): Int = knownNodes.size

    override fun knownNodeAddresses(): Set<String> = knownNodes.toSet()

    private fun rpcTargets(): Set<String> =
        knownNodes.mapNotNull { normalizeDhtRpcAddress(it, bootstrapAddresses) }.toSet()

    private companion object {
        const val MAX_TARGET_FAILURES = 3
    }
}

/**
 * Maps a DHT node address to something the RPC client may dial: WebSocket URLs
 * the [policy] admits (release: `wss://` only), the official relay's DHT
 * endpoint, the emulator dev bootstrap, or an address the user explicitly
 * enabled as a bootstrap node ([trusted], already policy-checked when stored).
 * Anything else a peer or node hands us is ignored so the network cannot steer
 * us to arbitrary hosts.
 */
internal fun normalizeDhtRpcAddress(
    address: String,
    trusted: Set<String> = emptySet(),
    policy: NodeAddressPolicy = NodeAddressPolicy.current,
): String? = when {
    address in trusted -> address
    address.startsWith("ws://") || address.startsWith("wss://") ->
        address.takeIf { policy.isBootstrapAllowed(it) }
    address == "${NetworkConfig.RELAY_HOST}:8443" -> NetworkConfig.DEFAULT_DHT_URL
    address == NetworkConfig.DEV_BOOTSTRAP_ADDRESS -> address
    else -> null
}
