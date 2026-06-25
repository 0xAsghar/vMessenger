package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.R as DesignR
import ir.vmessenger.core.designsystem.component.UserHashLabel
import ir.vmessenger.core.designsystem.component.UserHashShareRow
import ir.vmessenger.core.designsystem.component.UserHashText

@Composable
fun CreateIdentityRoute(
    onIdentityCreated: () -> Unit,
    viewModel: CreateIdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
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
    Icon(
        painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
        contentDescription = stringResource(DesignR.string.vmessenger_logo),
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.create_identity_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
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
