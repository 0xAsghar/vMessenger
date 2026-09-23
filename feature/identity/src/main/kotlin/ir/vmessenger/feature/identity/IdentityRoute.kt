package ir.vmessenger.feature.identity

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.EmptyStateAction
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UiMessageSnackbarEffect
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.Identity

/** Enough rows to fill the header block without pretending a long list is coming. */
private const val SKELETON_ROWS = 3

@Composable
fun IdentityRoute(
    onNavigateBack: () -> Unit = {},
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberVmSnackbar()
    UiMessageSnackbarEffect(messages = viewModel.messages, hostState = snackbarHost)

    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = stringResource(R.string.my_identity_title),
        onNavigateBack = onNavigateBack,
        scrolled = scroll.canScrollBackward,
        snackbarHost = { VmSnackbarHost(snackbarHost) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when (val state = uiState) {
                IdentityUiState.Loading -> SkeletonList(rows = SKELETON_ROWS)
                IdentityUiState.None -> EmptyState(
                    icon = Icons.Outlined.Fingerprint,
                    title = stringResource(R.string.my_identity_none),
                    body = stringResource(R.string.my_identity_none_body),
                    action = EmptyStateAction(
                        label = stringResource(R.string.my_identity_none_action),
                        onClick = onNavigateBack,
                    ),
                )
                is IdentityUiState.Loaded -> IdentityLoadedContent(
                    state = state,
                    scroll = scroll,
                    onSaveDisplayName = viewModel::updateDisplayName,
                )
            }
        }
    }
}

@Composable
private fun IdentityLoadedContent(
    state: IdentityUiState.Loaded,
    scroll: ScrollState,
    onSaveDisplayName: (String) -> Unit,
) {
    // Scrolls because the hash card and the hint used to clip behind the keyboard.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.xl),
    ) {
        IdentityHeader(identity = state.identity)
        DisplayNameSection(
            displayName = state.identity.displayName,
            saving = state.saving,
            onSave = onSaveDisplayName,
        )
        IdentityHashCard(userHash = state.identity.userHash)
        VmText(
            text = stringResource(R.string.my_identity_hint),
            style = VmTheme.typography.bodySm,
            color = VmTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** The same identicon the user's contacts see, over the same hash they were given. */
@Composable
private fun IdentityHeader(identity: Identity) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Avatar(
            seed = identity.identityHash,
            name = identity.displayName,
            size = VmSizes.avatarLg,
        )
        VmText(
            text = identity.displayName,
            style = VmTheme.typography.headingMd,
            color = VmTheme.colors.textPrimary,
        )
        UserHashText(text = identity.userHash, style = VmTheme.typography.bodySm)
    }
}

@Composable
private fun DisplayNameSection(
    displayName: String,
    saving: Boolean,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(displayName) { mutableStateOf(displayName) }
    val trimmed = name.trim()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            config = VmTextFieldConfig(
                label = stringResource(R.string.my_identity_display_name_label),
                placeholder = stringResource(R.string.my_identity_display_name_placeholder),
                enabled = !saving,
            ),
        )
        // Nothing to save while the write is in flight, or while the name is unchanged —
        // a button that stays enabled through both is how the silent failure went unnoticed.
        VmButton(
            text = stringResource(R.string.my_identity_display_name_save),
            onClick = { onSave(trimmed) },
            modifier = Modifier.fillMaxWidth(),
            enabled = trimmed != displayName && isDisplayNameValid(name),
            loading = saving,
        )
    }
}
