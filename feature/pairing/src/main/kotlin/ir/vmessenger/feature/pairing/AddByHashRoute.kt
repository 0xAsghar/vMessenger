package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

@Composable
fun AddByHashRoute(
    onDone: () -> Unit,
    viewModel: AddByHashViewModel = hiltViewModel(),
) {
    var userHash by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.add_by_hash_title))
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = userHash,
            onValueChange = { userHash = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.add_by_hash_label)) },
            singleLine = false,
        )
        Spacer(modifier = Modifier.height(16.dp))
        when (uiState) {
            AddContactUiState.Idle, AddContactUiState.Saving -> {
                Button(
                    onClick = { viewModel.addContact(userHash) },
                    enabled = uiState != AddContactUiState.Saving,
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
                Button(onClick = onDone) { Text(stringResource(R.string.add_contact_done)) }
            }
            is AddContactUiState.Error -> {
                val error = uiState as AddContactUiState.Error
                Text(text = error.message)
            }
        }
    }
}
