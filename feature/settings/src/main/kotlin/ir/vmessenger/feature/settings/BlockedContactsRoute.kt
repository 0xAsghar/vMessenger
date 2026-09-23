package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmSnackbarHostState
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.coroutines.flow.Flow

/**
 * Settings → حریم خصوصی → مخاطبین مسدود.
 *
 * Each row undoes one block, behind a confirmation that says what starts arriving again.
 */
@Composable
fun BlockedContactsRoute(
    onNavigateBack: () -> Unit,
    viewModel: BlockedContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberVmSnackbar()
    UnblockedSnackbarEffect(events = viewModel.events, hostState = snackbarHost)

    val listState = rememberLazyListState()
    VMessengerScaffold(
        title = stringResource(R.string.blocked_contacts_title),
        onNavigateBack = onNavigateBack,
        scrolled = listState.canScrollBackward,
        snackbarHost = { VmSnackbarHost(snackbarHost) },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            state.loading -> SkeletonList(modifier = modifier)
            state.contacts.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.blocked_contacts_empty_title),
                body = stringResource(R.string.blocked_contacts_empty_body),
                modifier = modifier,
            )
            else -> LazyColumn(state = listState, modifier = modifier) {
                items(state.contacts, key = { it.id }) { contact ->
                    BlockedContactRowItem(
                        contact = contact,
                        onUnblock = { viewModel.onUnblockRequest(contact.id) },
                    )
                }
            }
        }
    }

    val pending = state.pendingUnblock
    if (pending != null) {
        ConfirmDialog(
            title = stringResource(R.string.blocked_contacts_unblock_title, pending.name),
            body = stringResource(R.string.blocked_contacts_unblock_body),
            confirmLabel = stringResource(R.string.blocked_contacts_unblock),
            onConfirm = viewModel::confirmUnblock,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun BlockedContactRowItem(
    contact: BlockedContactRow,
    onUnblock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.listItemHeight)
            .padding(start = VmSpacing.lg, end = VmSpacing.sm, top = VmSpacing.sm, bottom = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Avatar(seed = contact.identityHash, name = contact.name)
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = contact.name,
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UserHashText(
                text = contact.userHash,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VmTextButton(text = stringResource(R.string.blocked_contacts_unblock), onClick = onUnblock)
    }
}

/** The confirmation is rendered in composition because it names the contact. */
@Composable
private fun UnblockedSnackbarEffect(
    events: Flow<String>,
    hostState: VmSnackbarHostState,
) {
    var unblockedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(events) {
        events.collect { unblockedName = it }
    }
    val text = unblockedName?.let { stringResource(R.string.blocked_contacts_unblocked, it) }
    LaunchedEffect(text) {
        if (text != null) {
            hostState.showSnackbar(text)
            unblockedName = null
        }
    }
}
