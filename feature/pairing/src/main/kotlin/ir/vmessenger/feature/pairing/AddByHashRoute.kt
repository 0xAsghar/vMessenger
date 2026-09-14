package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val ProgressSize = 20.dp

/** The field's own opinion of what has been typed so far. */
@Immutable
private data class HashFieldState(
    val value: String,
    val complete: Boolean,
    val malformed: Boolean,
)

@Composable
fun AddByHashRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AddByHashViewModel = hiltViewModel(),
) {
    var userHash by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val trimmed = userHash.trim()
    val field = HashFieldState(
        value = userHash,
        complete = UserHashEncoder.isValid(trimmed),
        // Only complain once there is enough typed to be wrong, not on the first character.
        malformed = trimmed.length >= MIN_HASH_HINT_LENGTH && !UserHashEncoder.isValid(trimmed),
    )

    VMessengerScaffold(
        title = stringResource(R.string.add_by_hash_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        AddByHashForm(
            padding = padding,
            field = field,
            onUserHashChange = { userHash = it },
            uiState = uiState,
            onAdd = { viewModel.addContact(trimmed) },
            onDone = onDone,
        )
    }
}

@Composable
@Suppress("LongParameterList") // a form: state in, one callback per control
private fun AddByHashForm(
    padding: PaddingValues,
    field: HashFieldState,
    onUserHashChange: (String) -> Unit,
    uiState: AddContactUiState,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = VmSpacing.xl, vertical = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.add_by_hash_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HashField(field = field, onUserHashChange = onUserHashChange)
        AddByHashStatus(
            uiState = uiState,
            canSubmit = field.complete,
            onAdd = onAdd,
            onDone = onDone,
        )
    }
}

@Composable
private fun HashField(
    field: HashFieldState,
    onUserHashChange: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = field.value,
        onValueChange = onUserHashChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = stringResource(R.string.add_by_hash_label)) },
        // Ltr: a hash is an opaque identifier and must not reorder as the user types it.
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        singleLine = false,
        minLines = 2,
        isError = field.malformed,
        supportingText = { HashFieldSupportingText(field) },
        trailingIcon = {
            IconButton(onClick = { clipboard.getText()?.text?.let(onUserHashChange) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = stringResource(R.string.add_by_hash_paste),
                )
            }
        },
    )
}

/** Live feedback: the checksum either verifies or it does not, and the user sees which. */
@Composable
private fun HashFieldSupportingText(field: HashFieldState) {
    when {
        field.complete -> Text(
            text = stringResource(R.string.add_by_hash_valid),
            color = MaterialTheme.colorScheme.primary,
        )
        field.malformed -> Text(
            text = stringResource(R.string.add_by_hash_invalid),
            color = MaterialTheme.colorScheme.error,
        )
        else -> Text(text = stringResource(R.string.add_by_hash_format))
    }
}

@Composable
private fun AddByHashStatus(
    uiState: AddContactUiState,
    canSubmit: Boolean,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    when (uiState) {
        AddContactUiState.Idle, AddContactUiState.Saving -> Button(
            onClick = onAdd,
            enabled = canSubmit && uiState != AddContactUiState.Saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState == AddContactUiState.Saving) {
                CircularProgressIndicator(modifier = Modifier.size(ProgressSize), strokeWidth = 2.dp)
            } else {
                Text(text = stringResource(R.string.add_by_hash_action))
            }
        }
        AddContactUiState.Success -> AddContactSuccessPanel(onDone = onDone)
        is AddContactUiState.Error -> Text(
            text = uiState.error.toUiText(),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AddContactSuccessPanel(onDone: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.add_contact_success),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.add_contact_pending_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.add_contact_done))
            }
        }
    }
}

private const val MIN_HASH_HINT_LENGTH = 6
