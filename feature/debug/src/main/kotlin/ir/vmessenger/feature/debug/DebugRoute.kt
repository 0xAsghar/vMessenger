package ir.vmessenger.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle

@Composable
fun DebugRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    VMessengerScaffold(
        title = stringResource(R.string.feature_debug_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.feature_debug_dht_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.feature_debug_bootstrapped, state.bootstrapped))
            Text(text = stringResource(R.string.feature_debug_known_nodes, state.knownNodes))
            Text(
                text = stringResource(R.string.feature_debug_endpoint, state.publishedEndpoint ?: "—"),
                style = UserHashTextStyle,
            )
            state.lastError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = { viewModel.joinAndPublish() }) {
                Text(text = stringResource(R.string.feature_debug_join_publish))
            }

            Text(
                text = stringResource(R.string.feature_debug_adb_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.feature_debug_adb_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "adb forward tcp:46555 tcp:46555\nadb forward tcp:${state.forwardPort} tcp:${state.listenPort}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
