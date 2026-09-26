package ir.vmessenger.feature.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.EmptyState
import ir.vmessenger.core.designsystem.component.EmptyStateAction
import ir.vmessenger.core.designsystem.component.SkeletonList
import ir.vmessenger.core.designsystem.component.UiMessageSnackbarEffect
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmExtendedFab
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSearchBar
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar

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
    val listState = rememberLazyListState()

    VMessengerScaffold(
        title = stringResource(R.string.contacts_title),
        scrolled = listState.canScrollBackward,
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
            VmExtendedFab(
                icon = Icons.Default.PersonAdd,
                text = stringResource(R.string.contacts_add),
                onClick = navigation.onAddByHash,
            )
        },
        snackbarHost = { VmSnackbarHost(snackbarHost) },
    ) { padding ->
        ContactsBody(
            state = state,
            listState = listState,
            padding = padding,
            callbacks = ContactsListCallbacks(
                onOpenContact = navigation.onOpenContact,
                onLongPressContact = viewModel::onSheetFor,
                onChat = navigation.onStartChat,
                onCall = navigation.onStartCall,
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
    VmIconButton(
        icon = Icons.Outlined.Search,
        contentDescription = stringResource(R.string.contacts_search),
        onClick = onSearch,
    )
    VmIconButton(
        icon = Icons.Outlined.QrCode2,
        contentDescription = stringResource(R.string.contacts_my_qr),
        onClick = onMyQr,
    )
    VmIconButton(
        icon = Icons.Outlined.QrCodeScanner,
        contentDescription = stringResource(R.string.contacts_scan_qr),
        onClick = onScanQr,
    )
}

@Composable
private fun ContactsBody(
    state: ContactsUiState,
    listState: LazyListState,
    padding: PaddingValues,
    callbacks: ContactsListCallbacks,
    navigation: ContactsNavigation,
) {
    val modifier = Modifier
        .fillMaxSize()
        .padding(padding)
    when {
        state.loading -> SkeletonList(modifier = modifier)
        state.isEmpty -> ContactsEmpty(onScanQr = navigation.onScanQr, modifier = modifier)
        state.noSearchResults -> EmptyState(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.contacts_search_empty_title),
            body = stringResource(R.string.contacts_search_empty_body),
            modifier = modifier,
        )
        else -> ContactsList(state = state, listState = listState, callbacks = callbacks, modifier = modifier)
    }
}

/**
 * Scanning a QR is the offer here; typing a hash lives behind the «افزودن مخاطب» button, which the
 * scaffold draws over this content. This used to end with a second, redundant text button for the
 * same destination, and in RTL the extended FAB landed squarely on top of it — leaving a fragment
 * of its label peeking out from under, which read as leftover text from an older build.
 */
@Composable
private fun ContactsEmpty(
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Contacts,
        title = stringResource(R.string.contacts_empty_title),
        body = stringResource(R.string.contacts_empty_body),
        action = EmptyStateAction(
            label = stringResource(R.string.contacts_empty_scan),
            onClick = onScanQr,
        ),
        modifier = modifier,
    )
}
