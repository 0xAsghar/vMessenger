package ir.vmessenger.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySelectionTest {
    private val default = "wss://relay.vmessenger.ir/relay"

    @Test
    fun picksHealthiestRankedRelayFirst() {
        val ranked = listOf("wss://node-a/relay", "wss://node-b/relay")
        assertEquals("wss://node-a/relay", selectActiveRelay(ranked, default))
    }

    @Test
    fun fallsBackToDefaultWhenNoRelaysAvailable() {
        assertEquals(default, selectActiveRelay(emptyList(), default))
    }
}
