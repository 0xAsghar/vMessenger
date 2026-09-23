package ir.vmessenger.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.designsystem.component.UserHashLineBreaks
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/** Ltr: a hash is an opaque identifier and must not reorder as the user types it. */
private val HashTextStyle = TextStyle(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)

/** The field's own opinion of what has been typed so far. */
@Immutable
private data class HashFieldState(
    val value: String,
    val complete: Boolean,
    val malformed: Boolean,
    /** A valid ID that is the user's own: nobody to add, so it is neither complete nor malformed. */
    val own: Boolean,
)

private fun hashFieldState(value: String, ownUserHash: String?): HashFieldState {
    val trimmed = value.trim()
    val entered = UserHashEncoder.decode(trimmed)
    val own = entered != null && ownUserHash != null && entered.contentEquals(UserHashEncoder.decode(ownUserHash))
    return HashFieldState(
        value = value,
        complete = entered != null && !own,
        // Only complain once there is enough typed to be wrong, not on the first character.
        malformed = trimmed.length >= MIN_HASH_HINT_LENGTH && entered == null,
        own = own,
    )
}

@Composable
fun AddByHashRoute(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AddByHashViewModel = hiltViewModel(),
) {
    var userHash by rememberSaveable { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ownUserHash by viewModel.ownUserHash.collectAsStateWithLifecycle()
    val field = remember(userHash, ownUserHash) { hashFieldState(userHash, ownUserHash) }

    VMessengerScaffold(
        title = stringResource(R.string.add_by_hash_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        AddByHashForm(
            padding = padding,
            field = field,
            onUserHashChange = {
                userHash = it
                viewModel.onInputChanged()
            },
            uiState = uiState,
            onAdd = { viewModel.addContact(userHash.trim()) },
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
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.lg),
    ) {
        VmText(
            text = stringResource(R.string.add_by_hash_hint),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
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
    VmTextField(
        value = field.value,
        onValueChange = onUserHashChange,
        modifier = Modifier.fillMaxWidth(),
        config = VmTextFieldConfig(
            label = stringResource(R.string.add_by_hash_label),
            singleLine = false,
            minLines = 2,
            visualTransformation = UserHashLineBreaks,
            isError = field.malformed || field.own,
            // Live feedback: the checksum either verifies or it does not, and the user sees which.
            supportingText = stringResource(
                when {
                    field.own -> R.string.add_by_hash_own
                    field.complete -> R.string.add_by_hash_valid
                    field.malformed -> R.string.add_by_hash_invalid
                    else -> R.string.add_by_hash_format
                },
            ),
            supportingIsSuccess = field.complete,
            capitalization = KeyboardCapitalization.Characters,
        ),
        textStyle = HashTextStyle,
        trailingIcon = {
            VmIconButton(
                icon = Icons.Outlined.ContentPaste,
                contentDescription = stringResource(R.string.add_by_hash_paste),
                onClick = { clipboard.getText()?.text?.let(onUserHashChange) },
            )
        },
    )
}

@Composable
private fun AddByHashStatus(
    uiState: AddContactUiState,
    canSubmit: Boolean,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    if (uiState == AddContactUiState.Success) {
        AddContactSuccessPanel(onDone = onDone)
        return
    }
    // A failure is said above the button, not in its place: the button used to vanish with the
    // first error, and nothing brought it back short of leaving the screen.
    Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.md)) {
        if (uiState is AddContactUiState.Error) {
            VmText(text = uiState.error.toUiText(), color = VmTheme.colors.textCritical)
        }
        VmButton(
            text = stringResource(R.string.add_by_hash_action),
            onClick = onAdd,
            enabled = canSubmit,
            loading = uiState == AddContactUiState.Saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddContactSuccessPanel(onDone: () -> Unit) {
    VmSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = VmShapes.card,
        color = VmTheme.colors.bgSubtle,
    ) {
        Column(
            modifier = Modifier.padding(VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VmText(
                text = stringResource(R.string.add_contact_success),
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
            )
            VmText(
                text = stringResource(R.string.add_contact_pending_hint),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
            )
            VmButton(
                text = stringResource(R.string.add_contact_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val MIN_HASH_HINT_LENGTH = 6
