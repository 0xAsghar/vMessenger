package ir.vmessenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.ContactRelationshipStatus

private const val DISABLED_ALPHA = 0.5f
private const val CONTACT_CONTENT_TYPE = "new-chat-contact"

/**
 * Picks who to talk to. Approved contacts open (or create) their conversation; the rest
 * stay listed but greyed with a status chip, so "why can't I message them" is answered
 * on the spot rather than by their absence.
 */
@Composable
fun NewChatRoute(
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val opened by viewModel.openedConversationId.collectAsStateWithLifecycle()

    LaunchedEffect(opened) {
        opened?.let {
            viewModel.onOpenHandled()
            onOpenConversation(it)
        }
    }

    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_new_title),
        onNavigateBack = onBack,
        modifier = modifier,
        titleContent = {
            VmSearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onClose = onBack,
                placeholder = stringResource(R.string.feature_chat_new_search),
            )
        },
    ) { padding ->
        NewChatContent(
            state = state,
            onNewGroup = onNewGroup,
            onContactClick = viewModel::onContactClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun NewChatContent(
    state: NewChatUiState,
    onNewGroup: () -> Unit,
    onContactClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> SkeletonList(modifier = modifier)

        state.isEmpty -> EmptyState(
            icon = Icons.Outlined.PersonOff,
            title = stringResource(R.string.feature_chat_new_empty_title),
            body = stringResource(R.string.feature_chat_new_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier) {
            item(key = "new-group", contentType = "new-group") {
                NewGroupRow(onClick = onNewGroup)
            }
            if (state.isNoResults) {
                item(key = "no-results", contentType = "no-results") {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.feature_chat_no_results_title),
                        body = stringResource(R.string.feature_chat_no_results_body),
                    )
                }
            }
            items(items = state.rows, key = { it.contactId }, contentType = { CONTACT_CONTENT_TYPE }) { row ->
                ContactPickerRow(row = row, onClick = onContactClick)
            }
        }
    }
}

@Composable
private fun NewGroupRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(VmSizes.avatarMd),
        ) {
            Icon(
                imageVector = Icons.Outlined.Group,
                contentDescription = null,
                modifier = Modifier.padding(VmSpacing.md),
            )
        }
        Text(
            text = stringResource(R.string.feature_chat_new_group),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ContactPickerRow(row: NewChatRow, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.canChat) { onClick(row.contactId) }
            .heightIn(min = VmSizes.listItemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)
            .alpha(if (row.canChat) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Avatar(seed = row.seed.bytes, name = row.name, size = VmSizes.avatarMd)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UserHashText(
                text = row.userHash,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
            )
        }
        StatusChip(row)
    }
}

@Composable
private fun StatusChip(row: NewChatRow) {
    val label = statusLabel(row) ?: return
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}

@Composable
private fun statusLabel(row: NewChatRow): String? = when {
    row.blocked -> stringResource(R.string.feature_chat_status_blocked)
    row.status == ContactRelationshipStatus.PENDING_OUT ->
        stringResource(R.string.feature_chat_status_pending_out)

    row.status == ContactRelationshipStatus.PENDING_IN ->
        stringResource(R.string.feature_chat_status_pending_in)

    row.status == ContactRelationshipStatus.REJECTED -> stringResource(R.string.feature_chat_status_rejected)
    else -> null
}
