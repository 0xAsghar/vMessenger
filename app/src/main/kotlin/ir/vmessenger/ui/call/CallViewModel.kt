package ir.vmessenger.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.data.activity.ActivityLogger
import ir.vmessenger.data.call.CallCoordinator
import ir.vmessenger.data.call.CallSession
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.ui.ThemeChoice
import ir.vmessenger.ui.themeChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val contactRepository: ContactRepository,
    themePreferences: ThemePreferences,
) : ViewModel() {
    /** The app's theme setting, or null for the moment it takes to read — the screen waits for it. */
    internal val theme: StateFlow<ThemeChoice?> = themePreferences.themeChoice()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    val session: StateFlow<CallSession?> = callCoordinator.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS),
        initialValue = callCoordinator.session.value,
    )

    /**
     * The peer's identicon seed, the same one the contact list draws. Empty until it is read, or
     * when it cannot be — the avatar then shows the name's initial on a neutral disc.
     */
    val avatarSeed: StateFlow<ByteArray> = callCoordinator.session
        .map { it?.contactId }
        .distinctUntilChanged()
        .map { contactId ->
            contactId?.let { runCatching { contactRepository.getContact(it)?.identityHash }.getOrNull() }
                ?: ByteArray(0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), ByteArray(0))

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
