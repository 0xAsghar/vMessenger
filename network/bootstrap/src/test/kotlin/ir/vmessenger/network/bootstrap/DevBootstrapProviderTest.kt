package ir.vmessenger.network.bootstrap

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.NetworkConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevBootstrapProviderTest {
    private val provider = DevBootstrapProvider()

    @After
    fun reset() {
        NetworkConfig.useDevBootstrap = false
    }

    private fun addresses() = runBlocking { (provider.nodes() as AppResult.Success).data.map { it.address } }

    @Test
    fun `the app contributes no bootstrap node of its own`() {
        assertTrue(addresses().isEmpty())
    }

    @Test
    fun `a debug build that asks for the developer bootstrap gets it`() {
        NetworkConfig.useDevBootstrap = true
        assertEquals(listOf(NetworkConfig.DEV_BOOTSTRAP_ADDRESS), addresses())
    }
}
