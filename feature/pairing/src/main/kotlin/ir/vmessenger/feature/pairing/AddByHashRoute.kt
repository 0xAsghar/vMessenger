package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

@Composable
fun AddByHashRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AddByHashViewModel = hiltViewModel(),
) {
    var userHash by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    VMessengerScaffold(
        title = stringResource(R.string.add_by_hash_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        AddByHashForm(
            padding = padding,
            userHash = userHash,
            onUserHashChange = { userHash = it },
            uiState = uiState,
            onAdd = { viewModel.addContact(userHash) },
            onDone = onDone,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun AddByHashForm(
    padding: androidx.compose.foundation.layout.PaddingValues,
    userHash: String,
    onUserHashChange: (String) -> Unit,
    uiState: AddContactUiState,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.add_by_hash_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = userHash,
            onValueChange = onUserHashChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.add_by_hash_label)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            singleLine = false,
            minLines = 2,
        )
        Spacer(modifier = Modifier.height(16.dp))
        when (uiState) {
            AddContactUiState.Idle, AddContactUiState.Saving -> {
                Button(
                    onClick = onAdd,
                    enabled = uiState != AddContactUiState.Saving && userHash.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState == AddContactUiState.Saving) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.add_by_hash_action))
                    }
                }
            }
            AddContactUiState.Success -> {
                Text(text = stringResource(R.string.add_contact_success))
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_contact_done))
                }
            }
            is AddContactUiState.Error -> {
                Text(
                    text = uiState.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
