package ir.vmessenger.feature.chat.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.AvatarVariant
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.asText
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.feature.chat.R

/**
 * Creating a group: the contact picker, then the name.
 *
 * The screen owns both steps instead of being two destinations, so stepping back to the
 * member list is an undo rather than a fresh start; [onGroupCreated] is only called once the
 * repository has a conversation to open.
 */
@Composable
fun NewGroupRoute(
    onBack: () -> Unit,
    onGroupCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val created by viewModel.createdConversationId.collectAsStateWithLifecycle()
    val snackbar = rememberVmSnackbar()
    val naming = state.step == NewGroupStep.NameGroup

    LaunchedEffect(created) {
        created?.let {
            viewModel.onCreatedHandled()
            onGroupCreated(it)
        }
    }
    // asText() resolves a string resource, so the message is rendered here and only the
    // resolved sentence reaches the host.
    val message = state.message?.asText()
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    BackHandler(enabled = naming) { viewModel.onBackToMembers() }

    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_group_new_title),
        onNavigateBack = { if (naming) viewModel.onBackToMembers() else onBack() },
        modifier = modifier,
        subtitle = stringResource(
            if (naming) R.string.feature_chat_group_new_name_step else R.string.feature_chat_group_new_members_step,
        ),
        snackbarHost = { VmSnackbarHost(snackbar) },
        bottomBar = {
            NewGroupBottomBar(
                state = state,
                onContinue = viewModel::onContinue,
                onCreate = viewModel::onCreate,
            )
        },
    ) { padding ->
        NewGroupContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun NewGroupContent(
    state: NewGroupUiState,
    viewModel: NewGroupViewModel,
    modifier: Modifier = Modifier,
) {
    when (state.step) {
        NewGroupStep.PickMembers -> GroupMemberPicker(
            state = state.picker,
            onQueryChange = viewModel::onQueryChange,
            onToggle = viewModel::onToggleMember,
            modifier = modifier,
        )

        NewGroupStep.NameGroup -> NewGroupNameStep(
            state = state,
            onNameChange = viewModel::onNameChange,
            modifier = modifier,
        )
    }
}

@Composable
private fun NewGroupNameStep(
    state: NewGroupUiState,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.feature_chat_group_avatar_placeholder)
    Column(
        modifier = modifier.padding(VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The group has no id yet, so there is nothing to seed an identicon from; an empty
        // seed makes Avatar fall back to the first letter of the name being typed.
        Avatar(
            seed = ByteArray(0),
            name = state.name.ifBlank { placeholder },
            size = VmSizes.avatarLg,
            variant = AvatarVariant.Group,
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(text = stringResource(R.string.feature_chat_group_name_label)) },
            singleLine = true,
            supportingText = {
                Text(
                    text = stringResource(
                        R.string.feature_chat_group_name_remaining,
                        VmTextFormat.digits(state.nameRemaining.toString()),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                R.string.feature_chat_group_name_members,
                VmTextFormat.digits(state.picker.selectedCount.toString()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One button whose meaning follows the step, so the bar never moves between the two. */
@Composable
private fun NewGroupBottomBar(
    state: NewGroupUiState,
    onContinue: () -> Unit,
    onCreate: () -> Unit,
) {
    val naming = state.step == NewGroupStep.NameGroup
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = if (naming) onCreate else onContinue,
            enabled = if (naming) state.canCreate else state.canContinue,
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(VmSpacing.lg),
        ) {
            Text(
                text = stringResource(
                    if (naming) R.string.feature_chat_group_create else R.string.feature_chat_group_continue,
                ),
            )
        }
    }
}
