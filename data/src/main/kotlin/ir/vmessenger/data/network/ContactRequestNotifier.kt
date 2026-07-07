package ir.vmessenger.data.network

import ir.vmessenger.domain.model.ContactRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRequestNotifier @Inject constructor() {
    private val _incoming = MutableSharedFlow<ContactRequest>(extraBufferCapacity = 8)
    val incoming: SharedFlow<ContactRequest> = _incoming.asSharedFlow()

    suspend fun notify(request: ContactRequest) {
        _incoming.emit(request)
    }
}
