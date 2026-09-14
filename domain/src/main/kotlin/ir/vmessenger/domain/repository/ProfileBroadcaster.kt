package ir.vmessenger.domain.repository

/**
 * Pushes this device's profile to every approved contact.
 *
 * A port rather than a direct call, because the domain layer must not reach into the wire
 * protocol — the same shape the location service control and the read-receipt policy already use.
 */
interface ProfileBroadcaster {
    /**
     * Queues the current display name and avatar, at a fresh revision, to every approved contact
     * we hold a conversation with. Queued rather than sent: the contacts most in need of the
     * update are the ones who are not online at the moment it happens.
     */
    suspend fun broadcastProfile()
}
