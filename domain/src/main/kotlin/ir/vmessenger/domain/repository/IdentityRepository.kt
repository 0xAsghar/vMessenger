package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Identity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeIdentity(): Flow<Identity?>
    suspend fun getIdentity(): Identity?
    suspend fun hasIdentity(): Boolean
    suspend fun generateIdentity(): AppResult<Identity>
    suspend fun getEd25519PrivateKey(): ByteArray?
    suspend fun getX25519StaticPrivateKey(): ByteArray?
    suspend fun wipeIdentity()
}
