package ir.vmessenger.feature.chat.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.model.GroupMember
import ir.vmessenger.domain.model.GroupMemberRole
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import ir.vmessenger.domain.usecase.group.AddContactFromGroupMemberUseCase
import ir.vmessenger.domain.usecase.group.AddGroupMembersUseCase
import ir.vmessenger.domain.usecase.group.CloseGroupUseCase
import ir.vmessenger.domain.usecase.group.LeaveGroupUseCase
import ir.vmessenger.domain.usecase.group.ObserveGroupMembersUseCase
import ir.vmessenger.domain.usecase.group.ObserveGroupUseCase
import ir.vmessenger.domain.usecase.group.RemoveGroupMemberUseCase
import ir.vmessenger.domain.usecase.group.SetGroupAuditRetentionUseCase
import ir.vmessenger.domain.usecase.group.SetGroupMemberAdminUseCase
import ir.vmessenger.domain.usecase.group.UpdateGroupNameUseCase
import ir.vmessenger.feature.chat.IdentitySeed
import ir.vmessenger.feature.chat.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private const val BYTE_MASK = 0xFF
private const val LOW_NIBBLE_MASK = 0x0F

/** Route argument name; it has to match `VmRoute.GroupInfo.groupId`. */
internal const val GROUP_ID_KEY = "groupId"

/**
 * `null` means the database has not answered yet; a holder with a null [value] means it has
 * answered and the group is gone. Without the distinction the screen would pop itself on the
 * first frame of every visit.
 */
internal data class GroupSnapshot(val value: Group?)

/** The part of the screen the database does not own: which modal is open and what is ticked. */
internal data class GroupInfoLocal(
    val dialog: GroupDialog = GroupDialog.None,
    val query: String = "",
    val selected: ImmutableSet<String> = persistentSetOf(),
    val message: UiMessage? = null,
)

/**
 * One group, as its own destination.
 *
 * Every structural action is creator-only and the repository enforces that, but the state
 * gates them too: an action the user cannot take should not be on screen to be tapped, and
 * a closed group offers none of them at all.
 */
@HiltViewModel
// LongParameterList: Hilt injection, one use case per membership operation; bundling them
// into a facade would only move the list somewhere less obvious.
@Suppress("LongParameterList")
class GroupInfoViewModel @Inject constructor(
    observeGroup: ObserveGroupUseCase,
    observeGroupMembers: ObserveGroupMembersUseCase,
    observeContacts: ObserveContactsUseCase,
    private val updateGroupName: UpdateGroupNameUseCase,
    private val addGroupMembers: AddGroupMembersUseCase,
    private val removeGroupMember: RemoveGroupMemberUseCase,
    private val leaveGroup: LeaveGroupUseCase,
    private val closeGroup: CloseGroupUseCase,
    private val addContactFromGroupMember: AddContactFromGroupMemberUseCase,
    private val setAuditRetention: SetGroupAuditRetentionUseCase,
    private val setMemberAdmin: SetGroupMemberAdminUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>(GROUP_ID_KEY).orEmpty()
    private val local = MutableStateFlow(GroupInfoLocal())

    private val group: Flow<GroupSnapshot?> = observeGroup(groupId)
        .map<Group?, GroupSnapshot?> { GroupSnapshot(it) }
        .onStart { emit(null) }

    private val contacts: Flow<List<GroupPickerContact>?> = observeContacts()
        .map<List<Contact>, List<GroupPickerContact>?> { list -> list.toPickerContacts() }
        .onStart { emit(null) }

    val uiState: StateFlow<GroupInfoUiState> = combine(
        group,
        observeGroupMembers(groupId),
        contacts,
        local,
    ) { snapshot, members, available, control ->
        buildGroupInfoState(snapshot, members, available, control)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), GroupInfoUiState())

    /** Opening the member picker starts from a clean tick list rather than the last one. */
    fun onOpenDialog(dialog: GroupDialog) {
        local.update { it.copy(dialog = dialog, query = "", selected = persistentSetOf()) }
    }

    fun onDismissDialog() {
        local.update { it.copy(dialog = GroupDialog.None, query = "", selected = persistentSetOf()) }
    }

    /** Runs whatever the open dialog was asking for; renaming carries a value, so it has its own. */
    fun onConfirmDialog() {
        val control = local.value
        local.update { it.copy(dialog = GroupDialog.None, query = "", selected = persistentSetOf()) }
        viewModelScope.launch { runConfirmed(control.dialog, control.selected.toList()) }
    }

    fun onConfirmRename(name: String) {
        local.update { it.copy(dialog = GroupDialog.None) }
        if (!GroupLimits.isValidName(name)) return
        viewModelScope.launch { report(updateGroupName(groupId, name.trim())) }
    }

    fun onPickerQueryChange(value: String) = local.update { it.copy(query = value) }

    fun onPickerToggle(contactId: String) = local.update {
        it.copy(selected = it.selected.toggleWithin(uiState.value.remainingSeats, contactId))
    }

    /**
     * Sharing a group is not consent to a private chat, so this sends an ordinary contact
     * request and says so; the member stays a stranger until they accept.
     */
    fun onAddContact(identityHash: String) {
        viewModelScope.launch {
            val result = addContactFromGroupMember(groupId, identityHash)
            if (result is AppResult.Success) {
                local.update { it.copy(message = UiMessage.Text(R.string.feature_chat_group_request_sent)) }
            } else {
                report(result)
            }
        }
    }

    fun onMessageShown() = local.update { it.copy(message = null) }

    private suspend fun runConfirmed(dialog: GroupDialog, picked: List<String>) {
        when (dialog) {
            is GroupDialog.AddMembers -> report(addGroupMembers(groupId, picked))
            is GroupDialog.RemoveMember -> report(removeGroupMember(groupId, dialog.member.identityHash))
            is GroupDialog.Leave -> report(leaveGroup(groupId))
            is GroupDialog.Close -> report(closeGroup(groupId))
            is GroupDialog.AuditRetention -> report(setAuditRetention(groupId, dialog.enable))
            is GroupDialog.MemberRole ->
                report(setMemberAdmin(groupId, dialog.member.identityHash, dialog.admin))
            is GroupDialog.Rename, is GroupDialog.None -> Unit
        }
    }

    /** Success shows up in the observed list on its own; only failures need saying out loud. */
    private fun report(result: AppResult<Unit>) {
        if (result is AppResult.Error) {
            local.update { it.copy(message = UiMessage.Failure(result.error)) }
        }
    }
}

internal fun buildGroupInfoState(
    snapshot: GroupSnapshot?,
    members: List<GroupMember>,
    contacts: List<GroupPickerContact>?,
    control: GroupInfoLocal,
): GroupInfoUiState {
    val group = snapshot?.value
    val rows = members.map { it.toMemberRow() }.toImmutableList()
    return GroupInfoUiState(
        loading = snapshot == null,
        exists = group != null,
        name = group?.name.orEmpty(),
        // The same seed the chats tab gives the group's row, so one group draws one avatar
        // wherever it appears.
        // Same seed the chat list uses for this group, so its avatar matches there.
        seed = IdentitySeed(group?.avatarSeed.orEmpty().toByteArray()),
        createdByMe = group?.isCreatedByMe == true,
        closed = group?.closed == true,
        auditRetention = group?.auditRetention == true,
        members = rows,
        dialog = control.dialog,
        picker = pickerFor(control, rows, contacts),
        message = control.message,
    )
}

/**
 * The picker exists only while the sheet is open, and it offers contacts who are not members
 * already — re-adding one would be a no-op the user has no way to see.
 */
private fun pickerFor(
    control: GroupInfoLocal,
    members: ImmutableList<GroupMemberRow>,
    contacts: List<GroupPickerContact>?,
): GroupPickerState? {
    if (control.dialog !is GroupDialog.AddMembers) return null
    val present = members.mapTo(HashSet()) { it.identityHash }
    return GroupPickerState(
        // A picker row is seeded from the contact's identity hash, which is the same value
        // the member list carries in hex.
        contacts = contacts.orEmpty().filterNot { it.seed.bytes.toHex() in present }.toImmutableList(),
        selected = control.selected,
        query = control.query,
        capacity = (GroupLimits.MAX_MEMBERS - members.size).coerceAtLeast(0),
        loading = contacts == null,
    )
}

private fun GroupMember.toMemberRow(): GroupMemberRow = GroupMemberRow(
    identityHash = identityHash,
    name = displayName,
    // The identicon is seeded from the raw identity bytes so a member and their contact row
    // elsewhere in the app draw the same picture.
    seed = IdentitySeed(hexToBytes(identityHash)),
    contactId = contactId,
    isCreator = role == GroupMemberRole.CREATOR,
    isAdmin = role == GroupMemberRole.ADMIN,
    isMe = isMe,
    requestPending = contactStatus == ContactRelationshipStatus.PENDING_OUT,
)

/** Lowercase hex to bytes; anything malformed seeds nothing rather than throwing at draw time. */
internal fun hexToBytes(hex: String): ByteArray {
    if (hex.length % 2 != 0) return ByteArray(0)
    return ByteArray(hex.length / 2) { index ->
        val high = hex[index * 2].digitToIntOrNull(HEX_RADIX) ?: 0
        val low = hex[index * 2 + 1].digitToIntOrNull(HEX_RADIX) ?: 0
        ((high shl NIBBLE_BITS) or low).toByte()
    }
}

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and BYTE_MASK
        append(HEX_DIGITS[value ushr NIBBLE_BITS])
        append(HEX_DIGITS[value and LOW_NIBBLE_MASK])
    }
}
