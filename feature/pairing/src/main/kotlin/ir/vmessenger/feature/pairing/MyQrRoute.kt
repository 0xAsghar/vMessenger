package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.QrCard
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

@Composable
fun MyQrRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: MyQrViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = stringResource(R.string.my_qr_title),
        onNavigateBack = onNavigateBack,
        scrolled = scroll.canScrollBackward,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                MyQrUiState.Loading -> VmProgressIndicator()
                MyQrUiState.NoIdentity -> VmText(stringResource(R.string.pairing_no_identity))
                MyQrUiState.Error -> VmText(stringResource(R.string.my_qr_error))
                // The code sits on its own soft card so it reads as a thing to show someone.
                is MyQrUiState.Ready -> VmSurface(
                    modifier = Modifier.padding(VmSpacing.lg),
                    shape = VmShapes.dialog,
                    color = VmTheme.colors.bgSubtle,
                ) {
                    QrCard(
                        payload = state.qrPayload,
                        userHash = state.userHash,
                    )
                }
            }
        }
    }
}
