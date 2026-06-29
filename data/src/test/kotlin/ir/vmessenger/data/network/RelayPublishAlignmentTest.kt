package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.RelaySource
import ir.vmessenger.core.common.network.SelectedRelay
import ir.vmessenger.core.common.network.TransportIds
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayPublishAlignmentTest {
    private val default = NetworkConfig.DEFAULT_RELAY_URL

    @Test
    fun publishedRelayEndpointMatchesSelectedRelay() {
        val ranked = listOf("wss://node-a/relay", "wss://node-b/relay")
        val selectedUrl = selectActiveRelay(ranked, default)
        val selected = SelectedRelay(url = selectedUrl, source = RelaySource.RANKED)

        val publishedRelay = Endpoint(transport = TransportIds.RELAY, address = selected.url)
        val listenerUrl = selected.url

        assertEquals(listenerUrl, publishedRelay.address)
        assertEquals("wss://node-a/relay", selected.url)
    }

    @Test
    fun defaultRelayAlignmentWhenListEmpty() {
        val selectedUrl = selectActiveRelay(emptyList(), default)
        val selected = SelectedRelay(url = selectedUrl, source = RelaySource.DEFAULT)
        val published = Endpoint(transport = TransportIds.RELAY, address = selected.url)
        assertEquals(default, published.address)
        assertEquals(default, selected.url)
    }
}
