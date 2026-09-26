package ir.vmessenger.data.network

import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRequestRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the person that someone asked to become their contact. Until 2.0.2 this only emitted into a
 * flow nobody collected, so a request raised no notification and was seen only once the app was
 * opened; now it raises one, through the same notifier (and the same privacy rules) as messages.
 */
@Singleton
class ContactRequestNotifier @Inject constructor(
    private val notifier: IncomingMessageNotifier,
    private val requests: ContactRequestRepository,
) {
    /**
     * Saves [request] as pending and raises its notification. The requester's app re-sends until we
     * answer; only the copy that makes the request pending alerts, not every one after it.
     */
    suspend fun saveAndNotify(request: ContactRequest) {
        val alreadyPending = requests.observePendingRequests().first().any { it.requestId == request.requestId }
        requests.saveRequest(request)
        if (alreadyPending) return
        notifier.notifyContactRequest(
            requesterName = request.requesterDisplayName.ifBlank { request.requesterUserHash },
            requestId = request.requestId,
        )
    }
}
