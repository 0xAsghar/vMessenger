package ir.vmessenger.feature.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VmBottomSheet

/**
 * Long-press actions for one contact.
 *
 * Every entry is a [SettingsRow], so each keeps the 48 dp touch target and reads its label to a
 * screen reader; the destructive pair is tinted by overriding the content colour rather than by
 * colouring the text alone.
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
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
            if (!contact.blocked) {
                SettingsRow(
                    label = stringResource(R.string.contacts_sheet_block),
                    icon = Icons.Outlined.Block,
                    trailing = SettingsTrailing.None,
                    onClick = { onAction(ContactSheetAction.BLOCK) },
                )
            }
            SettingsRow(
                label = stringResource(R.string.contacts_sheet_delete),
                icon = Icons.Outlined.DeleteOutline,
                trailing = SettingsTrailing.None,
                onClick = { onAction(ContactSheetAction.DELETE) },
            )
        }
    }
}
