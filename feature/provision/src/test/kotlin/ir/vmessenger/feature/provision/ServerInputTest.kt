package ir.vmessenger.feature.provision

import ir.vmessenger.core.nodesetup.IssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInputTest {
    @Test
    fun `Persian digits and bidi marks become a plain address`() {
        assertEquals("203.0.113.10", ServerInput.clean("‏۲۰۳.۰.۱۱۳.۱۰ "))
        assertEquals(2222, ServerInput.port("۲۲۲۲"))
    }

    @Test
    fun `user at host typed into the host field splits`() {
        assertEquals("203.0.113.10" to "alice", ServerInput.split("alice@203.0.113.10", "root"))
        assertEquals("example.com" to "root", ServerInput.split("ssh://example.com", "root"))
    }

    @Test
    fun `what a shell would not take is refused before it gets there`() {
        assertFalse(ServerInput.hostOk("1.2.3.4; rm -rf /"))
        assertFalse(ServerInput.userOk("root'"))
        assertNull(ServerInput.port("70000"))
        assertFalse(ServerInput.domainOk("not a domain"))
        assertTrue(ServerInput.emailOk(""))
        assertFalse(ServerInput.emailOk("a'b@c.d"))
    }

    @Test
    fun `every issue code has its own words or its family's`() {
        val unknown = ProvisionIssueText.of(null)
        IssueCode.entries.forEach { assertNotEquals(it.name, unknown, ProvisionIssueText.of(it)) }
    }
}
