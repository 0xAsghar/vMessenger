package ir.vmessenger.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.core.designsystem.component.UiMessageBus
import ir.vmessenger.core.location.DeviceLocationProvider
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * The contacts tab.
 *
 * Everything the user can change — the search query, which row opened the sheet, which
 * confirmation is pending — lives in one control flow that is combined with the database, so a
 * rotation loses none of it and the composables stay stateless.
 */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    observeContacts: ObserveContactsUseCase,
    locationRepository: LocationRepository,
    deviceLocationProvider: DeviceLocationProvider,
    private val contactRequests: ContactRequestRepository,
    private val actions: ContactActions,
    messageBus: UiMessageBus,
) : ViewModel() {

    private val control = MutableStateFlow(ContactsControl())
    private val localMessages = Channel<UiMessage>(Channel.BUFFERED)

    /** Snackbars from this screen, plus the one a deletion left behind when the detail popped. */
    val messages: Flow<UiMessage> = merge(localMessages.receiveAsFlow(), messageBus.messages)

    private val contactRows = combine(
        observeContacts(),
        locationRepository.observeIncomingLocations(),
        // Distances are happy with a last-known fix, so this collects without acquiring a live
        // one — opening this tab must never be a reason to switch the GPS on.
        deviceLocationProvider.observe(),
    ) { contacts, incoming, myLocation ->
        contacts.map { contact -> contact.toRow(incoming[contact.id], myLocation) }.sortedByPersianName()
    }

    // A pending row can linger after the relationship completed through another path (a mutual
    // add); such requests are auto-accepted on arrival, so showing them again would be a lie.
    private val requestRows = combine(
        contactRequests.observePendingRequests(),
        observeContacts(),
    ) { requests, contacts ->
        val approved = contacts.filter { it.isApproved }.map { it.identityHash }
        requests
            .filterNot { request ->
                approved.any { IdentityHashMatcher.matches(it, request.requesterIdentityHash) }
            }
            .map { it.toRow() }
    }

    val uiState: StateFlow<ContactsUiState> = combine(
        contactRows,
        requestRows,
        control,
    ) { rows, requests, state ->
        buildContactsState(rows, requests, state)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ContactsUiState())

    fun onQueryChange(query: String) = control.update { it.copy(query = query) }

    fun onSearchActiveChange(active: Boolean) =
        control.update { it.copy(searchActive = active, query = if (active) it.query else "") }

    /** Opens the long-press sheet for a contact, or closes it when given null. */
    fun onSheetFor(contactId: String?) = control.update { it.copy(sheetContactId = contactId) }

    /** Every sheet entry except "chat" opens a confirmation; the caller navigates for "chat". */
    fun onSheetAction(contactId: String, action: ContactSheetAction) {
        val pending = when (action) {
            ContactSheetAction.CHAT -> null
            ContactSheetAction.RENAME -> PendingContactAction.Rename(contactId)
            ContactSheetAction.BLOCK -> PendingContactAction.Block(contactId)
            ContactSheetAction.UNBLOCK -> PendingContactAction.Unblock(contactId)
            ContactSheetAction.DELETE -> PendingContactAction.Delete(contactId)
        }
        control.update { it.copy(sheetContactId = null, pending = pending) }
    }

    fun onApproveRequest(requestId: String) = viewModelScope.launch {
        contactRequests.getRequest(requestId)?.let { emit(actions.approveRequest(it)) }
    }

    /** Rejecting is destructive enough to confirm: the peer is told and has to ask again. */
    fun onRejectRequest(requestId: String) =
        control.update { it.copy(pending = PendingContactAction.RejectRequest(requestId)) }

    fun dismissDialog() = control.update { it.copy(pending = null) }

    fun confirmRename(alias: String) {
        val pending = control.value.pending as? PendingContactAction.Rename ?: return
        control.update { it.copy(pending = null) }
        viewModelScope.launch { emit(actions.rename(pending.targetId, alias)) }
    }

    fun confirmPendingAction() {
        val pending = control.value.pending ?: return
        control.update { it.copy(pending = null) }
        viewModelScope.launch {
            when (pending) {
                is PendingContactAction.Delete -> emit(actions.delete(pending.targetId))
                is PendingContactAction.Block -> emit(actions.setBlocked(pending.targetId, blocked = true))
                is PendingContactAction.Unblock -> emit(actions.setBlocked(pending.targetId, blocked = false))
                is PendingContactAction.RejectRequest ->
                    contactRequests.getRequest(pending.targetId)?.let { emit(actions.rejectRequest(it)) }
                is PendingContactAction.Rename -> Unit
            }
        }
    }

    private suspend fun emit(message: UiMessage) {
        localMessages.send(message)
    }
}
