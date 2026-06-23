package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.dao.KeyMaterialDao
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.KeyMaterialEntity
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val identityDao: IdentityDao,
    private val keyMaterialDao: KeyMaterialDao,
    private val cryptoEngine: CryptoEngine,
    private val keyStoreKeyManager: KeyStoreKeyManager,
) : IdentityRepository {

    override fun observeIdentity(): Flow<Identity?> =
        identityDao.observeIdentity().map { it?.toDomain() }

    override suspend fun getIdentity(): Identity? = identityDao.getIdentity()?.toDomain()

    override suspend fun hasIdentity(): Boolean = identityDao.getIdentity() != null

    override suspend fun generateIdentity(): AppResult<Identity> = runCatching {
        check(!hasIdentity()) { "هویت از قبل وجود دارد" }
        val ed25519 = cryptoEngine.generateEd25519KeyPair()
        val x25519 = cryptoEngine.generateX25519KeyPair()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519.publicKey)
        val userHash = UserHashEncoder.encode(identityHash)
        val now = System.currentTimeMillis()
        val entity = IdentityEntity(
            ed25519Public = ed25519.publicKey,
            identityHash = identityHash,
            userHash = userHash,
            x25519StaticPublic = x25519.publicKey,
            createdAtUnixMs = now,
        )
        identityDao.insertIdentity(entity)
        keyMaterialDao.insert(
            KeyMaterialEntity(
                alias = ALIAS_ED25519,
                wrappedPrivateKey = keyStoreKeyManager.wrapPrivateKey(ALIAS_ED25519, ed25519.privateKey),
                updatedAtUnixMs = now,
            ),
        )
        keyMaterialDao.insert(
            KeyMaterialEntity(
                alias = ALIAS_X25519,
                wrappedPrivateKey = keyStoreKeyManager.wrapPrivateKey(ALIAS_X25519, x25519.privateKey),
                updatedAtUnixMs = now,
            ),
        )
        entity.toDomain()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(AppError.Crypto(it.message ?: "خطا در ایجاد هویت")) },
    )

    override suspend fun getEd25519PrivateKey(): ByteArray? =
        keyMaterialDao.getByAlias(ALIAS_ED25519)?.let {
            keyStoreKeyManager.unwrapPrivateKey(ALIAS_ED25519, it.wrappedPrivateKey)
        }

    override suspend fun getX25519StaticPrivateKey(): ByteArray? =
        keyMaterialDao.getByAlias(ALIAS_X25519)?.let {
            keyStoreKeyManager.unwrapPrivateKey(ALIAS_X25519, it.wrappedPrivateKey)
        }

    override suspend fun wipeIdentity() {
        identityDao.deleteAll()
        keyMaterialDao.deleteAll()
    }

    private fun IdentityEntity.toDomain() = Identity(
        ed25519PublicKey = ed25519Public,
        identityHash = identityHash,
        userHash = userHash,
        x25519StaticPublicKey = x25519StaticPublic,
        createdAtUnixMs = createdAtUnixMs,
    )

    companion object {
        private const val ALIAS_ED25519 = "identity-ed25519"
        private const val ALIAS_X25519 = "identity-x25519-static"
    }
}
