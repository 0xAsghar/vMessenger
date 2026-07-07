package ir.vmessenger.ui.contact

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.R

@Composable
fun ContactRequestOverlay(
    viewModel: ContactRequestViewModel = hiltViewModel(),
) {
    val pending by viewModel.pendingRequest.collectAsStateWithLifecycle()
    val request = pending ?: return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(text = stringResource(R.string.contact_request_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.contact_request_body,
                    request.requesterDisplayName,
                    request.requesterUserHash,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::approve) {
                Text(text = stringResource(R.string.contact_request_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::reject) {
                Text(text = stringResource(R.string.contact_request_reject))
            }
        },
    )
}
