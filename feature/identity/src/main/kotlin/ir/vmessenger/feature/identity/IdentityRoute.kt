package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.UserHashLabel
import ir.vmessenger.core.designsystem.component.UserHashShareRow
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

@Composable
fun IdentityRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    VMessengerScaffold(
        title = stringResource(R.string.my_identity_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val state = uiState) {
                IdentityUiState.Loading -> CircularProgressIndicator()
                IdentityUiState.None -> Text(text = stringResource(R.string.my_identity_none))
                is IdentityUiState.Loaded -> IdentityLoadedContent(
                    displayName = state.identity.displayName,
                    userHash = state.identity.userHash,
                    onSaveDisplayName = viewModel::updateDisplayName,
                )
            }
        }
    }
}

@Composable
private fun IdentityLoadedContent(
    displayName: String,
    userHash: String,
    onSaveDisplayName: (String) -> Unit,
) {
    var name by rememberSaveable(displayName) { mutableStateOf(displayName) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.my_identity_display_name_label)) },
        singleLine = true,
    )
    Button(
        onClick = { onSaveDisplayName(name.trim()) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        enabled = name.trim().length in CreateIdentityViewModel.DISPLAY_NAME_MIN..CreateIdentityViewModel.DISPLAY_NAME_MAX,
    ) {
        Text(text = stringResource(R.string.my_identity_display_name_save))
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UserHashLabel()
            UserHashText(
                text = userHash,
                modifier = Modifier.padding(top = 8.dp),
            )
            UserHashShareRow(userHash = userHash)
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.my_identity_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
