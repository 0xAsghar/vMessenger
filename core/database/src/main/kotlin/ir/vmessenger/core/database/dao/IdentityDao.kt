package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.KeyMaterialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE id = 0 LIMIT 1")
    fun observeIdentity(): Flow<IdentityEntity?>

    @Query("SELECT * FROM identity WHERE id = 0 LIMIT 1")
    suspend fun getIdentity(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(entity: IdentityEntity)

    @Query("DELETE FROM identity")
    suspend fun deleteAll()
}

@Dao
interface KeyMaterialDao {
    @Query("SELECT * FROM key_material WHERE alias = :alias LIMIT 1")
    suspend fun getByAlias(alias: String): KeyMaterialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KeyMaterialEntity)

    @Query("DELETE FROM key_material")
    suspend fun deleteAll()
}
