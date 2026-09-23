package ir.vmessenger.feature.chat.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.AvatarVariant
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmInputDialog
import ir.vmessenger.core.designsystem.component.VmListRow
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.component.asText
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.feature.chat.R

private const val MEMBER_CONTENT_TYPE = "group-member"
private const val SKELETON_ROWS = 6

/** What a member row can ask for. */
@Immutable
private data class GroupInfoCallbacks(
    val onOpenDialog: (GroupDialog) -> Unit,
    val onAddContact: (String) -> Unit,
    val onOpenContact: (String) -> Unit,
    val onOpenAudit: () -> Unit,
)

/** The confirmations and the member sheet all answer through these. */
@Immutable
internal data class GroupDialogCallbacks(
    val onConfirm: () -> Unit,
    val onRename: (String) -> Unit,
    val onDismiss: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggle: (String) -> Unit,
)

/**
 * A group's own screen, reached as `VmRoute.GroupInfo`.
 *
 * It pops itself once the group stops existing, which is what happens right after leaving or
 * closing it. A closed group still renders, read-only and behind a banner: the history is the
 * reason to keep the screen reachable at all.
 */
@Composable
fun GroupInfoRoute(
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberVmSnackbar()

    // asText() resolves a string resource, so the message is rendered here and only the
    // finished sentence reaches the host.
    val message = state.message?.asText()
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    VMessengerScaffold(
        title = state.name.ifBlank { stringResource(R.string.feature_chat_group_info_title) },
        onNavigateBack = onBack,
        modifier = modifier,
        subtitle = stringResource(
            R.string.feature_chat_group_member_count,
            VmTextFormat.digits(state.memberCount.toString()),
        ),
        snackbarHost = { VmSnackbarHost(snackbar) },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        if (state.loading) {
            SkeletonList(modifier = content, rows = SKELETON_ROWS)
        } else {
            GroupInfoList(
                state = state,
                callbacks = remember(viewModel, onOpenContact, onOpenAudit) {
                    GroupInfoCallbacks(
                        onOpenDialog = viewModel::onOpenDialog,
                        onAddContact = viewModel::onAddContact,
                        onOpenContact = onOpenContact,
                        onOpenAudit = onOpenAudit,
                    )
                },
                modifier = content,
            )
        }
    }

    GroupDialogHost(
        state = state,
        callbacks = remember(viewModel) {
            GroupDialogCallbacks(
                onConfirm = viewModel::onConfirmDialog,
                onRename = viewModel::onConfirmRename,
                onDismiss = viewModel::onDismissDialog,
                onQueryChange = viewModel::onPickerQueryChange,
                onToggle = viewModel::onPickerToggle,
            )
        },
    )
}

@Composable
private fun GroupInfoList(
    state: GroupInfoUiState,
    callbacks: GroupInfoCallbacks,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.feature_chat_group_member_unknown)
    LazyColumn(modifier = modifier) {
        item(key = "header") { GroupInfoHeader(state = state) }
        if (state.canManage) {
            item(key = "manage") { GroupManageSection(state = state, onOpenDialog = callbacks.onOpenDialog) }
        }
        if (state.canReviewAudit) {
            item(key = "audit") {
                SettingsRow(
                    label = stringResource(R.string.feature_chat_group_audit_open),
                    icon = Icons.Outlined.History,
                    trailing = SettingsTrailing.Chevron,
                    onClick = callbacks.onOpenAudit,
                )
            }
        }
        item(key = "members-header") {
            SectionHeader(title = stringResource(R.string.feature_chat_group_members_section))
        }
        items(items = state.members, key = { it.identityHash }, contentType = { MEMBER_CONTENT_TYPE }) { member ->
            MemberRow(
                member = member,
                unknown = unknown,
                canRemove = state.canManage,
                callbacks = callbacks,
            )
        }
        item(key = "danger") { GroupDangerSection(state = state, onOpenDialog = callbacks.onOpenDialog) }
    }
}

@Composable
private fun GroupInfoHeader(state: GroupInfoUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        if (state.closed) {
            VmSurface(
                color = VmTheme.colors.bgCriticalSubtle,
                contentColor = VmTheme.colors.textCritical,
                modifier = Modifier.fillMaxWidth(),
            ) {
                VmText(
                    text = stringResource(R.string.feature_chat_group_closed_banner),
                    style = VmTheme.typography.bodyMd,
                    modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
                )
            }
        }
        if (state.auditRetention) {
            // Shown to everyone, not only the creator. The feature is defensible because the
            // people it applies to are told it applies to them, so this banner is not decoration.
            VmSurface(
                color = VmTheme.colors.bgWarningSubtle,
                contentColor = VmTheme.colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                VmText(
                    text = stringResource(R.string.feature_chat_group_audit_banner),
                    style = VmTheme.typography.bodyMd,
                    modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
                )
            }
        }
        Avatar(
            seed = state.seed.bytes,
            name = state.name,
            size = VmSizes.avatarLg,
            variant = AvatarVariant.Group,
            modifier = Modifier.padding(top = VmSpacing.md),
            contentDescription = stringResource(R.string.feature_chat_group_avatar, state.name),
        )
        VmText(text = state.name, style = VmTheme.typography.headingMd)
        VmText(
            text = stringResource(
                R.string.feature_chat_group_member_count,
                VmTextFormat.digits(state.memberCount.toString()),
            ),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
        )
    }
}

/** Creator-only, and only while the group is open; a closed group offers nothing here. */
@Composable
private fun GroupManageSection(state: GroupInfoUiState, onOpenDialog: (GroupDialog) -> Unit) {
    SectionHeader(title = stringResource(R.string.feature_chat_group_manage_section))
    SettingsRow(
        label = stringResource(R.string.feature_chat_group_rename),
        icon = Icons.Outlined.DriveFileRenameOutline,
        trailing = SettingsTrailing.None,
        onClick = { onOpenDialog(GroupDialog.Rename) },
    )
    SettingsRow(
        label = stringResource(R.string.feature_chat_group_add_members),
        icon = Icons.Outlined.GroupAdd,
        // Stays visible when the group is full so the reason is the disabled state, not an
        // action that quietly disappeared.
        supporting = stringResource(
            R.string.feature_chat_group_seats_left,
            VmTextFormat.digits(state.remainingSeats.toString()),
        ),
        trailing = SettingsTrailing.None,
        enabled = state.canAddMembers,
        onClick = { onOpenDialog(GroupDialog.AddMembers) },
    )
}

@Composable
private fun MemberRow(
    member: GroupMemberRow,
    unknown: String,
    canRemove: Boolean,
    callbacks: GroupInfoCallbacks,
) {
    val name = member.label(unknown)
    val contactId = member.contactId?.takeIf { !member.isMe }
    VmListRow(
        title = name,
        modifier = if (contactId != null) {
            Modifier.clickable { callbacks.onOpenContact(contactId) }
        } else {
            Modifier
        },
        subtitle = {
            Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs)) {
                if (member.isCreator) {
                    MemberChip(label = stringResource(R.string.feature_chat_group_badge_creator))
                }
                if (member.isMe) {
                    MemberChip(label = stringResource(R.string.feature_chat_group_badge_me))
                }
                if (member.requestPending) {
                    MemberChip(label = stringResource(R.string.feature_chat_status_pending_out))
                }
            }
        },
        trailing = { MemberActions(member = member, canRemove = canRemove, callbacks = callbacks) },
        avatar = { Avatar(seed = member.seed.bytes, name = name, size = VmSizes.avatarMd) },
    )
}

@Composable
private fun MemberActions(
    member: GroupMemberRow,
    canRemove: Boolean,
    callbacks: GroupInfoCallbacks,
) {
    if (member.canBeAddedAsContact) {
        VmIconButton(
            icon = Icons.Outlined.PersonAddAlt,
            contentDescription = stringResource(R.string.feature_chat_group_add_contact),
            onClick = { callbacks.onAddContact(member.identityHash) },
        )
    }
    // The creator cannot remove themselves out of a group they own; closing it is their exit.
    if (canRemove && !member.isMe) {
        VmIconButton(
            icon = Icons.Outlined.PersonRemove,
            contentDescription = stringResource(R.string.feature_chat_group_remove_member),
            onClick = { callbacks.onOpenDialog(GroupDialog.RemoveMember(member)) },
            tint = VmTheme.colors.iconCritical,
        )
    }
}

@Composable
private fun MemberChip(label: String) {
    VmSurface(
        shape = VmShapes.pill,
        color = VmTheme.colors.bgSubtleStrong,
        contentColor = VmTheme.colors.textSecondary,
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodyXsMedium,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}

/** Leaving and closing are the same door seen from either side, so only one of them shows. */
@Composable
private fun GroupDangerSection(state: GroupInfoUiState, onOpenDialog: (GroupDialog) -> Unit) {
    if (!state.canLeave && !state.canClose) return
    if (state.canLeave) {
        SettingsRow(
            label = stringResource(R.string.feature_chat_group_leave),
            icon = Icons.AutoMirrored.Outlined.Logout,
            trailing = SettingsTrailing.None,
            destructive = true,
            onClick = { onOpenDialog(GroupDialog.Leave) },
        )
    }
    if (state.canAssignRoles) {
        SettingsRow(
            label = stringResource(R.string.feature_chat_group_audit_row),
            icon = Icons.Outlined.History,
            supporting = stringResource(R.string.feature_chat_group_audit_row_body),
            // The switch and the row both open the confirmation rather than flipping the
            // policy: this one is announced to everybody, so it does not happen on a stray tap.
            trailing = SettingsTrailing.Switch(state.auditRetention) { wanted ->
                onOpenDialog(GroupDialog.AuditRetention(wanted))
            },
        )
    }
    if (state.canClose) {
        SettingsRow(
            label = stringResource(R.string.feature_chat_group_close),
            icon = Icons.Outlined.Lock,
            trailing = SettingsTrailing.None,
            destructive = true,
            onClick = { onOpenDialog(GroupDialog.Close) },
        )
    }
}

@Composable
private fun GroupDialogHost(state: GroupInfoUiState, callbacks: GroupDialogCallbacks) {
    val unknown = stringResource(R.string.feature_chat_group_member_unknown)
    when (val dialog = state.dialog) {
        GroupDialog.None -> Unit
        GroupDialog.Rename -> RenameGroupDialog(
            currentName = state.name,
            onSave = callbacks.onRename,
            onDismiss = callbacks.onDismiss,
        )
        GroupDialog.AddMembers -> state.picker?.let { picker ->
            GroupMemberPickerSheet(
                state = picker,
                onQueryChange = callbacks.onQueryChange,
                onToggle = callbacks.onToggle,
                onConfirm = callbacks.onConfirm,
                onDismiss = callbacks.onDismiss,
            )
        }
        GroupDialog.Leave -> ConfirmDialog(
            title = stringResource(R.string.feature_chat_group_leave_title),
            body = stringResource(R.string.feature_chat_group_leave_body),
            confirmLabel = stringResource(R.string.feature_chat_group_leave_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
        GroupDialog.Close -> ConfirmDialog(
            title = stringResource(R.string.feature_chat_group_close_title),
            body = stringResource(R.string.feature_chat_group_close_body),
            confirmLabel = stringResource(R.string.feature_chat_group_close_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
        is GroupDialog.RemoveMember -> ConfirmDialog(
            title = stringResource(R.string.feature_chat_group_remove_title),
            body = stringResource(R.string.feature_chat_group_remove_body, dialog.member.label(unknown)),
            confirmLabel = stringResource(R.string.feature_chat_group_remove_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
        is GroupDialog.AuditRetention -> AuditRetentionDialog(dialog.enable, callbacks)
        is GroupDialog.MemberRole -> MemberRoleDialog(dialog, unknown, callbacks)
    }
}

/** The name is announced to every member, which is why it is not edited in place on the header. */
@Composable
private fun RenameGroupDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()
    VmInputDialog(
        title = stringResource(R.string.feature_chat_group_rename_title),
        confirmLabel = stringResource(R.string.feature_chat_group_rename_save),
        onConfirm = { onSave(trimmed) },
        onDismiss = onDismiss,
        confirmEnabled = GroupLimits.isValidName(name),
        dismissLabel = stringResource(R.string.feature_chat_group_rename_cancel),
    ) {
        VmTextField(
            value = name,
            onValueChange = { if (it.length <= GroupLimits.MAX_NAME_LENGTH) name = it },
            config = VmTextFieldConfig(
                label = stringResource(R.string.feature_chat_group_name_label),
                isError = !GroupLimits.isValidName(name),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
