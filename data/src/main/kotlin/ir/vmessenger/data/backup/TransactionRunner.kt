package ir.vmessenger.data.backup

import androidx.room.withTransaction
import ir.vmessenger.core.database.VMessengerDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a suspending block atomically against the app database. Abstracted so repository code stays
 * JVM-testable without a Room instance.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

@Singleton
class RoomTransactionRunner @Inject constructor(
    private val database: VMessengerDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}
