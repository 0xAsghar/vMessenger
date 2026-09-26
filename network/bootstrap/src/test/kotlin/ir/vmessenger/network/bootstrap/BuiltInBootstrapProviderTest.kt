package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInBootstrapProviderTest {
    private val provider = BuiltInBootstrapProvider()

    @After
    fun reset() {
        P2PConfig.multiNodeEnabled = P2PConfig.DEFAULT_MULTI_NODE
        NetworkConfig.useDevBootstrap = false
    }

    private fun addresses() = runBlocking { (provider.nodes() as AppResult.Success).data.map { it.address } }

    @Test
    fun `in multi-node mode the node list alone decides, so a switched-off built-in node stays off`() {
        P2PConfig.multiNodeEnabled = true
        assertTrue(addresses().isEmpty())
    }

    @Test
    fun `the legacy single-node mode and the developer override still use it`() {
        P2PConfig.multiNodeEnabled = false
        assertEquals(listOf(NetworkConfig.DEFAULT_DHT_URL), addresses())
        P2PConfig.multiNodeEnabled = true
        NetworkConfig.useDevBootstrap = true
        assertEquals(listOf(NetworkConfig.DEV_BOOTSTRAP_ADDRESS), addresses())
    }
}
