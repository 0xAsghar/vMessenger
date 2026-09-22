package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.database.entity.GroupMemberRole
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // one query per thing the group screens and the control handler ask
interface GroupDao {
    /**
     * Inserts a group we do not have yet. IGNORE, not REPLACE: `INSERT OR REPLACE` deletes
     * the existing row first, which cascades through `conversation` to every message in the
     * group. A re-sent snapshot would then silently erase the whole history — use [update]
     * to change a group that already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: GroupEntity)

    @Update
    suspend fun update(group: GroupEntity)

    @Query("SELECT * FROM chat_group WHERE id = :groupId LIMIT 1")
    suspend fun getById(groupId: String): GroupEntity?

    @Query("SELECT * FROM chat_group WHERE id = :groupId LIMIT 1")
    fun observe(groupId: String): Flow<GroupEntity?>

    @Query("UPDATE chat_group SET name = :name, version = :version WHERE id = :groupId")
    suspend fun setName(groupId: String, name: String, version: Long)

    @Query("UPDATE chat_group SET version = :version WHERE id = :groupId")
    suspend fun setVersion(groupId: String, version: Long)

    /** Set when the creator closes the group, and when we are the target of a REMOVE. */
    @Query("UPDATE chat_group SET closed = :closed WHERE id = :groupId")
    suspend fun setClosed(groupId: String, closed: Boolean)

    /** Creator-only; see [ir.vmessenger.core.database.entity.GroupEntity.auditRetention]. */
    @Query("UPDATE chat_group SET auditRetention = :enabled, version = :version WHERE id = :groupId")
    suspend fun setAuditRetention(groupId: String, enabled: Boolean, version: Long)

    @Query("UPDATE chat_group_member SET role = :role WHERE groupId = :groupId AND identityHash = :identityHash")
    suspend fun setMemberRole(groupId: String, identityHash: String, role: GroupMemberRole)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    /** Members that have not left or been removed, creator first then by name. */
    @Query(
        """
        SELECT * FROM chat_group_member
        WHERE groupId = :groupId AND removedAtUnixMs IS NULL
        ORDER BY role ASC, displayName COLLATE NOCASE ASC
        """,
    )
    fun observeActiveMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM chat_group_member WHERE groupId = :groupId AND removedAtUnixMs IS NULL")
    suspend fun activeMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM chat_group_member WHERE groupId = :groupId AND identityHash = :identityHash LIMIT 1")
    suspend fun member(groupId: String, identityHash: String): GroupMemberEntity?

    /**
     * Tombstones a member instead of deleting the row, so a control message that
     * arrives after they left is recognised rather than silently re-adding them.
     */
    @Query(
        """
        UPDATE chat_group_member SET removedAtUnixMs = :atUnixMs
        WHERE groupId = :groupId AND identityHash = :identityHash AND removedAtUnixMs IS NULL
        """,
    )
    suspend fun markRemoved(groupId: String, identityHash: String, atUnixMs: Long)

    /**
     * Replaces the whole membership from a creator snapshot: every current member
     * is tombstoned first, then the snapshot is written back (which revives anyone
     * still in it, because the insert replaces the row with `removedAtUnixMs` null).
     */
    @Transaction
    suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>, atUnixMs: Long) {
        markAllRemoved(groupId, atUnixMs)
        upsertMembers(members)
    }

    @Query("UPDATE chat_group_member SET removedAtUnixMs = :atUnixMs WHERE groupId = :groupId")
    suspend fun markAllRemoved(groupId: String, atUnixMs: Long)

    /** Erases the group with its members (the conversation cascades from the FK). */
    @Query("DELETE FROM chat_group WHERE id = :groupId")
    suspend fun deleteById(groupId: String)
}
