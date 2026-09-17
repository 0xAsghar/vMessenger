package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Scan a contact's QR code.
 *
 * The screen closes itself once the scan has an answer, either way, and the answer appears as a
 * snackbar on whatever the user lands back on. It used to stay open on a bare centred label with a
 * manual «بازگشت» button, and on failure it drew a red line of text that never timed out and never
 * reset — so a bad code left the scanner permanently accusing the user of nothing in particular.
 *
 * The message travels on [ir.vmessenger.core.designsystem.component.UiMessageBus] rather than a
 * host here: this composition is being popped, so a snackbar shown in it would race the pop.
 */
@Composable
fun QrScannerRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Keyed on the state so a second outcome re-fires. The graph pops this entry only while it is
    // still on top, so a repeat cannot take the contacts tab underneath with it.
    LaunchedEffect(uiState) {
        if (uiState is AddContactUiState.Success || uiState is AddContactUiState.Error) onDone()
    }

    QrScannerScreen(
        title = stringResource(R.string.scan_qr_title),
        hint = stringResource(R.string.scan_qr_hint),
        onNavigateBack = onNavigateBack,
        // Stop analysing the moment there is an answer, so the camera is not still firing
        // barcodes at a ViewModel whose screen is on its way out.
        scanPaused = !uiState.acceptsScan || uiState is AddContactUiState.Error,
        onQrScanned = viewModel::onQrScanned,
        // Saving is a database write now — the request goes out in the background — but the camera
        // has already stopped, so say something is happening rather than show an empty frame.
        overlay = { if (uiState is AddContactUiState.Saving) SavingIndicator() },
    )
}

@Composable
private fun SavingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
