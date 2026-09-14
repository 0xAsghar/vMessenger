package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

/** The three attachment sources offered in 1.0; the camera tile is deliberately out of scope. */
@Composable
fun AttachmentSheet(
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VmBottomSheet(
        title = stringResource(R.string.vm_attach_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            AttachmentTile(
                icon = Icons.Outlined.Photo,
                label = stringResource(R.string.vm_attach_photo),
                onClick = onPickPhoto,
                modifier = Modifier.weight(1f),
            )
            AttachmentTile(
                icon = Icons.Outlined.Movie,
                label = stringResource(R.string.vm_attach_video),
                onClick = onPickVideo,
                modifier = Modifier.weight(1f),
            )
            AttachmentTile(
                icon = Icons.Outlined.InsertDriveFile,
                label = stringResource(R.string.vm_attach_file),
                onClick = onPickFile,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AttachmentTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = VmSpacing.lg),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(VmSizes.iconLg),
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
