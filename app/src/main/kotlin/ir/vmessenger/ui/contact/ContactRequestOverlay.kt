package ir.vmessenger.ui.contact

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.VmDialog
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmTextFormat

@Composable
fun ContactRequestOverlay(
    viewModel: ContactRequestViewModel = hiltViewModel(),
) {
    val pending by viewModel.pendingRequest.collectAsStateWithLifecycle()
    val request = pending ?: return
    VmDialog(
        onDismissRequest = viewModel::dismiss,
        title = stringResource(R.string.contact_request_title),
        buttons = {
            VmTextButton(text = stringResource(R.string.contact_request_reject), onClick = viewModel::reject)
            VmTextButton(text = stringResource(R.string.contact_request_approve), onClick = viewModel::approve)
        },
    ) {
        // Both isolated: a Persian sentence that opened with a Latin name took the name's direction
        // for the whole paragraph, and was laid out left to right.
        VmText(
            text = stringResource(
                R.string.contact_request_body,
                VmTextFormat.isolate(request.requesterDisplayName),
                VmTextFormat.isolate(request.requesterUserHash),
            ),
        )
    }
}
