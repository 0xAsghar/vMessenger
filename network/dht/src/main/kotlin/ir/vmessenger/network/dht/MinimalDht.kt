package ir.vmessenger.network.dht

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.LengthPrefixedFrames
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
    suspend fun send(address: String, request: DhtRpcRequest): DhtRpcResponse = withContext(Dispatchers.IO) {
        if (address.startsWith("ws://") || address.startsWith("wss://")) {
            val responseBytes = WebSocketFrameClient.sendBinary(address, request.toByteArray())
            DhtRpcResponse.parseFrom(responseBytes)
        } else {
            sendTcp(address, request)
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
    suspend fun bootstrap(nodes: List<ir.vmessenger.network.bootstrap.BootstrapNode>): AppResult<Unit>
    suspend fun publish(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord): AppResult<Unit>
    suspend fun lookup(identityHash: ByteArray): AppResult<ir.vmessenger.core.proto.dht.v1.EndpointRecord?>
}

@Singleton
class MinimalDht @Inject constructor(
    private val rpcClient: DhtRpcClient,
    private val verifier: EndpointRecordVerifier,
) : Dht {
    private val knownNodes = mutableSetOf<String>()

    override suspend fun bootstrap(nodes: List<ir.vmessenger.network.bootstrap.BootstrapNode>): AppResult<Unit> =
        runCatching {
            for (node in nodes) {
                val response = rpcClient.send(
                    node.address,
                    DhtRpcRequest.newBuilder()
                        .setPing(
                            ir.vmessenger.core.proto.dht.v1.PingRequest.newBuilder()
                                .setNodeId(com.google.protobuf.ByteString.EMPTY),
                        )
                        .build(),
                )
                if (response.hasPing()) {
                    knownNodes.add(node.address)
                }
            }
            check(knownNodes.isNotEmpty()) { "Bootstrap failed" }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                AppResult.Error(ir.vmessenger.core.common.AppError.Network(it.message ?: "Bootstrap failed"))
            },
        )

    override suspend fun publish(record: ir.vmessenger.core.proto.dht.v1.EndpointRecord): AppResult<Unit> =
        runCatching {
            val targets = knownNodes.ifEmpty { throw IllegalStateException("Not bootstrapped") }
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
                if (response.hasStore() && response.store.accepted) stored = true
            }
            check(stored) { "Store rejected" }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = {
                AppResult.Error(ir.vmessenger.core.common.AppError.Network(it.message ?: "Publish failed"))
            },
        )

    override suspend fun lookup(identityHash: ByteArray): AppResult<ir.vmessenger.core.proto.dht.v1.EndpointRecord?> =
        runCatching {
            val targets = knownNodes.ifEmpty { throw IllegalStateException("Not bootstrapped") }
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
                    response.findValue.nodesList.forEach { knownNodes.add(it.address) }
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
}
