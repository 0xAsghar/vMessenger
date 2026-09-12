package ir.vmessenger.feature.chat.group

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.feature.chat.IdentitySeed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * One member of the group, as the info screen draws them.
 *
 * A member need not be a contact: their keys travel in the snapshot, so they can be in the
 * group without either side having agreed to a private chat.
 */
@Immutable
data class GroupMemberRow(
    /** Lowercase hex; the only stable handle a non-contact member has. */
    val identityHash: String,
    val name: String,
    val seed: IdentitySeed,
    val contactId: String?,
    val isCreator: Boolean,
    val isMe: Boolean,
    /** A contact request to this member is already on its way; offering another would resend it. */
    val requestPending: Boolean,
) {
    val isContact: Boolean get() = contactId != null

    /**
     * A name is only shown when we have a reason to trust it. A stranger's name comes from the
     * snapshot they wrote, so it is replaced by «ناشناس» until the user adds them as a contact
     * and picks a name themselves.
     */
    val hasKnownName: Boolean get() = (isContact || isMe) && name.isNotBlank()

    val canBeAddedAsContact: Boolean get() = !isContact && !isMe && !requestPending

    fun label(unknown: String): String = if (hasKnownName) name else unknown
}

/** The modal a group action waits behind; none of these runs on a single tap. */
@Immutable
sealed interface GroupDialog {

    data object None : GroupDialog

    data object Rename : GroupDialog

    /** The member picker, which is modal for the same reason: nothing is sent until confirmed. */
    data object AddMembers : GroupDialog

    data object Leave : GroupDialog

    data object Close : GroupDialog

    data class RemoveMember(val member: GroupMemberRow) : GroupDialog
}

/**
 * The group info screen.
 *
 * Membership is creator-authoritative, so every structural action is gated on [createdByMe]
 * here as well as in the repository: an action the user cannot take should not be on screen
 * to be tapped.
 */
@Immutable
data class GroupInfoUiState(
    val loading: Boolean = true,
    val exists: Boolean = false,
    val name: String = "",
    val seed: IdentitySeed = IdentitySeed.Empty,
    val createdByMe: Boolean = false,
    val closed: Boolean = false,
    val members: ImmutableList<GroupMemberRow> = persistentListOf(),
    val dialog: GroupDialog = GroupDialog.None,
    /** Non-null exactly while the "add members" sheet is open. */
    val picker: GroupPickerState? = null,
    val message: UiMessage? = null,
) {
    /** The group is gone (erased after a close, or never existed here); the screen has to pop. */
    val notFound: Boolean get() = !loading && !exists

    val memberCount: Int get() = members.size

    /** Structural changes belong to the creator, and only while the group is still open. */
    val canManage: Boolean get() = createdByMe && !closed

    val canAddMembers: Boolean get() = canManage && memberCount < GroupLimits.MAX_MEMBERS

    /** The creator has no "leave": closing is their way out, and it tells everyone else. */
    val canLeave: Boolean get() = exists && !createdByMe && !closed

    val canClose: Boolean get() = canManage

    /** Seats left, which is the cap the "add members" picker is given. */
    val remainingSeats: Int get() = (GroupLimits.MAX_MEMBERS - memberCount).coerceAtLeast(0)
}
