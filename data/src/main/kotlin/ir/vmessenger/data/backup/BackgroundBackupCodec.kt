package ir.vmessenger.data.backup

import ir.vmessenger.core.crypto.backup.BackupBundleCodec
import ir.vmessenger.core.crypto.backup.BackupHeaderInfo
import ir.vmessenger.data.di.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackupBundleCodec] with its CPU-bound work moved off the caller's dispatcher. The Argon2id KDF
 * takes seconds and up to 256 MiB, and the backup use cases are invoked from `viewModelScope` (Main),
 * so [encode] and [decode] always run on [defaultDispatcher]; [inspect] only parses the 54-byte header.
 */
@Singleton
class BackgroundBackupCodec @Inject constructor(
    private val codec: BackupBundleCodec,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    suspend fun encode(payloadBytes: ByteArray, passphrase: CharArray): ByteArray =
        withContext(defaultDispatcher) { codec.encode(payloadBytes, passphrase) }

    suspend fun decode(bundle: ByteArray, passphrase: CharArray): ByteArray =
        withContext(defaultDispatcher) { codec.decode(bundle, passphrase) }

    fun inspect(bundle: ByteArray): BackupHeaderInfo = codec.inspect(bundle)
}
