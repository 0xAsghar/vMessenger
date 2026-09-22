package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.model.GroupAuditEntry
import ir.vmessenger.domain.model.GroupMember
import kotlinx.coroutines.flow.Flow

/**
 * Group membership and its control-message side of the wire.
 *
 * Every mutating method here is creator-only except [leaveGroup]; the
 * implementation enforces that rather than trusting the caller, because a
 * control message from a non-creator is exactly what an attacker would send.
 */
@Suppress("TooManyFunctions") // one method per membership operation; grouping them would only hide the surface
interface GroupRepository {
    fun observeGroup(groupId: String): Flow<Group?>

    fun observeMembers(groupId: String): Flow<List<GroupMember>>

    /**
     * The revisions this device captured for [groupId] while retention was on, newest first.
     *
     * Empty when retention was never on, and — because switching it off erases what was kept —
     * empty again afterwards. Local only; see [ir.vmessenger.domain.model.GroupAuditEntry].
     */
    fun observeAuditEntries(groupId: String): Flow<List<GroupAuditEntry>>

    suspend fun getGroup(groupId: String): Group?

    /**
     * Creates the group locally, fans a CREATE control out to every member and
     * returns the id of its conversation.
     */
    suspend fun createGroup(name: String, memberContactIds: List<String>): AppResult<String>

    suspend fun renameGroup(groupId: String, name: String): AppResult<Unit>

    suspend fun addMembers(groupId: String, memberContactIds: List<String>): AppResult<Unit>

    suspend fun removeMember(groupId: String, identityHash: String): AppResult<Unit>

    /** Leaves the group: announces the departure, then makes the local copy read-only. */
    suspend fun leaveGroup(groupId: String): AppResult<Unit>

    /** Creator-only: closes the group for everyone. */
    suspend fun closeGroup(groupId: String): AppResult<Unit>

    /**
     * Switches admin review of edited and deleted messages on or off. Creator only.
     *
     * Turning it on changes what the group keeps, so every member is told: a system line lands in
     * the conversation and a banner stays on the group screen for as long as it is on. Turning it
     * off also erases what was kept — a policy that ended should not leave a stockpile behind.
     */
    suspend fun setAuditRetention(groupId: String, enabled: Boolean): AppResult<Unit>

    /** Promotes or demotes one member. Creator only, and never the creator's own row. */
    suspend fun setMemberAdmin(groupId: String, identityHash: String, admin: Boolean): AppResult<Unit>

    /**
     * Sends a contact request to a member we do not have yet.
     *
     * Sharing a group is not consent to a private chat, so this goes through the
     * ordinary request flow (the member stays PENDING_OUT until they accept)
     * rather than silently creating an approved contact.
     */
    suspend fun addMemberAsContact(groupId: String, identityHash: String): AppResult<Unit>
}
