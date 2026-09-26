package ir.vmessenger.feature.contacts

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.KeyChangeBanner
import ir.vmessenger.core.designsystem.component.SafetyNumberDisplay
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UiMessageBus
import ir.vmessenger.core.designsystem.component.UiMessageSnackbarEffect
import ir.vmessenger.core.designsystem.component.UserHashShareRow
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/** The taps the detail screen can produce, bundled to keep the content signatures short. */
@Immutable
private data class ContactDetailCallbacks(
    val onStartChat: () -> Unit,
    val onStartCall: () -> Unit,
    val onResend: () -> Unit,
    val onAcceptKeyChange: () -> Unit,
    val onVerifiedChange: (Boolean) -> Unit,
    val onLocationAccess: (Boolean) -> Unit,
    val onMenuAction: (ContactSheetAction) -> Unit,
)

/**
 * A contact's own screen, reached as `VmRoute.ContactDetail`.
 *
 * It pops itself when the contact stops existing, which is what happens right after the delete
 * confirmation; the snackbar is handed to the contacts list through [UiMessageBus].
 */
@Composable
fun ContactDetailRoute(
    onNavigateBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onStartCall: (String) -> Unit = {},
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberVmSnackbar()
    UiMessageSnackbarEffect(messages = viewModel.messages, hostState = snackbarHost)
    LaunchedEffect(Unit) {
        viewModel.openConversation.collect { conversationId -> onOpenConversation(conversationId) }
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onNavigateBack()
    }

    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = state.contact?.name ?: stringResource(R.string.contact_detail_title),
        onNavigateBack = onNavigateBack,
        scrolled = scroll.canScrollBackward,
        snackbarHost = { VmSnackbarHost(snackbarHost) },
    ) { padding ->
        val contact = state.contact
        if (contact == null) {
            SkeletonList(modifier = Modifier.fillMaxSize().padding(padding), rows = SKELETON_ROWS)
        } else {
            ContactDetailContent(
                state = state,
                contact = contact,
                padding = padding,
                scroll = scroll,
                callbacks = ContactDetailCallbacks(
                    onStartChat = viewModel::onStartChat,
                    onStartCall = { onStartCall(contact.id) },
                    onResend = viewModel::onResendRequest,
                    onAcceptKeyChange = viewModel::onAcceptKeyChange,
                    onVerifiedChange = viewModel::onVerifiedChange,
                    onLocationAccess = viewModel::onLocationAccessChange,
                    onMenuAction = viewModel::onMenuAction,
                ),
            )
        }
    }

    ContactDialogHost(
        dialog = state.dialog,
        callbacks = ContactDialogCallbacks(
            onConfirm = viewModel::confirmPendingAction,
            onRename = viewModel::confirmRename,
            onDismiss = viewModel::dismissDialog,
        ),
    )
}

@Composable
private fun ContactDetailContent(
    state: ContactDetailUiState,
    contact: ContactRow,
    padding: PaddingValues,
    scroll: ScrollState,
    callbacks: ContactDetailCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        if (contact.keyChangePending) {
            KeyChangeBanner(contactName = contact.name, onVerify = callbacks.onAcceptKeyChange)
        }
        ContactDetailHeader(contact = contact)
        ContactPrimaryActions(
            contact = contact,
            canResend = state.canResendRequest,
            callbacks = callbacks,
        )
        state.location?.let { ContactLocationCard(location = it) }
        if (contact.isApproved) ContactLocationHistory(history = state.locationHistory)
        state.safetyNumberKeys?.let { (local, remote) ->
            SafetyNumberDisplay(localPublicKey = local, remotePublicKey = remote)
            // Sits under the number it refers to: the switch only records that the user
            // compared it out of band, which is why the supporting text says how.
            SettingsRow(
                label = stringResource(R.string.contact_detail_verified),
                icon = Icons.Outlined.VerifiedUser,
                supporting = stringResource(R.string.contact_detail_verified_body),
                trailing = SettingsTrailing.Switch(
                    checked = contact.verified,
                    onCheckedChange = callbacks.onVerifiedChange,
                ),
            )
        }
        SectionHeader(title = stringResource(R.string.contact_detail_privacy_section))
        SettingsRow(
            label = stringResource(R.string.contact_detail_location_access),
            icon = Icons.Outlined.MyLocation,
            supporting = stringResource(R.string.contact_detail_location_access_body),
            trailing = SettingsTrailing.Switch(
                checked = state.canSeeMyLocation,
                onCheckedChange = callbacks.onLocationAccess,
            ),
            enabled = contact.canChat,
        )
        ContactDangerSection(contact = contact, onMenuAction = callbacks.onMenuAction)
    }
}

@Composable
private fun ContactDetailHeader(contact: ContactRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Avatar(
            seed = contact.identityHash,
            name = contact.name,
            size = VmSizes.avatarLg,
            contentDescription = stringResource(R.string.contact_detail_avatar, contact.name),
        )
        VmText(text = contact.name, style = VmTheme.typography.headingMd, color = VmTheme.colors.textPrimary)
        UserHashText(text = contact.userHash, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
            if (contact.blocked) {
                BlockedChip()
            }
            if (!contact.isApproved) {
                ContactStatusChip(status = contact.status)
            }
        }
        UserHashShareRow(userHash = contact.userHash)
    }
}

@Composable
private fun ContactPrimaryActions(
    contact: ContactRow,
    canResend: Boolean,
    callbacks: ContactDetailCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
            VmButton(
                text = stringResource(R.string.contacts_sheet_chat),
                onClick = callbacks.onStartChat,
                enabled = contact.canChat,
                leadingIcon = Icons.Outlined.ChatBubbleOutline,
                modifier = Modifier.weight(1f),
            )
            VmOutlinedButton(
                text = stringResource(R.string.contacts_call),
                onClick = callbacks.onStartCall,
                enabled = contact.canChat,
                leadingIcon = Icons.Outlined.Call,
                modifier = Modifier.weight(1f),
            )
        }
        if (canResend) {
            VmOutlinedButton(
                text = stringResource(R.string.contacts_resend_request),
                onClick = callbacks.onResend,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Rename, block/unblock and delete. Each opens a confirmation before anything happens. */
@Composable
private fun ContactDangerSection(
    contact: ContactRow,
    onMenuAction: (ContactSheetAction) -> Unit,
) {
    SectionHeader(title = stringResource(R.string.contact_detail_manage_section))
    SettingsRow(
        label = stringResource(R.string.contacts_sheet_rename),
        icon = Icons.Outlined.DriveFileRenameOutline,
        trailing = SettingsTrailing.None,
        onClick = { onMenuAction(ContactSheetAction.RENAME) },
    )
    // Only the two destructive rows are tinted; undoing a block is not destructive.
    if (contact.blocked) {
        SettingsRow(
            label = stringResource(R.string.contacts_sheet_unblock),
            icon = Icons.Outlined.LockOpen,
            trailing = SettingsTrailing.None,
            onClick = { onMenuAction(ContactSheetAction.UNBLOCK) },
        )
    }
    if (!contact.blocked) {
        SettingsRow(
            label = stringResource(R.string.contacts_sheet_block),
            icon = Icons.Outlined.Block,
            trailing = SettingsTrailing.None,
            destructive = true,
            onClick = { onMenuAction(ContactSheetAction.BLOCK) },
        )
    }
    SettingsRow(
        label = stringResource(R.string.contact_detail_delete),
        icon = Icons.Outlined.DeleteOutline,
        trailing = SettingsTrailing.None,
        destructive = true,
        onClick = { onMenuAction(ContactSheetAction.DELETE) },
    )
}

private const val SKELETON_ROWS = 4
