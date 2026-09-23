package ir.vmessenger.feature.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VmBottomSheet

/**
 * Long-press actions for one contact.
 *
 * Every entry is a [SettingsRow], so each keeps the touch target and reads its label to a screen
 * reader; the destructive pair is marked as such, which colours icon and label together.
 */
@Composable
internal fun ContactActionsSheet(
    contact: ContactRow,
    onAction: (ContactSheetAction) -> Unit,
    onDismiss: () -> Unit,
) {
    VmBottomSheet(title = contact.name, onDismiss = onDismiss) {
        if (contact.canChat) {
            SettingsRow(
                label = stringResource(R.string.contacts_sheet_chat),
                icon = Icons.AutoMirrored.Outlined.Chat,
                trailing = SettingsTrailing.None,
                onClick = { onAction(ContactSheetAction.CHAT) },
            )
        }
        SettingsRow(
            label = stringResource(R.string.contacts_sheet_rename),
            icon = Icons.Outlined.DriveFileRenameOutline,
            trailing = SettingsTrailing.None,
            onClick = { onAction(ContactSheetAction.RENAME) },
        )
        if (contact.blocked) {
            SettingsRow(
                label = stringResource(R.string.contacts_sheet_unblock),
                icon = Icons.Outlined.LockOpen,
                trailing = SettingsTrailing.None,
                onClick = { onAction(ContactSheetAction.UNBLOCK) },
            )
        }
        // Only the destructive rows are tinted; undoing a block is not destructive.
        if (!contact.blocked) {
            SettingsRow(
                label = stringResource(R.string.contacts_sheet_block),
                icon = Icons.Outlined.Block,
                trailing = SettingsTrailing.None,
                destructive = true,
                onClick = { onAction(ContactSheetAction.BLOCK) },
            )
        }
        SettingsRow(
            label = stringResource(R.string.contacts_sheet_delete),
            icon = Icons.Outlined.DeleteOutline,
            trailing = SettingsTrailing.None,
            destructive = true,
            onClick = { onAction(ContactSheetAction.DELETE) },
        )
    }
}
