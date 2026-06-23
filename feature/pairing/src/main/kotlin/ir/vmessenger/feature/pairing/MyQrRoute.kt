package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.QrCard

@Composable
fun MyQrRoute(viewModel: MyQrViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = uiState) {
            MyQrUiState.Loading -> CircularProgressIndicator()
            MyQrUiState.NoIdentity -> Text(stringResource(R.string.pairing_no_identity))
            is MyQrUiState.Ready -> QrCard(
                payload = state.qrPayload,
                userHash = state.userHash,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
