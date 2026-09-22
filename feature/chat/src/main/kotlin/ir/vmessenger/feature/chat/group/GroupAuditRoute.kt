package ir.vmessenger.feature.chat.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.feature.chat.R

/**
 * What members of this group edited or withdrew, as far as this device saw it.
 *
 * Reachable only while the group's creator has retention on and this device's own member row is a
 * creator or an admin — the same condition the banner every member sees describes. The screen keeps
 * saying so at the top, because someone reading other people's withdrawn words should be looking at
 * the reason they are allowed to.
 */
@Composable
fun GroupAuditRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupAuditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_group_audit_title),
        onNavigateBack = onBack,
        modifier = modifier,
    ) { padding ->
        when {
            state.loading -> SkeletonList(rows = SKELETON_ROWS, modifier = Modifier.padding(padding))
            // Not an empty list: "retention is off" and "nobody has edited anything" are different
            // facts, and showing the second in place of the first would be a lie by layout.
            !state.retentionOn -> EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.feature_chat_group_audit_off_state_title),
                body = stringResource(R.string.feature_chat_group_audit_off_state_body),
                modifier = Modifier.padding(padding),
            )
            state.entries.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.feature_chat_group_audit_empty_title),
                body = stringResource(R.string.feature_chat_group_audit_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                AuditDisclosure()
                LazyColumn {
                    items(state.entries, key = { "${it.messageId}-${it.capturedAtUnixMs}" }) { row ->
                        AuditRow(row)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Kept on the screen rather than shown once: it is the justification, not a notice to dismiss. */
@Composable
private fun AuditDisclosure() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.feature_chat_group_audit_screen_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        )
    }
}

@Composable
private fun AuditRow(row: GroupAuditRow) {
    val unknown = stringResource(R.string.feature_chat_group_member_unknown)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
    ) {
        Text(
            text = stringResource(
                if (row.deleted) {
                    R.string.feature_chat_group_audit_entry_deleted
                } else {
                    R.string.feature_chat_group_audit_entry_edited
                },
                row.authorName ?: unknown,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.text?.let { text ->
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
        row.attachmentName?.let { name ->
            Text(
                text = stringResource(R.string.feature_chat_preview_file, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = VmDateFormat.dayAndTime(row.capturedAtUnixMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SKELETON_ROWS = 6
