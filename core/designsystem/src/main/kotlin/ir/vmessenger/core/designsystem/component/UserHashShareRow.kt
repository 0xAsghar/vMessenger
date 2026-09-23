package ir.vmessenger.core.designsystem.component

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.foundation.rememberCopyToClipboard
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

@Composable
fun UserHashShareRow(
    userHash: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val copy = rememberCopyToClipboard(stringResource(R.string.hash_copied))
    val shareLabel = stringResource(R.string.hash_share_chooser)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = VmSpacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmIconButton(
            icon = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.hash_copy),
            onClick = { copy(userHash) },
            tint = VmTheme.colors.iconSecondary,
        )
        VmIconButton(
            icon = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.hash_share),
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, userHash)
                }
                context.startActivity(Intent.createChooser(intent, shareLabel))
            },
            tint = VmTheme.colors.iconSecondary,
        )
    }
}

@Composable
fun UserHashLabel(
    modifier: Modifier = Modifier,
) {
    VmText(
        text = stringResource(R.string.user_hash_label),
        modifier = modifier,
        style = VmTheme.typography.bodySmMedium,
        color = VmTheme.colors.textSecondary,
    )
}
