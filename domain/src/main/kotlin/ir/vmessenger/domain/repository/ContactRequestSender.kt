package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact

interface ContactRequestSender {
    suspend fun sendRequest(contact: Contact): AppResult<Unit>

    /**
     * [sendRequest] without waiting for it, for the add flows. They must not hold their screen on a
     * network round trip: failing against an offline peer took seconds, on a filtered network far
     * longer, and the QR scanner sat blank the whole time. Whatever this attempt does not deliver,
     * the retry worker does.
     */
    fun sendRequestInBackground(contact: Contact)
}
