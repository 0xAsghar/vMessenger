package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.data.activity.testActivityLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySelectionTest {
    private val default = "wss://relay.vmessenger.ir/relay"

    @Test
    fun picksHealthiestRankedRelayFirst() {
        val ranked = listOf("wss://node-a/relay", "wss://node-b/relay")
        assertEquals("wss://node-a/relay", selectActiveRelay(ranked, default))
    }

    @Test
    fun fallsBackOnlyWhenAFallbackIsGiven() {
        // The legacy single-node mode passes the built-in relay as the fallback...
        assertEquals(default, selectActiveRelay(emptyList(), default))
        // ...multi-node mode passes none: with every relay switched off there is no relay.
        assertNull(selectActiveRelay(emptyList(), null))
    }

    @Test
    fun switchingOffTheBuiltInRelayLeavesNoRelay() = runTest {
        val relayDao = FakeRelayNodeDao()
        val repo = NetworkNodeRepository(FakeBootstrapNodeDao(), relayDao, testActivityLogger()) {
            NodeAddressPolicy.RELEASE
        }
        repo.seedDefaults()
        val directory = RelayDirectoryImpl(repo)
        assertEquals(NetworkConfig.DEFAULT_RELAY_URL, directory.activeRelay()?.url)

        repo.setRelayEnabled(NetworkConfig.DEFAULT_RELAY_URL, enabled = false)
        assertNull("the built-in relay must not come back as a fallback", directory.activeRelay())
        assertTrue(NetworkConfig.relayFallbackEndpoints().isEmpty())

        repo.addNode("wss://relay.example.org/relay", ir.vmessenger.domain.model.NetworkNodeRole.RELAY)
        assertEquals("wss://relay.example.org/relay", directory.activeRelay()?.url)
    }

    @Test
    fun communityRelayIgnoredWhenDisabled() = runTest {
        val relayDao = FakeRelayNodeDao()
        val repo = NetworkNodeRepository(FakeBootstrapNodeDao(), relayDao, testActivityLogger()) {
            NodeAddressPolicy.RELEASE
        }
        repo.seedDefaults()
        // A peer advertises a relay: stored as community/disabled, so it never becomes the active relay...
        repo.importExchangedNodes(emptyList(), listOf("wss://evil.example/relay"))
        assertEquals(NetworkConfig.DEFAULT_RELAY_URL, selectActiveRelay(repo.enabledRelayUrls(), default))
        // ...and one failure of the built-in relay does not switch either.
        repo.recordRelayResult(NetworkConfig.DEFAULT_RELAY_URL, ok = false)
        assertEquals(NetworkConfig.DEFAULT_RELAY_URL, selectActiveRelay(repo.enabledRelayUrls(), default))
        // Only after the user enables it does it become a (lower-priority) candidate.
        repo.setRelayEnabled("wss://evil.example/relay", enabled = true)
        assertEquals(
            listOf(NetworkConfig.DEFAULT_RELAY_URL, "wss://evil.example/relay"),
            repo.enabledRelayUrls(),
        )
    }
}
