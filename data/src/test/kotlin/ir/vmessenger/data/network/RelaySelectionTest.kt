package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.data.activity.testActivityLogger
import ir.vmessenger.domain.model.NetworkNodeRole
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySelectionTest {
    private val repo = NetworkNodeRepository(FakeBootstrapNodeDao(), FakeRelayNodeDao(), testActivityLogger()) {
        NodeAddressPolicy.RELEASE
    }
    private val directory = RelayDirectoryImpl(repo)

    @After
    fun reset() {
        NetworkConfig.relayAddress = ""
        NetworkConfig.rankedRelayUrls = emptyList()
    }

    @Test
    fun anAppWithNoNodeHasNoRelayAndNoFallback() = runTest {
        assertNull("the app ships no relay of its own", directory.activeRelay())
        assertEquals("", NetworkConfig.relayAddress)
        assertTrue(NetworkConfig.relayFallbackEndpoints().isEmpty())
    }

    @Test
    fun picksTheHealthiestRelayThatIsSwitchedOn() = runTest {
        repo.addNode("wss://node-a.example/relay", NetworkNodeRole.RELAY)
        repo.addNode("wss://node-b.example/relay", NetworkNodeRole.RELAY)
        repeat(3) { repo.recordRelayResult("wss://node-a.example/relay", ok = false) }

        assertEquals("wss://node-b.example/relay", directory.activeRelay()?.url)
        assertEquals("wss://node-b.example/relay", NetworkConfig.relayAddress)
    }

    @Test
    fun switchingTheOnlyRelayOffLeavesNone() = runTest {
        repo.addNode("wss://relay.example.org/relay", NetworkNodeRole.RELAY)
        assertEquals("wss://relay.example.org/relay", directory.activeRelay()?.url)

        repo.setRelayEnabled("wss://relay.example.org/relay", enabled = false)
        assertNull(directory.activeRelay())
        assertTrue(NetworkConfig.relayFallbackEndpoints().isEmpty())
    }

    @Test
    fun communityRelayIgnoredUntilSwitchedOn() = runTest {
        repo.addNode("wss://relay.example.org/relay", NetworkNodeRole.RELAY)
        // A peer advertises a relay: stored as community/disabled, so it never becomes the active relay...
        repo.importExchangedNodes(emptyList(), listOf("wss://evil.example/relay"))
        assertEquals("wss://relay.example.org/relay", directory.activeRelay()?.url)
        // ...and one failure of the person's relay does not switch either.
        repo.recordRelayResult("wss://relay.example.org/relay", ok = false)
        assertEquals("wss://relay.example.org/relay", directory.activeRelay()?.url)
        // Only after the person switches it on does it become a (lower-priority) candidate.
        repo.setRelayEnabled("wss://evil.example/relay", enabled = true)
        assertEquals(listOf("wss://relay.example.org/relay", "wss://evil.example/relay"), repo.enabledRelayUrls())
    }
}
