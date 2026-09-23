package ir.vmessenger.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.EmptyStateAction
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmFab
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmSnackbarHostState
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.format.VmTextFormat
import kotlinx.coroutines.launch

/**
 * The chats tab: search, selection mode and the three list states (skeleton while the
 * database is still answering, empty, content).
 *
 * [onNewChat] is the FAB's destination. It defaults to a no-op so the tab shell can
 * adopt it without changing at the same time as this screen.
 */
@Composable
fun ChatRoute(
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberVmSnackbar()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val deletedMessage = stringResource(R.string.feature_chat_deleted)

    BackHandler(enabled = state.selectionMode) { viewModel.onClearSelection() }
    BackHandler(enabled = state.searching && !state.selectionMode) { viewModel.onSearchClose() }
    val listState = rememberLazyListState()

    VMessengerScaffold(
        title = stringResource(R.string.feature_chat_title),
        scrolled = listState.canScrollBackward,
        onNavigateBack = if (state.selectionMode) viewModel::onClearSelection else null,
        actions = { ChatListActions(state = state, viewModel = viewModel, onDelete = { confirmDelete = true }) },
        floatingActionButton = { NewChatFab(visible = !state.selectionMode, onClick = onNewChat) },
        modifier = modifier,
        titleContent = chatListTitle(state, viewModel),
        snackbarHost = { VmSnackbarHost(snackbar) },
    ) { padding ->
        ChatListContent(
            state = state,
            listState = listState,
            onOpen = onOpenConversation,
            onLongPress = viewModel::onToggleSelection,
            onNewChat = onNewChat,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (confirmDelete) {
        DeleteConversationsDialog(
            onConfirm = {
                confirmDelete = false
                viewModel.onDeleteSelected()
            },
            onDismiss = { confirmDelete = false },
            snackbar = snackbar,
            message = deletedMessage,
        )
    }
}

/** Search field, selection counter or nothing at all — the plain title is the scaffold's default. */
@Composable
private fun chatListTitle(state: ChatListUiState, viewModel: ChatListViewModel): (@Composable () -> Unit)? = when {
    state.selectionMode -> {
        { VmText(text = stringResource(R.string.feature_chat_selection_count, persian(state.selection.size))) }
    }

    state.searching -> {
        {
            VmSearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onClose = viewModel::onSearchClose,
                placeholder = stringResource(R.string.feature_chat_search),
            )
        }
    }

    else -> null
}

@Composable
private fun RowScope.ChatListActions(
    state: ChatListUiState,
    viewModel: ChatListViewModel,
    onDelete: () -> Unit,
) {
    if (state.selectionMode) {
        VmIconButton(
            icon = if (state.selectionMuted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
            contentDescription = stringResource(
                if (state.selectionMuted) R.string.feature_chat_unmute else R.string.feature_chat_mute,
            ),
            onClick = viewModel::onToggleMuteSelected,
        )
        VmIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.feature_chat_delete),
            onClick = onDelete,
        )
    } else if (!state.searching) {
        VmIconButton(
            icon = Icons.Outlined.Search,
            contentDescription = stringResource(R.string.feature_chat_search_open),
            onClick = viewModel::onSearchOpen,
        )
    }
}

@Composable
private fun NewChatFab(visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    VmFab(
        icon = Icons.Outlined.Edit,
        contentDescription = stringResource(R.string.feature_chat_new),
        onClick = onClick,
    )
}

@Composable
@Suppress("LongParameterList") // The list's state and one callback per thing a row can do.
private fun ChatListContent(
    state: ChatListUiState,
    listState: LazyListState,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> SkeletonList(modifier = modifier)

        state.isEmpty -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.Chat,
            title = stringResource(R.string.feature_chat_empty_title),
            body = stringResource(R.string.feature_chat_empty_body),
            modifier = modifier,
            action = EmptyStateAction(label = stringResource(R.string.feature_chat_new), onClick = onNewChat),
        )

        state.isNoResults -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.feature_chat_no_results_title),
            body = stringResource(R.string.feature_chat_no_results_body),
            modifier = modifier,
        )

        else -> LazyColumn(state = listState, modifier = modifier) {
            items(
                items = state.rows,
                key = { it.id },
                contentType = { CHAT_ROW_CONTENT_TYPE },
            ) { row ->
                ChatListRowItem(
                    row = row,
                    selected = row.id in state.selection,
                    selectionMode = state.selectionMode,
                    onOpen = onOpen,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@Composable
private fun DeleteConversationsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    snackbar: VmSnackbarHostState,
    message: String,
) {
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = stringResource(R.string.feature_chat_delete_title),
        body = stringResource(R.string.feature_chat_delete_body),
        confirmLabel = stringResource(R.string.feature_chat_delete_confirm),
        onConfirm = {
            onConfirm()
            scope.launch { snackbar.showSnackbar(message) }
        },
        onDismiss = onDismiss,
        destructive = true,
    )
}

private const val CHAT_ROW_CONTENT_TYPE = "chat-row"

@Composable
private fun persian(value: Int): String = remember(value) { VmTextFormat.digits(value.toString()) }
