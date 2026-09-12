package ir.vmessenger.data.wipe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wipe *plan* — the part of [SecureWipeCoordinator] that decides
 * what runs, in what order, and what happens when a step fails. The Android
 * side effects themselves are exercised on the emulators.
 */
class SecureWipeCoordinatorTest {
    /** Records the order steps ran in; the step named [failOn] throws instead. */
    private class RecordingActions(private val failOn: String? = null) : SecureWipeActions {
        val performed = mutableListOf<String>()

        private fun step(name: String) {
            performed += name
            if (name == failOn) error("boom in $name")
        }

        override suspend fun stopNetwork() = step(SecureWipePlan.STEP_NETWORK)
        override fun stopLocationSharing() = step(SecureWipePlan.STEP_LOCATION)
        override fun cancelNotifications() = step(SecureWipePlan.STEP_NOTIFICATIONS)
        override suspend fun clearAndCloseDatabase() = step(SecureWipePlan.STEP_DATABASE)
        override fun deleteDatabaseFiles() = step(SecureWipePlan.STEP_DATABASE_FILES)
        override fun deleteFiles() = step(SecureWipePlan.STEP_FILES)
        override suspend fun clearPreferences() = step(SecureWipePlan.STEP_PREFERENCES)
        override fun resetInMemoryState() = step(SecureWipePlan.STEP_MEMORY)
        override fun deleteMasterKey() = step(SecureWipePlan.STEP_KEYSTORE)
    }

    private val allSteps = listOf(
        SecureWipePlan.STEP_NETWORK,
        SecureWipePlan.STEP_LOCATION,
        SecureWipePlan.STEP_NOTIFICATIONS,
        SecureWipePlan.STEP_DATABASE,
        SecureWipePlan.STEP_DATABASE_FILES,
        SecureWipePlan.STEP_FILES,
        SecureWipePlan.STEP_PREFERENCES,
        SecureWipePlan.STEP_MEMORY,
        SecureWipePlan.STEP_KEYSTORE,
    )

    @Test
    fun allStepsRunEvenIfOneThrows() = runTest {
        val actions = RecordingActions(failOn = SecureWipePlan.STEP_DATABASE)

        val failed = SecureWipePlan.run(SecureWipePlan.steps(actions))

        // A database that refuses to close must not save the attachments, the
        // preferences or the Keystore key from being destroyed.
        assertEquals(allSteps, actions.performed)
        assertEquals(listOf(SecureWipePlan.STEP_DATABASE), failed)
    }

    @Test
    fun everyStepFailingStillRunsAllOfThem() = runTest {
        var calls = 0
        val steps = allSteps.map { name ->
            WipeStep(name) {
                calls++
                error("boom")
            }
        }

        val failed = SecureWipePlan.run(steps)

        assertEquals(allSteps.size, calls)
        assertEquals(allSteps, failed)
    }

    @Test
    fun orderIsNetworkFirstKeystoreLast() = runTest {
        val actions = RecordingActions()

        val failed = SecureWipePlan.run(SecureWipePlan.steps(actions))

        assertTrue(failed.isEmpty())
        // Network first: nothing may write into storage that is about to go.
        assertEquals(SecureWipePlan.STEP_NETWORK, actions.performed.first())
        // Keystore last: earlier steps still need to decrypt, and destroying the
        // master key is what makes anything left behind unreadable.
        assertEquals(SecureWipePlan.STEP_KEYSTORE, actions.performed.last())
        assertTrue(
            actions.performed.indexOf(SecureWipePlan.STEP_DATABASE) <
                actions.performed.indexOf(SecureWipePlan.STEP_DATABASE_FILES),
        )
    }

    /**
     * Cancellation is not a step failure. Swallowing it would run the rest of
     * the plan — up to destroying the Keystore key — on a dead scope, leaving
     * the wrapped passphrase behind and the install unopenable.
     */
    @Test
    fun cancellationPropagatesInsteadOfCountingAsAFailedStep() = runTest {
        val performed = mutableListOf<String>()
        val steps = listOf(
            WipeStep(SecureWipePlan.STEP_NETWORK) { performed += SecureWipePlan.STEP_NETWORK },
            WipeStep(SecureWipePlan.STEP_DATABASE) { throw CancellationException("caller scope died") },
            WipeStep(SecureWipePlan.STEP_KEYSTORE) { performed += SecureWipePlan.STEP_KEYSTORE },
        )

        var cancelled: CancellationException? = null
        try {
            SecureWipePlan.run(steps)
        } catch (e: CancellationException) {
            cancelled = e
        }

        assertNotNull(cancelled)
        assertEquals(listOf(SecureWipePlan.STEP_NETWORK), performed)
    }
}
