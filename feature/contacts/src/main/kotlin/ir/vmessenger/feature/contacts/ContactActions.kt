package ir.vmessenger.feature.contacts

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.data.network.ContactRequestHandler
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.usecase.chat.StartConversationUseCase
import ir.vmessenger.domain.usecase.contact.AcceptKeyChangeUseCase
import ir.vmessenger.domain.usecase.contact.BlockContactUseCase
import ir.vmessenger.domain.usecase.contact.DeleteContactUseCase
import ir.vmessenger.domain.usecase.contact.SendContactRequestUseCase
import ir.vmessenger.domain.usecase.contact.SetContactVerifiedUseCase
import ir.vmessenger.domain.usecase.contact.UpdateContactAliasUseCase
import javax.inject.Inject

/**
 * The contact commands the list and the detail screen share, each returning the snackbar its
 * caller should show. Keeping them here means both screens word the outcome identically and
 * neither ViewModel has to carry a use case it only forwards.
 */
@Suppress("LongParameterList") // one use case per command; they are forwarded, not combined
class ContactActions @Inject constructor(
    private val deleteContact: DeleteContactUseCase,
    private val blockContact: BlockContactUseCase,
    private val updateAlias: UpdateContactAliasUseCase,
    private val acceptKeyChangeUseCase: AcceptKeyChangeUseCase,
    private val sendContactRequest: SendContactRequestUseCase,
    private val setContactVerified: SetContactVerifiedUseCase,
    private val startConversation: StartConversationUseCase,
    private val requestHandler: ContactRequestHandler,
) {
    /**
     * Purges the contact, its conversation, attachments, outbox rows and location shares, and
     * tells the peer (see `ContactCleanupCoordinator`). There is nothing to undo afterwards.
     */
    suspend fun delete(contactId: String): UiMessage {
        deleteContact(contactId)
        return UiMessage.Text(R.string.contacts_deleted)
    }

    suspend fun setBlocked(contactId: String, blocked: Boolean): UiMessage {
        blockContact(contactId, blocked)
        return UiMessage.Text(
            if (blocked) R.string.contacts_blocked_done else R.string.contacts_unblocked_done,
        )
    }

    /**
     * Records that the user compared the safety number out of band. Local annotation only:
     * it neither changes the pinned key nor re-authenticates anything.
     */
    suspend fun setVerified(contactId: String, verified: Boolean): UiMessage {
        setContactVerified(contactId, verified)
        return UiMessage.Text(
            if (verified) R.string.contacts_marked_verified else R.string.contacts_unmarked_verified,
        )
    }

    suspend fun rename(contactId: String, alias: String): UiMessage {
        updateAlias(contactId, alias.trim())
        return UiMessage.Text(R.string.contacts_renamed)
    }

    suspend fun approveRequest(request: ContactRequest): UiMessage {
        requestHandler.approveRequest(request)
        return UiMessage.Text(R.string.contacts_request_approved)
    }

    suspend fun rejectRequest(request: ContactRequest): UiMessage {
        requestHandler.rejectRequest(request)
        return UiMessage.Text(R.string.contacts_request_rejected)
    }

    /** Pins the key the peer now presents; handshakes stay refused until this runs. */
    suspend fun acceptKeyChange(contactId: String): UiMessage =
        when (val result = acceptKeyChangeUseCase(contactId)) {
            is AppResult.Success -> UiMessage.Text(R.string.contacts_key_change_accepted)
            is AppResult.Error -> UiMessage.Failure(result.error)
        }

    suspend fun resendRequest(contact: Contact): UiMessage =
        when (val result = sendContactRequest(contact)) {
            is AppResult.Success -> UiMessage.Text(R.string.contacts_request_resent)
            is AppResult.Error -> UiMessage.Failure(result.error)
        }

    suspend fun conversationWith(contactId: String): String = startConversation(contactId)

    companion object {
        /** Alias bounds enforced by the rename dialog. */
        const val MIN_ALIAS_LENGTH = 1
        const val MAX_ALIAS_LENGTH = 32
    }
}
