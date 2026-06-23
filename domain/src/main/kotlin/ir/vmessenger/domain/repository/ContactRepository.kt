package ir.vmessenger.domain.repository

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>
    suspend fun getContact(id: String): Contact?
    suspend fun addContactByDescriptor(descriptorBytes: ByteArray, alias: String?): AppResult<Contact>
    suspend fun addContactByUserHash(userHash: String, alias: String?): AppResult<Contact>
    suspend fun updateContactAlias(id: String, alias: String)
    suspend fun blockContact(id: String, blocked: Boolean)
    suspend fun deleteContact(id: String)
}
