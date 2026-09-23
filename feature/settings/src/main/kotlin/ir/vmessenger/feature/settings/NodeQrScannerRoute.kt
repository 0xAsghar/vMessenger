package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
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
            // The camera is still running under an error, and its hint sits at the bottom: the
            // message goes at the top, on a surface of its own so it reads over any picture.
            NodeScanUiState.NotANodeLink -> ScanError(stringResource(R.string.nodes_scan_invalid))
            is NodeScanUiState.Failed -> ScanError(uiState.error.toUiText())
            NodeScanUiState.Success -> {
                VmText(
                    text = stringResource(R.string.nodes_scan_success),
                    style = VmTheme.typography.bodyLg,
                    color = VmTheme.colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                VmButton(
                    text = stringResource(R.string.nodes_close),
                    onClick = onDone,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(VmSpacing.xl),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.ScanError(message: String) {
    VmSurface(
        shape = VmShapes.field,
        color = VmTheme.colors.bgCriticalSubtle,
        contentColor = VmTheme.colors.textCritical,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(VmSpacing.lg),
    ) {
        VmText(
            text = message,
            style = VmTheme.typography.bodyMd,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        )
    }
}
