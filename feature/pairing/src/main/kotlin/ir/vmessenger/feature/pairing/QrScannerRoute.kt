package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.error.toUiText

@Composable
fun QrScannerRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QrScannerScreen(
        title = stringResource(R.string.scan_qr_title),
        hint = stringResource(R.string.scan_qr_hint),
        onNavigateBack = onNavigateBack,
        scanPaused = uiState is AddContactUiState.Success,
        onQrScanned = viewModel::onQrScanned,
        overlay = {
            ContactQrScannerOverlay(uiState = uiState, onDone = onDone)
        },
    )
}

@Composable
private fun ContactQrScannerOverlay(
    uiState: AddContactUiState,
    onDone: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is AddContactUiState.Error -> {
                Text(
                    text = uiState.error.toUiText(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AddContactUiState.Success -> {
                Text(
                    text = stringResource(R.string.add_contact_success),
                    modifier = Modifier.align(Alignment.Center),
                )
                Button(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                ) {
                    Text(stringResource(R.string.add_contact_done))
                }
            }
            else -> Unit
        }
    }
}
