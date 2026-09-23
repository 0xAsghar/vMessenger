package ir.vmessenger.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R

/**
 * What can be attached, as a plain list of actions like every other sheet in the app. The three
 * sources offered in 1.0; the camera is deliberately out of scope.
 */
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
        SettingsRow(
            label = stringResource(R.string.vm_attach_photo),
            icon = Icons.Outlined.Photo,
            trailing = SettingsTrailing.None,
            onClick = onPickPhoto,
        )
        SettingsRow(
            label = stringResource(R.string.vm_attach_video),
            icon = Icons.Outlined.Movie,
            trailing = SettingsTrailing.None,
            onClick = onPickVideo,
        )
        SettingsRow(
            label = stringResource(R.string.vm_attach_file),
            icon = Icons.Outlined.InsertDriveFile,
            trailing = SettingsTrailing.None,
            onClick = onPickFile,
        )
    }
}
