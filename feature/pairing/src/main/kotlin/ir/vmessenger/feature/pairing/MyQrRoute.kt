package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.QrCard
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

@Composable
fun MyQrRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: MyQrViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    VMessengerScaffold(
        title = stringResource(R.string.my_qr_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                MyQrUiState.Loading -> CircularProgressIndicator()
                MyQrUiState.NoIdentity -> Text(stringResource(R.string.pairing_no_identity))
                MyQrUiState.Error -> Text(stringResource(R.string.my_qr_error))
                is MyQrUiState.Ready -> QrCard(
                    payload = state.qrPayload,
                    userHash = state.userHash,
                )
            }
        }
    }
}
