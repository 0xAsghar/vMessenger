package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The buzzer's own policy branch, which is stricter than every other envelope kind: asking someone
 * to reveal where they are needs a *verified* contact. Enforced on the receiving side, so a sender
 * whose own UI skipped the check is refused on arrival rather than trusted.
 */
class GpsBuzzerPolicyTest {

    @Test
    fun `a verified approved contact may ask`() {
        assertTrue(InboundPolicy.allows(contact(verified = true), InboundKind.GPS_BUZZER))
    }

    @Test
    fun `an unverified contact may not ask, but may still chat`() {
        val unverified = contact(verified = false)

        assertFalse(InboundPolicy.allows(unverified, InboundKind.GPS_BUZZER))
        // The point of the branch: stricter than the ordinary traffic tier, not a blanket block.
        assertTrue(InboundPolicy.allows(unverified, InboundKind.CHAT))
    }

    @Test
    fun `a blocked or unapproved contact may not ask`() {
        assertFalse(InboundPolicy.allows(contact(verified = true, blocked = true), InboundKind.GPS_BUZZER))
        assertFalse(
            InboundPolicy.allows(
                contact(verified = true, status = ContactRelationshipStatus.PENDING_IN),
                InboundKind.GPS_BUZZER,
            ),
        )
    }

    @Test
    fun `a stranger may not ask`() {
        assertFalse(InboundPolicy.allows(null, InboundKind.GPS_BUZZER))
    }

    private fun contact(
        verified: Boolean,
        blocked: Boolean = false,
        status: ContactRelationshipStatus = ContactRelationshipStatus.APPROVED,
    ) = ContactEntity(
        id = "c1",
        identityHash = ByteArray(KEY_SIZE),
        ed25519Public = ByteArray(KEY_SIZE),
        x25519StaticPublic = ByteArray(KEY_SIZE),
        userHash = "vm2-test",
        displayName = "Peer",
        verified = verified,
        blocked = blocked,
        relationshipStatus = status,
        createdAtUnixMs = 0L,
        lastSeenUnixMs = null,
    )

    private companion object {
        const val KEY_SIZE = 32
    }
}
