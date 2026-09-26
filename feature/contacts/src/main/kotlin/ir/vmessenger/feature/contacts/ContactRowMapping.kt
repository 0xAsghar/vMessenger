package ir.vmessenger.feature.contacts

import android.location.Location
import ir.vmessenger.core.location.LocationUpdate
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.model.LocationSample
import kotlinx.collections.immutable.toImmutableList
import java.text.Collator
import java.util.Locale

/**
 * Which contact (or request) a confirmation dialog is currently waiting on. [targetId] is a
 * contact id everywhere except [RejectRequest], where it is the request id.
 */
internal sealed interface PendingContactAction {

    val targetId: String

    data class Rename(override val targetId: String) : PendingContactAction

    data class Block(override val targetId: String) : PendingContactAction

    data class Unblock(override val targetId: String) : PendingContactAction

    data class Delete(override val targetId: String) : PendingContactAction

    data class RejectRequest(override val targetId: String) : PendingContactAction
}

/**
 * The parts of [ContactsUiState] the user drives directly. Held as one flow so search, the
 * long-press sheet and the pending dialog all survive a rotation with the ViewModel.
 */
internal data class ContactsControl(
    val query: String = "",
    val searchActive: Boolean = false,
    val sheetContactId: String? = null,
    val pending: PendingContactAction? = null,
)

internal fun Contact.toRow(shared: LocationSample?, myLocation: LocationUpdate?): ContactRow = ContactRow(
    id = id,
    name = displayName,
    userHash = userHash,
    identityHash = identityHash,
    status = relationshipStatus,
    blocked = blocked,
    keyChangePending = keyChangePending,
    verified = verified,
    sharesLocation = shared != null,
    distanceMeters = distanceOrNull(myLocation, shared),
    lastSeenUnixMs = lastSeenUnixMs,
)

internal fun ContactRequest.toRow(): ContactRequestRow = ContactRequestRow(
    requestId = requestId,
    name = requesterDisplayName,
    userHash = requesterUserHash,
    identityHash = requesterIdentityHash,
)

/** Contacts sorted the way a Persian reader expects; the DAO's NOCASE ordering is ASCII-only. */
internal fun List<ContactRow>.sortedByPersianName(): List<ContactRow> {
    val collator = Collator.getInstance(Locale.forLanguageTag("fa"))
    return sortedWith { left, right -> collator.compare(left.name, right.name) }
}

/** Matches the typed query against the display name and the user hash, ignoring case and dashes. */
internal fun ContactRow.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = normalize(query)
    return normalize(name).contains(needle) || normalize(userHash).contains(needle)
}

internal fun buildContactsState(
    rows: List<ContactRow>,
    requests: List<ContactRequestRow>,
    control: ContactsControl,
): ContactsUiState {
    val visible = rows.filter { it.matches(control.query) }
    return ContactsUiState(
        loading = false,
        query = control.query,
        searchActive = control.searchActive,
        requests = requests.toImmutableList(),
        contacts = visible.toImmutableList(),
        sheetFor = rows.firstOrNull { it.id == control.sheetContactId },
        dialog = resolveDialog(control.pending, rows, requests),
    )
}

internal fun buildContactDetailState(
    data: ContactDetailData,
    action: PendingContactAction?,
): ContactDetailUiState {
    val contact = data.contact
    return ContactDetailUiState(
        loading = false,
        contact = contact?.toRow(shared = data.sharedLocation, myLocation = null),
        localPublicKey = data.localPublicKey,
        remotePublicKey = contact?.ed25519PublicKey,
        canSeeMyLocation = data.canSeeMyLocation,
        location = contact?.let { locationOf(data, it) },
        locationHistory = data.history.map {
            LocationHistoryEntry(it.sampledAtUnixMs, it.latitude, it.longitude, it.accuracyM)
        }.toImmutableList(),
        dialog = dialogFor(action, contact?.displayName),
    )
}

private fun resolveDialog(
    pending: PendingContactAction?,
    rows: List<ContactRow>,
    requests: List<ContactRequestRow>,
): ContactDialog {
    val name = when (pending) {
        null -> null
        is PendingContactAction.RejectRequest -> requests.firstOrNull { it.requestId == pending.targetId }?.name
        else -> rows.firstOrNull { it.id == pending.targetId }?.name
    }
    return dialogFor(pending, name)
}

/** A dialog only opens while its target still exists; otherwise the action silently lapses. */
private fun dialogFor(pending: PendingContactAction?, name: String?): ContactDialog = when {
    pending == null || name == null -> ContactDialog.None
    pending is PendingContactAction.Rename -> ContactDialog.Rename(pending.targetId, name)
    pending is PendingContactAction.Block -> ContactDialog.Block(pending.targetId, name)
    pending is PendingContactAction.Unblock -> ContactDialog.Unblock(pending.targetId, name)
    pending is PendingContactAction.Delete -> ContactDialog.Delete(pending.targetId, name)
    else -> ContactDialog.RejectRequest(pending.targetId, name)
}

private fun distanceOrNull(mine: LocationUpdate?, theirs: LocationSample?): Double? {
    if (mine == null || theirs == null) return null
    val result = FloatArray(1)
    Location.distanceBetween(mine.latitude, mine.longitude, theirs.latitude, theirs.longitude, result)
    return result[0].toDouble()
}

private fun normalize(value: String): String =
    value.lowercase(Locale.ROOT).replace("-", "").replace(" ", "")
