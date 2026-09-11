package ir.vmessenger.data.repository

import com.google.protobuf.InvalidProtocolBufferException
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.backup.BackupBundleCodec
import ir.vmessenger.core.crypto.backup.BackupBundleException
import ir.vmessenger.core.proto.backup.v1.BackupIdentity
import ir.vmessenger.core.proto.backup.v1.BackupPayload
import ir.vmessenger.data.backup.BackgroundBackupCodec
import ir.vmessenger.data.backup.BackupPayloadExporter
import ir.vmessenger.data.backup.BackupRestoreWriter
import ir.vmessenger.data.backup.TransactionRunner
import ir.vmessenger.domain.model.BackupHeaderInfo
import ir.vmessenger.domain.model.BackupOptions
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.domain.repository.IdentityBackupRepository
import ir.vmessenger.domain.repository.IdentityRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Carries an already-classified [AppError] out of a `runCatching` block. */
private class BackupException(val error: AppError) : Exception(error.message)

/** The unwrapped identity secrets an export needs; the arrays are wiped by [IdentityBackupRepositoryImpl]. */
private class IdentitySecrets(
    val identity: Identity,
    val ed25519Private: ByteArray,
    val x25519StaticPrivate: ByteArray,
)

// Orchestrates export/import; payload building and row writes live in ir.vmessenger.data.backup.
// [codec] runs the Argon2id KDF off the caller's dispatcher (the use cases are invoked from Main).
@Singleton
class IdentityBackupRepositoryImpl @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val cryptoEngine: CryptoEngine,
    private val codec: BackgroundBackupCodec,
    private val exporter: BackupPayloadExporter,
    private val writer: BackupRestoreWriter,
    private val transactionRunner: TransactionRunner,
) : IdentityBackupRepository {

    override suspend fun exportBundle(passphrase: CharArray, options: BackupOptions): AppResult<ByteArray> =
        runCatching {
            require(passphrase.size >= BackupBundleCodec.MIN_PASSPHRASE_CHARS) {
                "رمز پشتیبان باید حداقل ${BackupBundleCodec.MIN_PASSPHRASE_CHARS} کاراکتر باشد"
            }
            val secrets = loadIdentitySecrets()
            val payloadBytes = try {
                exporter.build(secrets.identity, secrets.ed25519Private, secrets.x25519StaticPrivate, options)
                    .toByteArray()
            } finally {
                cryptoEngine.memzero(secrets.ed25519Private)
                cryptoEngine.memzero(secrets.x25519StaticPrivate)
            }
            try {
                codec.encode(payloadBytes, passphrase)
            } finally {
                cryptoEngine.memzero(payloadBytes)
            }
        }.fold(
            onSuccess = {
                AppLogger.info(
                    "Backup",
                    "exported bundle bytes=${it.size} conversations=${options.includeConversations}",
                )
                AppResult.Success(it)
            },
            onFailure = {
                AppLogger.warn("Backup", "export failed: ${it.message}")
                AppResult.Error(it.toAppError("پشتیبان‌گیری ناموفق بود"))
            },
        )

    override suspend fun inspectBundle(bytes: ByteArray): AppResult<BackupHeaderInfo> = runCatching {
        val info = codec.inspect(bytes)
        BackupHeaderInfo(version = info.version, kdfOps = info.kdfOps, kdfMemBytes = info.kdfMemBytes)
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(it.toAppError("فایل پشتیبان قابل خواندن نیست")) },
    )

    override suspend fun importBundle(bytes: ByteArray, passphrase: CharArray): AppResult<RestoreSummary> =
        runCatching {
            if (identityRepository.hasIdentity()) {
                throw BackupException(AppError.Validation("هویت از قبل وجود دارد؛ بازیابی فقط روی نصب تازه ممکن است"))
            }
            val payload = parsePayload(codec.decode(bytes, passphrase))
            transactionRunner.inTransaction {
                installIdentity(payload.identity)
                writer.restore(payload)
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppLogger.warn("Backup", "import failed: ${it.message}")
                AppResult.Error(it.toAppError("بازیابی پشتیبان ناموفق بود"))
            },
        )

    private suspend fun loadIdentitySecrets(): IdentitySecrets {
        val identity = identityRepository.getIdentity()
            ?: throw BackupException(AppError.NotFound("هویتی برای پشتیبان‌گیری وجود ندارد"))
        val ed25519Private = identityRepository.getEd25519PrivateKey()
        val x25519StaticPrivate = identityRepository.getX25519StaticPrivateKey()
        if (ed25519Private == null || x25519StaticPrivate == null) {
            ed25519Private?.let(cryptoEngine::memzero)
            x25519StaticPrivate?.let(cryptoEngine::memzero)
            throw BackupException(AppError.Crypto("کلیدهای خصوصی هویت در دسترس نیست"))
        }
        return IdentitySecrets(identity, ed25519Private, x25519StaticPrivate)
    }

    /** Parses and wipes [plaintext]; the returned message keeps immutable key copies until it is dropped. */
    private fun parsePayload(plaintext: ByteArray): BackupPayload {
        val payload = try {
            BackupPayload.parseFrom(plaintext)
        } finally {
            cryptoEngine.memzero(plaintext)
        }
        require(payload.payloadVersion == BackupPayloadExporter.PAYLOAD_VERSION) {
            "نسخهٔ پشتیبان (${payload.payloadVersion}) پشتیبانی نمی‌شود"
        }
        require(payload.hasIdentity()) { "پشتیبان فاقد هویت است" }
        return payload
    }

    /** Validates the key material, then hands the private keys to [IdentityRepository], which wipes them. */
    private suspend fun installIdentity(identity: BackupIdentity) {
        val ed25519Public = identity.ed25519Public.toByteArray()
        val ed25519Private = identity.ed25519Private.toByteArray()
        val x25519Public = identity.x25519StaticPublic.toByteArray()
        val x25519Private = identity.x25519StaticPrivate.toByteArray()
        var handedOff = false
        try {
            validateIdentityKeys(ed25519Public, ed25519Private, x25519Public, x25519Private)
            handedOff = true
            val result = identityRepository.importIdentity(
                ed25519Public = ed25519Public,
                ed25519Private = ed25519Private,
                x25519StaticPublic = x25519Public,
                x25519StaticPrivate = x25519Private,
                displayName = identity.displayName,
                createdAtUnixMs = identity.createdAtUnixMs,
            )
            if (result is AppResult.Error) throw BackupException(result.error)
        } finally {
            if (!handedOff) {
                cryptoEngine.memzero(ed25519Private)
                cryptoEngine.memzero(x25519Private)
            }
        }
    }

    private fun validateIdentityKeys(
        ed25519Public: ByteArray,
        ed25519Private: ByteArray,
        x25519Public: ByteArray,
        x25519Private: ByteArray,
    ) {
        val sizesOk = ed25519Public.size == KEY_SIZE &&
            ed25519Private.size == ED25519_PRIVATE_SIZE &&
            x25519Public.size == KEY_SIZE &&
            x25519Private.size == KEY_SIZE
        require(sizesOk) { "کلیدهای هویت در پشتیبان ناقص است" }
        require(cryptoEngine.ed25519PublicFromPrivate(ed25519Private).contentEquals(ed25519Public)) {
            "کلید عمومی هویت با کلید خصوصی آن مطابقت ندارد"
        }
        require(cryptoEngine.x25519PublicFromPrivate(x25519Private).contentEquals(x25519Public)) {
            "کلید عمومی X25519 با کلید خصوصی آن مطابقت ندارد"
        }
        val probe = cryptoEngine.randomBytes(KEY_SIZE)
        val signature = cryptoEngine.signEd25519(probe, ed25519Private)
        require(cryptoEngine.verifyEd25519(probe, signature, ed25519Public)) { "آزمون امضای هویت ناموفق بود" }
    }

    private fun Throwable.toAppError(fallback: String): AppError = when (this) {
        is BackupException -> error
        is BackupBundleException.Malformed -> AppError.Validation("فایل پشتیبان نامعتبر است")
        is BackupBundleException.AuthenticationFailed ->
            AppError.Security("رمز پشتیبان نادرست است یا فایل آسیب دیده است")
        is InvalidProtocolBufferException -> AppError.Validation("محتوای پشتیبان قابل خواندن نیست")
        is IllegalArgumentException -> AppError.Validation(message ?: fallback)
        else -> AppError.Unknown(message ?: fallback)
    }

    companion object {
        private const val KEY_SIZE = 32
        private const val ED25519_PRIVATE_SIZE = 64
    }
}
