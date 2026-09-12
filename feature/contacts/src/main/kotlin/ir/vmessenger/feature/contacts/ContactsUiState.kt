package ir.vmessenger.feature.contacts

import androidx.compose.runtime.Immutable
import ir.vmessenger.domain.model.ContactRelationshipStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * One contact as the list and the detail screen draw it.
 *
 * The identity hash is carried as raw bytes because that is what `Avatar` seeds itself from; the
 * class is annotated [Immutable] so Compose keeps treating rows as stable despite that array.
 */
@Immutable
data class ContactRow(
    val id: String,
    val name: String,
    val userHash: String,
    val identityHash: ByteArray,
    val status: ContactRelationshipStatus,
    val blocked: Boolean,
    val keyChangePending: Boolean,
    val verified: Boolean,
    val sharesLocation: Boolean,
    val distanceMeters: Double?,
) {
    val isApproved: Boolean get() = status == ContactRelationshipStatus.APPROVED

    /** A chat can only start with an approved contact we have not blocked. */
    val canChat: Boolean get() = isApproved && !blocked

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactRow
        return id == other.id &&
            name == other.name &&
            userHash == other.userHash &&
            identityHash.contentEquals(other.identityHash) &&
            status == other.status &&
            blocked == other.blocked &&
            keyChangePending == other.keyChangePending &&
            verified == other.verified &&
            sharesLocation == other.sharesLocation &&
            distanceMeters == other.distanceMeters
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + identityHash.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + keyChangePending.hashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + sharesLocation.hashCode()
        result = 31 * result + (distanceMeters?.hashCode() ?: 0)
        return result
    }
}

/** A contact request waiting for the user's answer. */
@Immutable
data class ContactRequestRow(
    val requestId: String,
    val name: String,
    val userHash: String,
    val identityHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactRequestRow
        return requestId == other.requestId &&
            name == other.name &&
            userHash == other.userHash &&
            identityHash.contentEquals(other.identityHash)
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + userHash.hashCode()
        result = 31 * result + identityHash.contentHashCode()
        return result
    }
}

/**
 * The modal a contact action is waiting behind. Every entry here is destructive or renaming, so
 * none of them runs on a single tap.
 */
@Immutable
sealed interface ContactDialog {

    data object None : ContactDialog

    data class Rename(val contactId: String, val currentName: String) : ContactDialog

    data class Block(val contactId: String, val name: String) : ContactDialog

    data class Unblock(val contactId: String, val name: String) : ContactDialog

    data class Delete(val contactId: String, val name: String) : ContactDialog

    data class RejectRequest(val requestId: String, val name: String) : ContactDialog
}

/** What the long-press sheet offers for one contact. */
enum class ContactSheetAction { CHAT, RENAME, BLOCK, UNBLOCK, DELETE }

@Immutable
data class ContactsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val searchActive: Boolean = false,
    val requests: ImmutableList<ContactRequestRow> = persistentListOf(),
    val contacts: ImmutableList<ContactRow> = persistentListOf(),
    val sheetFor: ContactRow? = null,
    val dialog: ContactDialog = ContactDialog.None,
) {
    /** No contacts and no requests at all — as opposed to a search that matched nothing. */
    val isEmpty: Boolean get() = !loading && requests.isEmpty() && contacts.isEmpty() && query.isBlank()

    val noSearchResults: Boolean get() = !loading && contacts.isEmpty() && query.isNotBlank()
}

/** Everywhere the contacts tab can send the user. */
@Immutable
data class ContactsNavigation(
    val onMyQr: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onAddByHash: () -> Unit = {},
    val onOpenContact: (String) -> Unit = {},
    val onStartChat: (String) -> Unit = {},
)

/** The taps a contacts row can produce, bundled so the list keeps a short parameter list. */
@Immutable
data class ContactsListCallbacks(
    val onOpenContact: (String) -> Unit,
    val onLongPressContact: (String) -> Unit,
    val onApproveRequest: (String) -> Unit,
    val onRejectRequest: (String) -> Unit,
)

/** The three answers a contact dialog can produce. */
@Immutable
data class ContactDialogCallbacks(
    val onConfirm: () -> Unit,
    val onRename: (String) -> Unit,
    val onDismiss: () -> Unit,
)
