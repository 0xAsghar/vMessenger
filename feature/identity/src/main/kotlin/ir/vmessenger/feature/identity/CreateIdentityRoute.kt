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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CreateIdentityRoute(
    onIdentityCreated: () -> Unit,
    viewModel: CreateIdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val state = uiState) {
            CreateIdentityUiState.Intro -> CreateIdentityIntro(onCreate = viewModel::createIdentity)
            CreateIdentityUiState.Creating -> CreateIdentityLoading()
            is CreateIdentityUiState.Success -> CreateIdentitySuccess(
                userHash = state.identity.userHash,
                onContinue = onIdentityCreated,
            )
            is CreateIdentityUiState.Error -> CreateIdentityError(
                message = state.message,
                onRetry = viewModel::createIdentity,
            )
        }
    }
}

@Composable
private fun CreateIdentityIntro(onCreate: () -> Unit) {
    Text(
        text = stringResource(R.string.create_identity_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.create_identity_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.create_identity_action))
    }
}

@Composable
private fun CreateIdentityLoading() {
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.create_identity_creating))
}

@Composable
private fun CreateIdentitySuccess(userHash: String, onContinue: () -> Unit) {
    Text(
        text = stringResource(R.string.create_identity_success_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.create_identity_user_hash_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = userHash, style = MaterialTheme.typography.titleMedium)
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.create_identity_continue))
    }
}

@Composable
private fun CreateIdentityError(message: String, onRetry: () -> Unit) {
    Text(text = message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onRetry) {
        Text(text = stringResource(R.string.create_identity_retry))
    }
}
