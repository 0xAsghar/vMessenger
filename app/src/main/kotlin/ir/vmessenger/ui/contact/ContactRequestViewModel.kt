package ir.vmessenger.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.data.network.ContactRequestHandler
import ir.vmessenger.data.network.ContactRequestNotifier
import ir.vmessenger.domain.model.ContactRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactRequestViewModel @Inject constructor(
    private val notifier: ContactRequestNotifier,
    private val handler: ContactRequestHandler,
) : ViewModel() {
    private val _pendingRequest = MutableStateFlow<ContactRequest?>(null)
    val pendingRequest: StateFlow<ContactRequest?> = _pendingRequest.asStateFlow()

    init {
        viewModelScope.launch {
            notifier.incoming.collect { request ->
                _pendingRequest.value = request
            }
        }
    }

    fun approve() {
        val request = _pendingRequest.value ?: return
        viewModelScope.launch {
            handler.approveRequest(request)
            _pendingRequest.value = null
        }
    }

    fun reject() {
        val request = _pendingRequest.value ?: return
        viewModelScope.launch {
            handler.rejectRequest(request)
            _pendingRequest.value = null
        }
    }

    fun dismiss() {
        _pendingRequest.value = null
    }
}
