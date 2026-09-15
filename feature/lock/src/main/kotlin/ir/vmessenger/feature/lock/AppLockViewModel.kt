package ir.vmessenger.feature.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.data.lock.AppLockCoordinator
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.data.lock.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** What the lock screen has to say about the last thing the user tried. */
sealed interface UnlockFeedback {
    /** [attempt] is the coordinator's running total, which survives a force-stop. */
    data class Wrong(val attempt: Int) : UnlockFeedback

    /** Too many wrong PINs too quickly; [waitMs] is what is left of the wait. */
    data class TooSoon(val waitMs: Long) : UnlockFeedback

    /** The PIN was right and the hardware still would not release the key. Not a wrong PIN. */
    data object HardwareRefused : UnlockFeedback
    data object BiometricDone : UnlockFeedback
    data object BiometricFailed : UnlockFeedback
}

@Immutable
data class AppLockUiState(
    val lockState: LockState = LockState.Locked,
    val checking: Boolean = false,
    val wipeArmed: Boolean = false,
    val feedback: UnlockFeedback? = null,
    val unlocked: Boolean = false,
)

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val coordinator: AppLockCoordinator,
) : ViewModel() {

    private val own = MutableStateFlow(OwnState())

    val state: StateFlow<AppLockUiState> = combine(
        coordinator.state,
        coordinator.wipeOnFailedAttempts,
        own,
    ) { lockState, wipeArmed, local ->
        AppLockUiState(
            lockState = lockState,
            checking = local.checking,
            wipeArmed = wipeArmed,
            feedback = local.feedback,
            // A missing verifier is also a way in: there is nothing to check, and holding the
            // overlay up would shut the user out of an app that has no lock on it.
            unlocked = lockState == LockState.Unlocked || local.noLockSet,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppLockUiState())

    /**
     * Checks [pin] and takes ownership of it: the array is zeroed here whatever the outcome, so
     * the keypad can forget the digits the moment they are submitted.
     */
    fun unlock(pin: CharArray) {
        if (own.value.checking) {
            pin.fill(ZEROED)
            return
        }
        own.update { it.copy(checking = true, feedback = null) }
        viewModelScope.launch {
            val result = try {
                // Argon2id is half a second to two of CPU. On the main thread that is a keypad
                // that stops answering, which reads as a broken app rather than a slow check.
                withContext(Dispatchers.Default) { coordinator.unlock(pin) }
            } finally {
                pin.fill(ZEROED)
            }
            own.update {
                it.copy(
                    checking = false,
                    noLockSet = result is UnlockResult.NoLockSet,
                    feedback = result.feedback(),
                )
            }
        }
    }

    /**
     * A biometric result from the prompt.
     *
     * On a soft lock this opens the app outright — there is no key to recover, only a screen to
     * uncover, and making the user type a PIN after their fingerprint was accepted would be
     * theatre. In strict mode it cannot: the passphrase only comes back out of the Keystore, so
     * authenticating re-arms that key and the PIN is still required to use it.
     */
    fun onBiometricResult(authenticated: Boolean) {
        if (!authenticated) {
            own.update { it.copy(feedback = UnlockFeedback.BiometricFailed) }
            return
        }
        viewModelScope.launch {
            val opened = coordinator.unlockWithBiometric()
            own.update { it.copy(feedback = if (opened) null else UnlockFeedback.BiometricDone) }
        }
    }

    private fun UnlockResult.feedback(): UnlockFeedback? = when (this) {
        is UnlockResult.Wrong -> UnlockFeedback.Wrong(attempt)
        is UnlockResult.TooSoon -> UnlockFeedback.TooSoon(waitMs)
        UnlockResult.HardwareRefused -> UnlockFeedback.HardwareRefused
        // Wiped never reaches a frame: the wipe tears the process down. Reporting it as some
        // other failure would only be wrong in the case where something went wrong.
        UnlockResult.Wiped -> null
        UnlockResult.Unlocked, UnlockResult.NoLockSet -> null
    }

    private data class OwnState(
        val checking: Boolean = false,
        val noLockSet: Boolean = false,
        val feedback: UnlockFeedback? = null,
    )
}
