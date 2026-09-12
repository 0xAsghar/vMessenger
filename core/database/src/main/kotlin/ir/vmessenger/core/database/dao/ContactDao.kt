package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // one query per contact access path; splitting the DAO would scatter the table
interface ContactDao {
    @Query("SELECT * FROM contact WHERE blocked = 0 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeContacts(): Flow<List<ContactEntity>>

    /** The rows [observeContacts] hides, for the "blocked contacts" screen that undoes a block. */
    @Query("SELECT * FROM contact WHERE blocked = 1 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeBlocked(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contact WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContactEntity?

    @Query("SELECT * FROM contact WHERE identityHash = :identityHash LIMIT 1")
    suspend fun getByIdentityHash(identityHash: ByteArray): ContactEntity?

    @Query("SELECT * FROM contact WHERE ed25519Public = :ed25519Public LIMIT 1")
    suspend fun getByEd25519Public(ed25519Public: ByteArray): ContactEntity?

    @Query("SELECT * FROM contact")
    suspend fun getAll(): List<ContactEntity>

    /** Marks that we just received something from this contact (stops re-requesting). */
    @Query("UPDATE contact SET lastSeenUnixMs = :ts WHERE id = :id")
    suspend fun touchLastSeen(id: String, ts: Long)

    /**
     * Records a static key the peer presented that differs from the pinned one. The handshake
     * that observed it was rejected; the key stays pending until the user accepts the change.
     */
    @Query("UPDATE contact SET pendingX25519StaticPublic = :staticPub, keyChangedAtUnixMs = :ts WHERE id = :id")
    suspend fun recordPendingKeyChange(id: String, staticPub: ByteArray, ts: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ContactEntity)

    @Update
    suspend fun update(entity: ContactEntity)

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM contact")
    suspend fun deleteAll()
}
