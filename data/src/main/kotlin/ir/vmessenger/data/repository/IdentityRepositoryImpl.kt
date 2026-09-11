package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.dao.KeyMaterialDao
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.KeyMaterialEntity
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

// Single owner of the identity lifecycle (generate, import, rename, wipe, key access) and its Keystore wrapping.
@Suppress("TooManyFunctions")
@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val identityDao: IdentityDao,
    private val keyMaterialDao: KeyMaterialDao,
    private val cryptoEngine: CryptoEngine,
    private val keyStoreKeyManager: KeyStoreKeyManager,
) : IdentityRepository {

    override fun observeIdentity(): Flow<Identity?> =
        identityDao.observeIdentity().mapLatest { entity ->
            entity?.let { migrateUserHashIfNeeded(it) }?.toDomain()
        }

    override suspend fun getIdentity(): Identity? =
        identityDao.getIdentity()?.let { migrateUserHashIfNeeded(it) }?.toDomain()

    override suspend fun hasIdentity(): Boolean = identityDao.getIdentity() != null

    override suspend fun generateIdentity(displayName: String): AppResult<Identity> = runCatching {
        check(!hasIdentity()) { "هویت از قبل وجود دارد" }
        val trimmed = displayName.trim()
        check(trimmed.length in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            "نام باید بین $DISPLAY_NAME_MIN تا $DISPLAY_NAME_MAX کاراکتر باشد"
        }
        val ed25519 = cryptoEngine.generateEd25519KeyPair()
        val x25519 = cryptoEngine.generateX25519KeyPair()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519.publicKey)
        val userHash = UserHashEncoder.encode(identityHash)
        val now = System.currentTimeMillis()
        val entity = IdentityEntity(
            ed25519Public = ed25519.publicKey,
            identityHash = identityHash,
            userHash = userHash,
            displayName = trimmed,
            x25519StaticPublic = x25519.publicKey,
            createdAtUnixMs = now,
        )
        identityDao.insertIdentity(entity)
        wrapKeyMaterial(ed25519.privateKey, x25519.privateKey, now).forEach { keyMaterialDao.insert(it) }
        entity.toDomain()
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(AppError.Crypto(it.message ?: "خطا در ایجاد هویت")) },
    )

    @Suppress("LongParameterList")
    override suspend fun importIdentity(
        ed25519Public: ByteArray,
        ed25519Private: ByteArray,
        x25519StaticPublic: ByteArray,
        x25519StaticPrivate: ByteArray,
        displayName: String,
        createdAtUnixMs: Long,
    ): AppResult<Identity> = runCatching {
        require(!hasIdentity()) { "هویت از قبل وجود دارد" }
        val trimmed = displayName.trim()
        require(trimmed.length in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            "نام باید بین $DISPLAY_NAME_MIN تا $DISPLAY_NAME_MAX کاراکتر باشد"
        }
        require(cryptoEngine.ed25519PublicFromPrivate(ed25519Private).contentEquals(ed25519Public)) {
            "کلید عمومی هویت با کلید خصوصی آن مطابقت ندارد"
        }
        require(cryptoEngine.x25519PublicFromPrivate(x25519StaticPrivate).contentEquals(x25519StaticPublic)) {
            "کلید عمومی X25519 با کلید خصوصی آن مطابقت ندارد"
        }
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519Public)
        val now = System.currentTimeMillis()
        // Wrap before touching the database so a Keystore failure leaves no half-installed identity.
        val keyMaterial = wrapKeyMaterial(ed25519Private, x25519StaticPrivate, now)
        val entity = IdentityEntity(
            ed25519Public = ed25519Public.copyOf(),
            identityHash = identityHash,
            userHash = UserHashEncoder.encode(identityHash),
            displayName = trimmed,
            x25519StaticPublic = x25519StaticPublic.copyOf(),
            createdAtUnixMs = createdAtUnixMs.takeIf { it > 0 } ?: now,
        )
        identityDao.insertIdentity(entity)
        keyMaterial.forEach { keyMaterialDao.insert(it) }
        AppLogger.info("Identity", "identity imported from backup")
        entity.toDomain()
    }.also {
        cryptoEngine.memzero(ed25519Private)
        cryptoEngine.memzero(x25519StaticPrivate)
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { failure ->
            val message = failure.message ?: "بازیابی هویت ناموفق بود"
            AppResult.Error(
                if (failure is IllegalArgumentException) AppError.Validation(message) else AppError.Crypto(message),
            )
        },
    )

    override suspend fun updateDisplayName(displayName: String): AppResult<Unit> = runCatching {
        val trimmed = displayName.trim()
        check(trimmed.length in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            "نام باید بین $DISPLAY_NAME_MIN تا $DISPLAY_NAME_MAX کاراکتر باشد"
        }
        val entity = identityDao.getIdentity() ?: error("هویت یافت نشد")
        identityDao.insertIdentity(entity.copy(displayName = trimmed))
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error(AppError.Validation(it.message ?: "به‌روزرسانی نام ناموفق بود")) },
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

    /** Keystore-wraps both private keys; the plaintext inputs are left untouched for the caller to zeroize. */
    private fun wrapKeyMaterial(
        ed25519Private: ByteArray,
        x25519Private: ByteArray,
        now: Long,
    ): List<KeyMaterialEntity> = listOf(
        KeyMaterialEntity(
            alias = ALIAS_ED25519,
            wrappedPrivateKey = keyStoreKeyManager.wrapPrivateKey(ALIAS_ED25519, ed25519Private),
            updatedAtUnixMs = now,
        ),
        KeyMaterialEntity(
            alias = ALIAS_X25519,
            wrappedPrivateKey = keyStoreKeyManager.wrapPrivateKey(ALIAS_X25519, x25519Private),
            updatedAtUnixMs = now,
        ),
    )

    private suspend fun migrateUserHashIfNeeded(entity: IdentityEntity): IdentityEntity {
        val fixed = UserHashEncoder.encode(entity.identityHash)
        if (fixed == entity.userHash) return entity
        val updated = entity.copy(userHash = fixed)
        identityDao.insertIdentity(updated)
        AppLogger.info("Identity", "migrated userHash to decodable format")
        return updated
    }

    private fun IdentityEntity.toDomain() = Identity(
        ed25519PublicKey = ed25519Public,
        identityHash = identityHash,
        userHash = userHash,
        displayName = displayName,
        x25519StaticPublicKey = x25519StaticPublic,
        createdAtUnixMs = createdAtUnixMs,
    )

    companion object {
        private const val ALIAS_ED25519 = "identity-ed25519"
        private const val ALIAS_X25519 = "identity-x25519-static"
        const val DISPLAY_NAME_MIN = 2
        const val DISPLAY_NAME_MAX = 32
    }
}
