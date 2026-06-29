package ir.vmessenger.network.dht

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DhtRpcClient @Inject constructor() {
    @Suppress("TooGenericExceptionCaught")
    suspend fun send(address: String, request: DhtRpcRequest): DhtRpcResponse = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val response = if (address.startsWith("ws://") || address.startsWith("wss://")) {
                val responseBytes = WebSocketFrameClient.sendBinary(address, request.toByteArray())
                DhtRpcResponse.parseFrom(responseBytes)
            } else {
                sendTcp(address, request)
            }
            AppLogger.debug("DhtRpc", "OK $address in ${System.currentTimeMillis() - started}ms")
            response
        } catch (e: Exception) {
            AppLogger.error("DhtRpc", "FAIL $address: ${e.message}")
            throw e
        }
    }

    private fun sendTcp(address: String, request: DhtRpcRequest): DhtRpcResponse {
        val (host, port) = address.splitHostPort()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
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
    suspend fun bootstrap(
        nodes: List<ir.vmessenger.network.bootstrap.BootstrapNode>,
    ): AppResult<List<ir.vmessenger.network.bootstrap.BootstrapNode>>
    suspend fun publish(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord): AppResult<Unit>
    suspend fun lookup(identityHash: ByteArray): AppResult<ir.vmessenger.core.proto.dht.v1.EndpointRecord?>

    /** Addresses of DHT nodes currently known to this client (for caching/persistence). */
    fun knownNodeAddresses(): Set<String>
}

@Singleton
class MinimalDht @Inject constructor(
    private val rpcClient: DhtRpcClient,
    private val verifier: EndpointRecordVerifier,
) : Dht {
    private val knownNodes = mutableSetOf<String>()

    override suspend fun bootstrap(
        nodes: List<ir.vmessenger.network.bootstrap.BootstrapNode>,
    ): AppResult<List<ir.vmessenger.network.bootstrap.BootstrapNode>> =
        runCatching {
            AppLogger.info("Dht", "bootstrap ${nodes.size} node(s): ${nodes.joinToString { it.address }}")
            val responders = mutableListOf<ir.vmessenger.network.bootstrap.BootstrapNode>()
            for (node in nodes) {
                val reachable = pingNode(node.address)
                if (reachable) {
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
                AppResult.Error(ir.vmessenger.core.common.AppError.Network(it.message ?: "Bootstrap failed"))
            },
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pingNode(address: String): Boolean = try {
        val response = rpcClient.send(
            address,
            DhtRpcRequest.newBuilder()
                .setPing(
                    ir.vmessenger.core.proto.dht.v1.PingRequest.newBuilder()
                        .setNodeId(com.google.protobuf.ByteString.EMPTY),
                )
                .build(),
        )
        response.hasPing()
    } catch (e: Exception) {
        AppLogger.warn("Dht", "ping failed $address: ${e.message}")
        false
    }

    override suspend fun publish(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord): AppResult<Unit> =
        runCatching {
            val targets = rpcTargets().ifEmpty { throw IllegalStateException("Not bootstrapped") }
            var stored = false
            for (address in targets) {
                val response = rpcClient.send(
                    address,
                    DhtRpcRequest.newBuilder()
                        .setStore(
                            ir.vmessenger.core.proto.dht.v1.StoreRequest.newBuilder().setRecord(record),
                        )
                        .build(),
                )
                when {
                    response.hasStore() && response.store.accepted -> stored = true
                    response.hasStore() ->
                        AppLogger.warn(
                            "Dht",
                            "store rejected seq=${record.sequence} at $address",
                        )
                    else -> AppLogger.warn("Dht", "store response missing at $address")
                }
            }
            check(stored) { "Store rejected (seq=${record.sequence})" }
            AppLogger.info("Dht", "publish/store OK seq=${record.sequence}")
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                AppLogger.error("Dht", "publish failed: ${it.message}")
                AppResult.Error(ir.vmessenger.core.common.AppError.Network(it.message ?: "Publish failed"))
            },
        )

    override suspend fun lookup(identityHash: ByteArray): AppResult<ir.vmessenger.core.proto.dht.v1.EndpointRecord?> =
        runCatching {
            val targets = rpcTargets().ifEmpty { throw IllegalStateException("Not bootstrapped") }
            for (address in targets) {
                val response = rpcClient.send(
                    address,
                    DhtRpcRequest.newBuilder()
                        .setFindValue(
                            ir.vmessenger.core.proto.dht.v1.FindValueRequest.newBuilder()
                                .setKey(com.google.protobuf.ByteString.copyFrom(identityHash)),
                        )
                        .build(),
                )
                if (response.hasFindValue() && response.findValue.found) {
                    val record = response.findValue.record
                    if (verifier.verify(record)) return@runCatching record
                }
                if (response.hasFindValue()) {
                    response.findValue.nodesList.forEach { node ->
                        normalizeDhtRpcAddress(node.address)?.let { knownNodes.add(it) }
                    }
                }
            }
            null
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppResult.Error(ir.vmessenger.core.common.AppError.Network(it.message ?: "Lookup failed"))
            },
        )

    fun knownNodeCount(): Int = knownNodes.size

    override fun knownNodeAddresses(): Set<String> = knownNodes.toSet()

    private fun rpcTargets(): Set<String> = knownNodes.mapNotNull(::normalizeDhtRpcAddress).toSet()
}

private fun normalizeDhtRpcAddress(address: String): String? = when {
    address.startsWith("ws://") || address.startsWith("wss://") -> address
    address == "${NetworkConfig.RELAY_HOST}:8443" -> NetworkConfig.DEFAULT_DHT_URL
    address == NetworkConfig.DEV_BOOTSTRAP_ADDRESS -> address
    else -> null
}
