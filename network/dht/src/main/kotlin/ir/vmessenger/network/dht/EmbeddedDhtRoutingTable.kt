package ir.vmessenger.network.dht

import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal k-bucket routing table (k=8) for embedded DHT participation.
 */
class EmbeddedDhtRoutingTable(
    private val localNodeId: ByteArray,
    private val k: Int = K_BUCKET_SIZE,
) {
    private val buckets = ConcurrentHashMap<Int, MutableList<DhtNodeInfo>>()

    fun localId(): ByteArray = localNodeId

    fun insert(node: DhtNodeInfo) {
        if (node.nodeId.toByteArray().contentEquals(localNodeId)) return
        val bucketIndex = bucketIndexFor(node.nodeId.toByteArray())
        val bucket = buckets.getOrPut(bucketIndex) { mutableListOf() }
        synchronized(bucket) {
            val existing = bucket.indexOfFirst {
                it.nodeId.toByteArray().contentEquals(node.nodeId.toByteArray())
            }
            if (existing >= 0) bucket.removeAt(existing)
            bucket.add(0, node)
            while (bucket.size > k) bucket.removeAt(bucket.lastIndex)
        }
    }

    fun findClosest(targetKey: ByteArray, count: Int = k): List<DhtNodeInfo> {
        val targetId = MessageDigest.getInstance("SHA-256").digest(targetKey)
        return buckets.values
            .flatMap { it.toList() }
            .sortedWith(compareBy { xorDistance(it.nodeId.toByteArray(), targetId).contentHashCode() })
            .take(count)
    }

    fun allNodes(): List<DhtNodeInfo> = buckets.values.flatMap { it.toList() }

    fun nodeCount(): Int = buckets.values.sumOf { it.size }

    private fun bucketIndexFor(nodeId: ByteArray): Int {
        val distance = xorDistance(localNodeId, nodeId)
        return distance.indexOfFirst { it != 0.toByte() }.let { if (it < 0) 0 else minOf(it * 8, 255) }
    }

    private fun xorDistance(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        return ByteArray(len) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    companion object {
        const val K_BUCKET_SIZE = 8
    }
}
