package ir.vmessenger.feature.settings

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
import ir.vmessenger.feature.pairing.QrScannerScreen

@Composable
fun NodeQrScannerRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: NodeQrScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QrScannerScreen(
        title = stringResource(R.string.nodes_scan_title),
        hint = stringResource(R.string.nodes_scan_hint),
        onNavigateBack = onNavigateBack,
        scanPaused = uiState is NodeScanUiState.Success,
        onQrScanned = viewModel::onQrScanned,
        overlay = {
            NodeQrScannerOverlay(uiState = uiState, onDone = onDone)
        },
    )
}

@Composable
private fun NodeQrScannerOverlay(
    uiState: NodeScanUiState,
    onDone: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is NodeScanUiState.Error -> {
                Text(
                    text = uiState.message,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            NodeScanUiState.Success -> {
                Text(
                    text = stringResource(R.string.nodes_scan_success),
                    modifier = Modifier.align(Alignment.Center),
                )
                Button(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                ) {
                    Text(stringResource(R.string.nodes_close))
                }
            }
            else -> Unit
        }
    }
}
