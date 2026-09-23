package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Shown above a conversation or on a contact detail when the peer's identity key changed: a warm
 * band with the sentence that names the risk and, under it, what to do about it. The shield is
 * decorative; the sentence already says everything.
 */
@Composable
fun KeyChangeBanner(
    contactName: String,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val c = VmTheme.colors
    VmSurface(
        color = c.keyChangeWarning,
        contentColor = c.textPrimary,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = VmSpacing.lg, end = VmSpacing.sm, top = VmSpacing.md)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
                modifier = Modifier.padding(end = VmSpacing.sm),
            ) {
                VmIcon(imageVector = Icons.Outlined.GppMaybe, contentDescription = null, tint = c.textWarning)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs)) {
                    VmText(
                        text = stringResource(R.string.vm_key_change_title, contactName),
                        style = VmTheme.typography.bodyMdMedium,
                    )
                    VmText(
                        text = stringResource(R.string.vm_key_change_body),
                        style = VmTheme.typography.bodySm,
                        color = c.textSecondary,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = VmSpacing.xs),
            ) {
                if (onDismiss != null) {
                    VmTextButton(text = stringResource(R.string.vm_key_change_dismiss), onClick = onDismiss)
                }
                VmTextButton(text = stringResource(R.string.vm_key_change_verify), onClick = onVerify)
            }
        }
    }
}
