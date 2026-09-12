package ir.vmessenger.network.dht

import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse

/** One DHT RPC round trip to [address]; [DhtRpcClient] is the network implementation. */
fun interface DhtRpcSender {
    suspend fun send(address: String, request: DhtRpcRequest): DhtRpcResponse
}
