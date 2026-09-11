package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Identity
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeIdentity(): Flow<Identity?>
    suspend fun getIdentity(): Identity?
    suspend fun hasIdentity(): Boolean
    suspend fun generateIdentity(displayName: String): AppResult<Identity>

    /**
     * Installs a previously exported identity (backup restore). Refuses with `AppError.Validation` when an
     * identity already exists or the key material is inconsistent. `identityHash` and `userHash` are
     * recomputed from [ed25519Public]. Takes ownership of the private key arrays and zeroizes them before
     * returning, whether or not the import succeeded.
     */
    @Suppress("LongParameterList")
    suspend fun importIdentity(
        ed25519Public: ByteArray,
        ed25519Private: ByteArray,
        x25519StaticPublic: ByteArray,
        x25519StaticPrivate: ByteArray,
        displayName: String,
        createdAtUnixMs: Long,
    ): AppResult<Identity>

    suspend fun updateDisplayName(displayName: String): AppResult<Unit>
    suspend fun getEd25519PrivateKey(): ByteArray?
    suspend fun getX25519StaticPrivateKey(): ByteArray?
    suspend fun wipeIdentity()
}
