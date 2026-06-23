package ir.vmessenger.feature.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationRoute(
    viewModel: LocationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.feature_location_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.latestSample != null) {
                        Text(
                            text = stringResource(
                                R.string.feature_location_coords,
                                state.latestSample!!.latitude,
                                state.latestSample!!.longitude,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(text = stringResource(R.string.feature_location_map_placeholder))
                    }
                }
            }
            Text(text = stringResource(R.string.feature_location_sharing_hint))
            Button(
                onClick = { viewModel.toggleSharing() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state.sharing) {
                        stringResource(R.string.feature_location_stop)
                    } else {
                        stringResource(R.string.feature_location_start)
                    },
                )
            }
        }
    }
}
