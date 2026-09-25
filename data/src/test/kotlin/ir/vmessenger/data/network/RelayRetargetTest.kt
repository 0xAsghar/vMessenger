package ir.vmessenger.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetargetTest {
    @Test
    fun `a listener on another relay than the published one re-publishes`() {
        assertTrue(shouldRetarget(published = "wss://a/relay", connected = "wss://b/relay#pin-sha256=x"))
        assertTrue(shouldRetarget(published = null, connected = "wss://a/relay"))
    }

    @Test
    fun `the same relay, or none between sessions, does not`() {
        assertFalse(shouldRetarget(published = "wss://a/relay", connected = "wss://a/relay"))
        assertFalse(shouldRetarget(published = "wss://a/relay", connected = null))
    }
}
