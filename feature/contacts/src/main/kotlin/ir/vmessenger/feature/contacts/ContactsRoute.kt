package ir.vmessenger.feature.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.EmptyStateAction
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * The contacts tab: pending requests, then the contacts themselves.
 *
 * Opening a contact navigates to `ContactDetail` in the outer graph rather than swapping a
 * remembered id here, so the system back button closes the detail instead of leaving the tab.
 */
@Composable
fun ContactsRoute(
    navigation: ContactsNavigation,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberVmSnackbar()
    UiMessageSnackbarEffect(messages = viewModel.messages, hostState = snackbarHost)
    BackHandler(enabled = state.searchActive) { viewModel.onSearchActiveChange(false) }

    VMessengerScaffold(
        title = stringResource(R.string.contacts_title),
        titleContent = if (state.searchActive) {
            {
                VmSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onClose = { viewModel.onSearchActiveChange(false) },
                    placeholder = stringResource(R.string.contacts_search_placeholder),
                )
            }
        } else {
            null
        },
        actions = {
            if (!state.searchActive) {
                ContactsTopBarActions(
                    onSearch = { viewModel.onSearchActiveChange(true) },
                    onMyQr = navigation.onMyQr,
                    onScanQr = navigation.onScanQr,
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = navigation.onAddByHash,
                icon = { Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text(text = stringResource(R.string.contacts_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { VmSnackbarHost(snackbarHost) },
    ) { padding ->
        ContactsBody(
            state = state,
            padding = padding,
            callbacks = ContactsListCallbacks(
                onOpenContact = navigation.onOpenContact,
                onLongPressContact = viewModel::onSheetFor,
                onApproveRequest = viewModel::onApproveRequest,
                onRejectRequest = viewModel::onRejectRequest,
            ),
            navigation = navigation,
        )
    }

    ContactsOverlays(state = state, viewModel = viewModel, onStartChat = navigation.onStartChat)
}

/** The long-press sheet and whichever confirmation it opened; both are driven by the state. */
@Composable
private fun ContactsOverlays(
    state: ContactsUiState,
    viewModel: ContactsViewModel,
    onStartChat: (String) -> Unit,
) {
    val sheetFor = state.sheetFor
    if (sheetFor != null) {
        ContactActionsSheet(
            contact = sheetFor,
            onAction = { action ->
                if (action == ContactSheetAction.CHAT) onStartChat(sheetFor.id)
                viewModel.onSheetAction(sheetFor.id, action)
            },
            onDismiss = { viewModel.onSheetFor(null) },
        )
    }
    ContactDialogHost(
        dialog = state.dialog,
        callbacks = ContactDialogCallbacks(
            onConfirm = viewModel::confirmPendingAction,
            onRename = viewModel::confirmRename,
            onDismiss = viewModel::dismissDialog,
        ),
    )
}

@Composable
private fun RowScope.ContactsTopBarActions(
    onSearch: () -> Unit,
    onMyQr: () -> Unit,
    onScanQr: () -> Unit,
) {
    IconButton(onClick = onSearch) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = stringResource(R.string.contacts_search),
        )
    }
    IconButton(onClick = onMyQr) {
        Icon(
            imageVector = Icons.Outlined.QrCode2,
            contentDescription = stringResource(R.string.contacts_my_qr),
        )
    }
    IconButton(onClick = onScanQr) {
        Icon(
            imageVector = Icons.Outlined.QrCodeScanner,
            contentDescription = stringResource(R.string.contacts_scan_qr),
        )
    }
}

@Composable
private fun ContactsBody(
    state: ContactsUiState,
    padding: PaddingValues,
    callbacks: ContactsListCallbacks,
    navigation: ContactsNavigation,
) {
    val modifier = Modifier
        .fillMaxSize()
        .padding(padding)
    when {
        state.loading -> SkeletonList(modifier = modifier)
        state.isEmpty -> ContactsEmpty(
            onScanQr = navigation.onScanQr,
            onAddByHash = navigation.onAddByHash,
            modifier = modifier,
        )
        state.noSearchResults -> EmptyState(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.contacts_search_empty_title),
            body = stringResource(R.string.contacts_search_empty_body),
            modifier = modifier,
        )
        else -> ContactsList(state = state, callbacks = callbacks, modifier = modifier)
    }
}

/** Both ways in are offered: scanning the other device's QR, or typing their user hash. */
@Composable
private fun ContactsEmpty(
    onScanQr: () -> Unit,
    onAddByHash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            icon = Icons.Outlined.Contacts,
            title = stringResource(R.string.contacts_empty_title),
            body = stringResource(R.string.contacts_empty_body),
            action = EmptyStateAction(
                label = stringResource(R.string.contacts_empty_scan),
                onClick = onScanQr,
            ),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onAddByHash,
            modifier = Modifier.padding(bottom = VmSpacing.xxl),
        ) {
            Text(text = stringResource(R.string.contacts_empty_add_by_hash))
        }
    }
}
