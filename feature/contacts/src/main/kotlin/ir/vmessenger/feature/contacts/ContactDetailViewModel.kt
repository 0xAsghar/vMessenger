package ir.vmessenger.feature.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.core.designsystem.component.UiMessageBus
import ir.vmessenger.domain.model.ContactRelationshipStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DETAIL_TIMEOUT_MS = 5_000L

/** Route argument name; it has to match `VmRoute.ContactDetail.contactId`. */
private const val CONTACT_ID_KEY = "contactId"

@Immutable
data class ContactDetailUiState(
    val loading: Boolean = true,
    val contact: ContactRow? = null,
    val localPublicKey: ByteArray? = null,
    val remotePublicKey: ByteArray? = null,
    val canSeeMyLocation: Boolean = false,
    val dialog: ContactDialog = ContactDialog.None,
) {
    /** The contact is gone (deleted here, or after the peer's revoke); the screen has to pop. */
    val notFound: Boolean get() = !loading && contact == null

    val canResendRequest: Boolean
        get() = contact?.status == ContactRelationshipStatus.PENDING_OUT

    /** The safety number needs both keys; a hash-only contact has not proven one yet. */
    val safetyNumberKeys: Pair<ByteArray, ByteArray>?
        get() {
            val local = localPublicKey
            val remote = remotePublicKey?.takeIf { key -> key.any { it != 0.toByte() } }
            return if (local != null && remote != null) local to remote else null
        }
}

/**
 * One contact, as its own destination.
 *
 * The contact id comes from the route, so the screen survives rotation and process death without
 * the tab having to remember which row was tapped.
 */
@HiltViewModel
// One entry point per action the screen offers; collapsing them into a single
// dispatch would only hide the surface behind a when.
@Suppress("TooManyFunctions")
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val source: ContactDetailSource,
    private val actions: ContactActions,
    private val messageBus: UiMessageBus,
) : ViewModel() {

    private val contactId: String = savedStateHandle.get<String>(CONTACT_ID_KEY).orEmpty()
    private val pending = MutableStateFlow<PendingContactAction?>(null)
    private val localMessages = Channel<UiMessage>(Channel.BUFFERED)
    private val conversations = Channel<String>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = localMessages.receiveAsFlow()

    /** Emits once the conversation to open has been resolved from the contact. */
    val openConversation: Flow<String> = conversations.receiveAsFlow()

    val uiState: StateFlow<ContactDetailUiState> = combine(
        source.observe(contactId),
        pending,
    ) { data, action ->
        buildContactDetailState(data, action)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(DETAIL_TIMEOUT_MS), ContactDetailUiState())

    /** Rename, block, unblock and delete all open a confirmation first; chat navigates instead. */
    fun onMenuAction(action: ContactSheetAction) {
        pending.value = when (action) {
            ContactSheetAction.CHAT -> null
            ContactSheetAction.RENAME -> PendingContactAction.Rename(contactId)
            ContactSheetAction.BLOCK -> PendingContactAction.Block(contactId)
            ContactSheetAction.UNBLOCK -> PendingContactAction.Unblock(contactId)
            ContactSheetAction.DELETE -> PendingContactAction.Delete(contactId)
        }
    }

    fun dismissDialog() {
        pending.value = null
    }

    fun confirmRename(alias: String) {
        pending.value = null
        viewModelScope.launch { emit(actions.rename(contactId, alias)) }
    }

    fun confirmPendingAction() {
        val action = pending.value ?: return
        pending.value = null
        viewModelScope.launch { runPending(action) }
    }

    fun onAcceptKeyChange() = viewModelScope.launch { emit(actions.acceptKeyChange(contactId)) }

    fun onVerifiedChange(verified: Boolean) =
        viewModelScope.launch { emit(actions.setVerified(contactId, verified)) }

    fun onResendRequest() = viewModelScope.launch {
        source.contactOrNull(contactId)?.let { emit(actions.resendRequest(it)) }
    }

    fun onStartChat() = viewModelScope.launch {
        conversations.send(actions.conversationWith(contactId))
    }

    fun onLocationAccessChange(granted: Boolean) = viewModelScope.launch {
        source.setLocationAccess(contactId, granted)
    }

    private suspend fun runPending(action: PendingContactAction) {
        when (action) {
            // The screen is about to pop, so the confirmation has to be shown by the list.
            is PendingContactAction.Delete -> messageBus.send(actions.delete(contactId))
            is PendingContactAction.Block -> emit(actions.setBlocked(contactId, blocked = true))
            is PendingContactAction.Unblock -> emit(actions.setBlocked(contactId, blocked = false))
            is PendingContactAction.Rename, is PendingContactAction.RejectRequest -> Unit
        }
    }

    private suspend fun emit(message: UiMessage) = localMessages.send(message)
}
