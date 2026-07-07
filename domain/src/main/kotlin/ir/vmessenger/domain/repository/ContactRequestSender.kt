package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact

interface ContactRequestSender {
    suspend fun sendRequest(contact: Contact): AppResult<Unit>
}
