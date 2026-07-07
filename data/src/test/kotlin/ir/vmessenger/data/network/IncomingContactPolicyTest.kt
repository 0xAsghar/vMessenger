package ir.vmessenger.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingContactPolicyTest {
    @Test
    fun strangerContactIdIsStableHex() {
        val hash = byteArrayOf(0x01, 0x02, 0x03)
        val id = ContactRequestHandler.strangerContactId(hash)
        assertTrue(id.startsWith("stranger:"))
        assertTrue(id.contains("010203"))
    }

    @Test
    fun approvedContactIdsDoNotUseStrangerPrefix() {
        assertFalse("contact-uuid".startsWith("stranger:"))
    }
}
