package ir.vmessenger.data.wipe

import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CancellationException

/** One named, independently guarded stage of the secure wipe. */
class WipeStep(val name: String, val run: suspend () -> Unit)

/**
 * The side effects of a wipe, one method per stage.
 *
 * Split from the plan so the ordering and the all-steps-run guarantee can be
 * tested without Android (`SecureWipeCoordinator` is the only production
 * implementation).
 */
@Suppress("TooManyFunctions") // One method per wipe stage; splitting the interface would hide the order.
interface SecureWipeActions {
    suspend fun stopNetwork()
    fun stopLocationSharing()
    fun cancelNotifications()
    suspend fun clearAndCloseDatabase()
    fun deleteDatabaseFiles()
    fun deleteFiles()
    suspend fun clearPreferences()

    /** In-memory singletons: cached keys, logs, network diagnostics, runtime flags. */
    fun resetInMemoryState()

    /** Destroys the Keystore master key; every wrapped blob left anywhere becomes undecryptable. */
    fun deleteMasterKey()
}

/**
 * The wipe, as an ordered list of guarded steps.
 *
 * Order matters twice. The network stops **first** so nothing writes a new
 * message, receipt or cache entry into storage that is about to be deleted.
 * The Keystore master key is destroyed **last**: every earlier step may still
 * need to decrypt (closing the SQLCipher database, reading attachment paths),
 * and a failure anywhere earlier still ends with the key gone, which alone
 * makes the leftovers unreadable.
 */
object SecureWipePlan {
    const val STEP_NETWORK = "network"
    const val STEP_LOCATION = "location"
    const val STEP_NOTIFICATIONS = "notifications"
    const val STEP_DATABASE = "database"
    const val STEP_DATABASE_FILES = "database-files"
    const val STEP_FILES = "files"
    const val STEP_PREFERENCES = "preferences"
    const val STEP_MEMORY = "memory"
    const val STEP_KEYSTORE = "keystore"

    fun steps(actions: SecureWipeActions): List<WipeStep> = listOf(
        WipeStep(STEP_NETWORK) { actions.stopNetwork() },
        WipeStep(STEP_LOCATION) { actions.stopLocationSharing() },
        WipeStep(STEP_NOTIFICATIONS) { actions.cancelNotifications() },
        WipeStep(STEP_DATABASE) { actions.clearAndCloseDatabase() },
        WipeStep(STEP_DATABASE_FILES) { actions.deleteDatabaseFiles() },
        WipeStep(STEP_FILES) { actions.deleteFiles() },
        WipeStep(STEP_PREFERENCES) { actions.clearPreferences() },
        WipeStep(STEP_MEMORY) { actions.resetInMemoryState() },
        WipeStep(STEP_KEYSTORE) { actions.deleteMasterKey() },
    )

    /**
     * Runs every step, in order, **even when an earlier one throws** — a wipe
     * that stopped at the first failure would leave the rest of the data
     * behind, which is the opposite of what the user asked for. Returns the
     * names of the steps that failed (empty on a clean wipe).
     *
     * Cancellation is **not** a step failure and is rethrown: swallowing it
     * would let the remaining steps run on a dead scope and, worse, let the
     * Keystore key be destroyed while the wrapped passphrase survives — an
     * unopenable install with no way back. The caller runs the wipe where it
     * cannot be cancelled ([SecureWipeCoordinator.wipe]).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(steps: List<WipeStep>): List<String> {
        val failed = mutableListOf<String>()
        for (step in steps) {
            try {
                step.run()
                AppLogger.info(TAG, "wipe step ok: ${step.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += step.name
                AppLogger.warn(TAG, "wipe step failed: ${step.name}: ${e.message}")
            }
        }
        return failed
    }

    private const val TAG = "Wipe"
}
