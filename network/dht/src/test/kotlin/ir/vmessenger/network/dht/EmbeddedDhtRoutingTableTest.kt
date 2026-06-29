package ir.vmessenger.network.dht

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedDhtRoutingTableTest {
    private val localId = ByteArray(32) { 7 }
    private val table = EmbeddedDhtRoutingTable(localId)

    @Test
    fun insertsAndFindsClosestNodes() {
        val node = DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(ByteArray(32) { 1 }))
            .setAddress("10.0.0.1:9000")
            .build()
        table.insert(node)
        assertEquals(1, table.nodeCount())
        val closest = table.findClosest(ByteArray(32) { 1 })
        assertTrue(closest.isNotEmpty())
        assertEquals("10.0.0.1:9000", closest.first().address)
    }
}
