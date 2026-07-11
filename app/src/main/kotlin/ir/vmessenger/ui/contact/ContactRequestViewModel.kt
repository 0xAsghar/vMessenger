package ir.vmessenger.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.data.network.ContactRequestHandler
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRequestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactRequestViewModel @Inject constructor(
    contactRequestRepository: ContactRequestRepository,
    private val handler: ContactRequestHandler,
) : ViewModel() {
    /** Requests the user swiped away this session; they stay pending in the DB. */
    private val dismissedIds = MutableStateFlow<Set<String>>(emptySet())

    // Backed by the database so requests that arrived while the app was closed
    // (or while the dialog was missed) are shown again on next launch.
    val pendingRequest: StateFlow<ContactRequest?> =
        combine(
            contactRequestRepository.observePendingRequests(),
            dismissedIds,
        ) { pending, dismissed ->
            pending.firstOrNull { it.requestId !in dismissed }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun approve() {
        val request = pendingRequest.value ?: return
        viewModelScope.launch {
            handler.approveRequest(request)
        }
    }

    fun reject() {
        val request = pendingRequest.value ?: return
        viewModelScope.launch {
            handler.rejectRequest(request)
        }
    }

    fun dismiss() {
        val request = pendingRequest.value ?: return
        dismissedIds.update { it + request.requestId }
    }
}
