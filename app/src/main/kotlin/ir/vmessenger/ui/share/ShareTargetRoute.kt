package ir.vmessenger.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.AvatarVariant
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmListRow
import ir.vmessenger.domain.model.ConversationSummary

/**
 * Where a share lands: the chat list, as a one-tap destination picker.
 *
 * Deliberately only existing conversations. Starting a brand-new chat needs a contact request and a
 * handshake, which is not something to run a shared photo through — the user can pair first and
 * share again.
 */
@Composable
fun ShareTargetRoute(
    onNavigateBack: () -> Unit,
    onShared: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareTargetViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    VMessengerScaffold(
        title = stringResource(R.string.share_target_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Chat,
                title = stringResource(R.string.share_target_empty_title),
                body = stringResource(R.string.share_target_empty_body),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(conversations, key = { it.id }) { summary ->
                    ShareTargetRow(
                        summary = summary,
                        enabled = !sending,
                        onClick = { viewModel.onPick(summary.id, onShared) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareTargetRow(summary: ConversationSummary, enabled: Boolean, onClick: () -> Unit) {
    VmListRow(
        title = summary.contactName,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        avatar = {
            Avatar(
                seed = summary.identityHash,
                name = summary.contactName,
                variant = if (summary.groupId != null) AvatarVariant.Group else AvatarVariant.Person,
            )
        },
    )
}
