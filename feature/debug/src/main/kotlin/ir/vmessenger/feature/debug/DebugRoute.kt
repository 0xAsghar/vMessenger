package ir.vmessenger.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugRoute(
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.feature_debug_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.feature_debug_dht_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.feature_debug_bootstrapped, state.bootstrapped))
            Text(text = stringResource(R.string.feature_debug_known_nodes, state.knownNodes))
            Text(text = stringResource(R.string.feature_debug_endpoint, state.publishedEndpoint ?: "—"))
            state.lastError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = { viewModel.joinAndPublish() }) {
                Text(text = stringResource(R.string.feature_debug_join_publish))
            }

            Text(
                text = stringResource(R.string.feature_debug_adb_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.feature_debug_adb_instructions))
            Text(
                text = "adb forward tcp:46555 tcp:46555\nadb forward tcp:${state.forwardPort} tcp:${state.listenPort}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
