package ir.vmessenger.domain.repository

/**
 * Erases every trace of the account from this device.
 *
 * The implementation never returns normally: once the data is gone it restarts
 * the process, because long-lived singletons still hold the old database
 * passphrase and network state in memory.
 */
interface SecureWipeService {
    suspend fun wipe()
}
