package ir.vmessenger.node

import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class DhtNodeServer(
    private val port: Int,
    private val nodeId: ByteArray = MessageDigest.getInstance("SHA-256").digest("vmessenger-node".toByteArray()),
) {
    private val records = ConcurrentHashMap<String, EndpointRecord>()

    fun start() {
        println("vMessenger DHT node listening on port $port")
        ServerSocket(port).use { server ->
            while (true) {
                val socket = server.accept()
                thread { handleClient(socket) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            try {
                val requestBytes = LengthPrefixedFrames.readFrame(input) ?: return
                val request = DhtRpcRequest.parseFrom(requestBytes)
                val response = handleRequest(request)
                LengthPrefixedFrames.writeFrame(output, response.toByteArray())
            } catch (e: IOException) {
                System.err.println("Client error: ${e.message}")
            }
        }
    }

    private fun handleRequest(request: DhtRpcRequest): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder()
                    .setNodeId(com.google.protobuf.ByteString.copyFrom(nodeId)),
            )
            request.hasFindNode() -> builder.setFindNode(
                FindNodeResponse.newBuilder()
                    .addNodes(
                        ir.vmessenger.core.proto.dht.v1.DhtNodeInfo.newBuilder()
                            .setNodeId(com.google.protobuf.ByteString.copyFrom(nodeId))
                            .setAddress("127.0.0.1:$port"),
                    ),
            )
            request.hasStore() -> {
                val record = request.store.record
                val key = record.identityHash.toByteArray().contentHashCode().toString()
                val existing = records[key]
                val accepted = existing == null || record.sequence > existing.sequence
                if (accepted) {
                    records[key] = record
                }
                builder.setStore(StoreResponse.newBuilder().setAccepted(accepted))
            }
            request.hasFindValue() -> {
                val keyBytes = request.findValue.key.toByteArray()
                val record = records.values.find { entry ->
                    entry.identityHash.toByteArray().contentEquals(keyBytes)
                }
                if (record != null) {
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(true)
                            .setRecord(record),
                    )
                } else {
                    builder.setFindValue(
                        FindValueResponse.newBuilder()
                            .setFound(false)
                            .addNodes(
                                ir.vmessenger.core.proto.dht.v1.DhtNodeInfo.newBuilder()
                                    .setNodeId(com.google.protobuf.ByteString.copyFrom(nodeId))
                                    .setAddress("127.0.0.1:$port"),
                            ),
                    )
                }
            }
        }
        return builder.build()
    }
}
