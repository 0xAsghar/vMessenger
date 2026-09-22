package ir.vmessenger.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.data.activity.ActivityLogger
import ir.vmessenger.data.call.CallCoordinator
import ir.vmessenger.data.call.CallSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The call screen's window onto [CallCoordinator].
 *
 * It holds no state of its own, and deliberately no key material: the session it exposes carries a
 * name and a state, which is all a screen needs. Every button here is a call into the coordinator,
 * so the state machine stays the single authority on what a call may do next.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callCoordinator: CallCoordinator,
    private val activityLogger: ActivityLogger,
) : ViewModel() {
    val session: StateFlow<CallSession?> = callCoordinator.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
        initialValue = callCoordinator.session.value,
    )

    fun dial(contactId: String) = viewModelScope.launch { callCoordinator.dial(contactId) }

    fun accept() = viewModelScope.launch { callCoordinator.accept() }

    fun decline() = viewModelScope.launch { callCoordinator.decline() }

    fun hangUp() = viewModelScope.launch { callCoordinator.hangUp() }

    fun setMuted(muted: Boolean) = callCoordinator.setMuted(muted)

    fun setSpeaker(on: Boolean) = callCoordinator.setSpeaker(on)

    /** Records what the user answered to the microphone request, either way. */
    fun recordMicrophoneAnswer(granted: Boolean) {
        val kind = if (granted) ActivityKind.PermissionGranted else ActivityKind.PermissionDenied
        activityLogger.record(kind, PERMISSION_MICROPHONE)
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        /** The permission's own name, so the log entry reads as the thing the user was asked. */
        const val PERMISSION_MICROPHONE = "RECORD_AUDIO"
    }
}
