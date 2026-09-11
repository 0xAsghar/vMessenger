package ir.vmessenger.node

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.Endpoint
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeRequest
import ir.vmessenger.core.proto.dht.v1.FindValueRequest
import ir.vmessenger.core.proto.dht.v1.PingRequest
import ir.vmessenger.core.proto.dht.v1.StoreRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DhtRequestHandlerTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private var now = 1_700_000_000_000L
    private val nodeId = ByteArray(32) { 0x42 }
    private val counters = RecordingCounters()

    private class RecordingCounters : DhtCounters {
        var stored = 0
        val rejected = mutableListOf<String>()
        var lastRecordCount = -1
        override fun onStored() { stored++ }
        override fun onRejected(reason: String) { rejected += reason }
        override fun setRecordCount(n: Int) { lastRecordCount = n }
    }

    private class Identity(val pub: ByteArray, val secret: ByteArray) {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(pub)
    }

    private fun identity(): Identity {
        val pub = ByteArray(32)
        val secret = ByteArray(64)
        check(sodium.cryptoSignKeypair(pub, secret))
        return Identity(pub, secret)
    }

    private fun handler(
        maxRecords: Int = 100,
        maxRecordTtlMs: Long = 60 * 60 * 1000L,
        maxFutureSkewMs: Long = 5 * 60 * 1000L,
        peerNodes: List<String> = emptyList(),
    ) = DhtRequestHandler(
        nodeId = nodeId,
        advertisedAddress = ADVERTISED,
        peerNodes = peerNodes,
        maxRecords = maxRecords,
        maxRecordTtlMs = maxRecordTtlMs,
        maxFutureSkewMs = maxFutureSkewMs,
        clock = { now },
        stats = counters,
    )

    private fun signedRecord(
        id: Identity,
        sequence: Long = 1,
        publishedAt: Long = now,
        ttlMs: Long = 60_000,
        address: String = "wss://relay.example/relay",
    ): EndpointRecord {
        val unsigned = EndpointRecord.newBuilder()
            .setIdentityHash(ByteString.copyFrom(id.hash))
            .setIdentityPub(ByteString.copyFrom(id.pub))
            .addEndpoints(Endpoint.newBuilder().setTransport("relay").setAddress(address))
            .setPublishedAtUnixMs(publishedAt)
            .setTtlMs(ttlMs)
            .setSequence(sequence)
            .build()
        val transcript = NodeEndpointRecordVerifier.buildTranscript(unsigned)
        val signature = ByteArray(64)
        check(sodium.cryptoSignDetached(signature, transcript, transcript.size.toLong(), id.secret))
        return unsigned.toBuilder().setSignature(ByteString.copyFrom(signature)).build()
    }

    private fun DhtRequestHandler.store(record: EndpointRecord): Boolean =
        handle(
            DhtRpcRequest.newBuilder().setStore(StoreRequest.newBuilder().setRecord(record)).build(),
        ).store.accepted

    private fun DhtRequestHandler.findValue(key: ByteArray) =
        handle(
            DhtRpcRequest.newBuilder()
                .setFindValue(FindValueRequest.newBuilder().setKey(ByteString.copyFrom(key)))
                .build(),
        ).findValue

    @Test
    fun `store accepts a freshly signed record`() {
        val h = handler()
        val record = signedRecord(identity())

        assertTrue(h.store(record))
        assertEquals(1, h.recordCount())
        assertEquals(1, counters.stored)
        assertEquals(1, counters.lastRecordCount)
    }

    @Test
    fun `store rejects tampered signature`() {
        val h = handler()
        val record = signedRecord(identity()).toBuilder().setSequence(9).build()

        assertFalse(h.store(record))
        assertEquals(listOf(DhtRequestHandler.REASON_INVALID), counters.rejected)
    }

    @Test
    fun `store rejects lower or equal sequence and accepts higher`() {
        val h = handler()
        val id = identity()
        assertTrue(h.store(signedRecord(id, sequence = 5)))

        assertFalse(h.store(signedRecord(id, sequence = 5)))
        assertFalse(h.store(signedRecord(id, sequence = 4)))
        assertTrue(h.store(signedRecord(id, sequence = 6)))
        assertEquals(listOf(DhtRequestHandler.REASON_SEQUENCE, DhtRequestHandler.REASON_SEQUENCE), counters.rejected)
        assertEquals(6L, h.findValue(id.hash).record.sequence)
    }

    @Test
    fun `store rejects oversize and non-positive ttl`() {
        val h = handler(maxRecordTtlMs = 10_000)
        assertFalse(h.store(signedRecord(identity(), ttlMs = 10_001)))
        assertFalse(h.store(signedRecord(identity(), ttlMs = 0)))
        assertFalse(h.store(signedRecord(identity(), ttlMs = -5)))
        assertTrue(h.store(signedRecord(identity(), ttlMs = 10_000)))
        assertEquals(List(3) { DhtRequestHandler.REASON_TTL }, counters.rejected)
    }

    @Test
    fun `store rejects publishedAt beyond the future skew`() {
        val h = handler(maxFutureSkewMs = 1_000)
        assertFalse(h.store(signedRecord(identity(), publishedAt = now + 1_001)))
        assertTrue(h.store(signedRecord(identity(), publishedAt = now + 1_000)))
        assertEquals(listOf(DhtRequestHandler.REASON_FUTURE), counters.rejected)
    }

    @Test
    fun `store rejects already expired record`() {
        val h = handler()
        assertFalse(h.store(signedRecord(identity(), publishedAt = now - 60_000, ttlMs = 60_000)))
        assertEquals(listOf(DhtRequestHandler.REASON_EXPIRED), counters.rejected)
    }

    @Test
    fun `maxRecords caps new keys but still allows updates`() {
        val h = handler(maxRecords = 2)
        val first = identity()
        assertTrue(h.store(signedRecord(first, sequence = 1)))
        assertTrue(h.store(signedRecord(identity(), sequence = 1)))

        assertFalse(h.store(signedRecord(identity(), sequence = 1)))
        assertEquals(listOf(DhtRequestHandler.REASON_CAPACITY), counters.rejected)
        assertTrue(h.store(signedRecord(first, sequence = 2)))
        assertEquals(2, h.recordCount())
    }

    @Test
    fun `findValue resolves by 16-byte prefix and misses unknown keys`() {
        val h = handler(peerNodes = listOf(PEER))
        val id = identity()
        val record = signedRecord(id)
        assertTrue(h.store(record))

        val partialKey = id.hash.copyOf(16).copyOf(32)
        val hit = h.findValue(partialKey)
        assertTrue(hit.found)
        assertEquals(record, hit.record)
        assertEquals(0, hit.nodesCount)

        val miss = h.findValue(ByteArray(32) { 0x7f })
        assertFalse(miss.found)
        assertEquals(listOf(ADVERTISED, PEER), miss.nodesList.map { it.address })
    }

    @Test
    fun `expiry sweep removes stale records and lookup hides them`() {
        val h = handler()
        val id = identity()
        assertTrue(h.store(signedRecord(id, ttlMs = 1_000)))
        assertTrue(h.store(signedRecord(identity(), ttlMs = 100_000)))

        now += 1_000
        assertFalse(h.findValue(id.hash).found)
        assertEquals(2, h.recordCount())

        assertEquals(1, h.sweepExpired())
        assertEquals(1, h.recordCount())
        assertEquals(1, counters.lastRecordCount)
        assertEquals(0, h.sweepExpired())
    }

    @Test
    fun `ping returns configured nodeId`() {
        val response = handler().handle(
            DhtRpcRequest.newBuilder().setPing(PingRequest.newBuilder()).build(),
        )
        assertArrayEquals(nodeId, response.ping.nodeId.toByteArray())
    }

    @Test
    fun `findNode returns self with advertised url and peer nodes`() {
        val response = handler(peerNodes = listOf(PEER)).handle(
            DhtRpcRequest.newBuilder().setFindNode(FindNodeRequest.newBuilder()).build(),
        )
        val nodes = response.findNode.nodesList
        assertEquals(listOf(ADVERTISED, PEER), nodes.map { it.address })
        assertArrayEquals(nodeId, nodes[0].nodeId.toByteArray())
        assertEquals(32, nodes[1].nodeId.size())
        assertFalse(nodes[1].nodeId == nodes[0].nodeId)
    }

    @Test
    fun `legacy constructor keeps host-port address and fixed node id`() {
        val response = DhtRequestHandler(port = 8443, publicHost = "relay.example").handle(
            DhtRpcRequest.newBuilder().setFindNode(FindNodeRequest.newBuilder()).build(),
        )
        val self = response.findNode.nodesList.single()
        assertEquals("relay.example:8443", self.address)
        assertArrayEquals(DhtRequestHandler.legacyNodeId(), self.nodeId.toByteArray())
    }

    private companion object {
        const val ADVERTISED = "wss://relay.example/dht"
        const val PEER = "wss://peer.example/dht"
    }
}
