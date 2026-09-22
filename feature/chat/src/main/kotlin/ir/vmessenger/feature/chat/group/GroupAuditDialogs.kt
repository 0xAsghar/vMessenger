package ir.vmessenger.feature.chat.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.feature.chat.R

/**
 * Switching retention on takes something away from the other members rather than from any data, so
 * the confirmation says what it will mean for them and is styled as the consequential choice.
 */
@Composable
internal fun AuditRetentionDialog(enable: Boolean, callbacks: GroupDialogCallbacks) {
    ConfirmDialog(
        title = stringResource(
            if (enable) R.string.feature_chat_group_audit_on_title else R.string.feature_chat_group_audit_off_title,
        ),
        body = stringResource(
            if (enable) R.string.feature_chat_group_audit_on_body else R.string.feature_chat_group_audit_off_body,
        ),
        confirmLabel = stringResource(R.string.feature_chat_group_audit_confirm),
        onConfirm = callbacks.onConfirm,
        onDismiss = callbacks.onDismiss,
        destructive = enable,
    )
}

@Composable
internal fun MemberRoleDialog(
    dialog: GroupDialog.MemberRole,
    unknown: String,
    callbacks: GroupDialogCallbacks,
) {
    ConfirmDialog(
        title = stringResource(
            if (dialog.admin) {
                R.string.feature_chat_group_promote_title
            } else {
                R.string.feature_chat_group_demote_title
            },
        ),
        body = stringResource(
            if (dialog.admin) R.string.feature_chat_group_promote_body else R.string.feature_chat_group_demote_body,
            dialog.member.label(unknown),
        ),
        confirmLabel = stringResource(R.string.feature_chat_group_audit_confirm),
        onConfirm = callbacks.onConfirm,
        onDismiss = callbacks.onDismiss,
    )
}
