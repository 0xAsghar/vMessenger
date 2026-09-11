package ir.vmessenger.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConfigTest {

    @Test
    fun `defaults when env is empty`() {
        val config = NodeConfig.fromEnv(emptyMap())

        assertEquals(NodeConfig(), config)
        assertEquals(8443, config.port)
        assertEquals("relay.vmessenger.ir", config.publicHost)
        assertEquals("wss://relay.vmessenger.ir/dht", config.advertisedDhtUrl)
        assertTrue(config.peerNodes.isEmpty())
        assertEquals("./state", config.stateDir)
        assertFalse(config.trustProxy)
        assertEquals(20_000, config.maxListeners)
        assertEquals(64, config.maxListenersPerIp)
        assertEquals(5_000, config.maxPendingDialers)
        assertEquals(8, config.maxPendingPerListener)
        assertEquals(30_000L, config.pendingDialerTtlMs)
        assertEquals(300_000L, config.proofMaxSkewMs)
        assertEquals(100_000, config.maxRecords)
        assertEquals(86_400_000L, config.maxRecordTtlMs)
        assertEquals(10_000, config.maxCircuits)
        assertEquals(600_000L, config.circuitIdleTimeoutMs)
        assertEquals(30, config.dialRatePerMin)
        assertEquals(10, config.dialBurst)
        assertEquals(60, config.storeRatePerMin)
        assertEquals(20, config.storeBurst)
        assertEquals(1_048_576L, config.wsMaxFrameBytes)
        assertEquals(30_000L, config.wsPingPeriodMs)
        assertEquals(60_000L, config.wsTimeoutMs)
    }

    @Test
    fun `env overrides are applied`() {
        val config = NodeConfig.fromEnv(
            mapOf(
                "VMESSENGER_NODE_PORT" to "9000",
                "VMESSENGER_PUBLIC_HOST" to "node.example.org",
                "VMESSENGER_PEER_NODES" to "wss://a.example.org/dht, wss://b.example.org/dht,,",
                "VMESSENGER_STATE_DIR" to "/var/lib/vmessenger",
                "VMESSENGER_TRUST_PROXY" to "1",
                "VMESSENGER_MAX_LISTENERS" to "5",
                "VMESSENGER_PROOF_MAX_SKEW_MS" to "1000",
                "VMESSENGER_WS_MAX_FRAME_BYTES" to "2048",
            ),
        )

        assertEquals(9000, config.port)
        assertEquals("node.example.org", config.publicHost)
        assertEquals("wss://node.example.org/dht", config.advertisedDhtUrl)
        assertEquals(listOf("wss://a.example.org/dht", "wss://b.example.org/dht"), config.peerNodes)
        assertEquals("/var/lib/vmessenger", config.stateDir)
        assertTrue(config.trustProxy)
        assertEquals(5, config.maxListeners)
        assertEquals(1000L, config.proofMaxSkewMs)
        assertEquals(2048L, config.wsMaxFrameBytes)
    }

    @Test
    fun `explicit advertised url wins over public host`() {
        val config = NodeConfig.fromEnv(
            mapOf(
                "VMESSENGER_PUBLIC_HOST" to "node.example.org",
                "VMESSENGER_ADVERTISED_DHT_URL" to "ws://10.0.0.1:8443/dht",
            ),
        )
        assertEquals("ws://10.0.0.1:8443/dht", config.advertisedDhtUrl)
    }

    @Test
    fun `trust proxy accepts true and rejects other values`() {
        assertTrue(NodeConfig.fromEnv(mapOf("VMESSENGER_TRUST_PROXY" to "true")).trustProxy)
        assertTrue(NodeConfig.fromEnv(mapOf("VMESSENGER_TRUST_PROXY" to " TRUE ")).trustProxy)
        assertFalse(NodeConfig.fromEnv(mapOf("VMESSENGER_TRUST_PROXY" to "0")).trustProxy)
        assertFalse(NodeConfig.fromEnv(mapOf("VMESSENGER_TRUST_PROXY" to "yes")).trustProxy)
    }

    @Test
    fun `bad numbers fall back to defaults`() {
        val config = NodeConfig.fromEnv(
            mapOf(
                "VMESSENGER_NODE_PORT" to "eighty",
                "VMESSENGER_MAX_LISTENERS" to "-3",
                "VMESSENGER_PROOF_MAX_SKEW_MS" to "",
                "VMESSENGER_MAX_RECORDS" to "0",
                "VMESSENGER_WS_TIMEOUT_MS" to "99999999999999999999",
            ),
        )

        assertEquals(NodeConfig.DEFAULT_PORT, config.port)
        assertEquals(NodeConfig.DEFAULT_MAX_LISTENERS, config.maxListeners)
        assertEquals(NodeConfig.DEFAULT_PROOF_MAX_SKEW_MS, config.proofMaxSkewMs)
        assertEquals(NodeConfig.DEFAULT_MAX_RECORDS, config.maxRecords)
        assertEquals(NodeConfig.DEFAULT_WS_TIMEOUT_MS, config.wsTimeoutMs)
    }

    @Test
    fun `describe lists every tunable without peer urls`() {
        val config = NodeConfig.fromEnv(mapOf("VMESSENGER_PEER_NODES" to "wss://secret.example.org/dht"))
        val line = config.describe()

        assertTrue(line.contains("port=8443"))
        assertTrue(line.contains("peerNodes=1"))
        assertFalse(line.contains("secret.example.org"))
        assertTrue(line.contains("wsTimeoutMs=60000"))
    }
}
