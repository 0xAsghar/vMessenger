package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.BackupHeaderInfo
import ir.vmessenger.domain.model.BackupOptions
import ir.vmessenger.domain.model.RestoreSummary

/**
 * Passphrase-encrypted, versioned backup of the local identity, contacts and (optionally) conversations.
 *
 * Passphrases are passed as [CharArray] so callers can zeroize them after use; implementations must
 * never retain them.
 */
interface IdentityBackupRepository {
    /** Serialises and encrypts the current identity into a `.vmb` bundle. */
    suspend fun exportBundle(passphrase: CharArray, options: BackupOptions = BackupOptions()): AppResult<ByteArray>

    /** Reads the unencrypted header of [bytes] (format version, KDF parameters) without a passphrase. */
    suspend fun inspectBundle(bytes: ByteArray): AppResult<BackupHeaderInfo>

    /** Decrypts [bytes] and restores its contents. Refuses when an identity already exists. */
    suspend fun importBundle(bytes: ByteArray, passphrase: CharArray): AppResult<RestoreSummary>
}
