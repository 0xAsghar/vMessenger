package ir.vmessenger.feature.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Identicon
import ir.vmessenger.core.designsystem.component.SafetyNumberDisplay
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.feature.location.LocationMapView

@Composable
fun ContactsRoute(
    onMyQr: () -> Unit,
    onScanQr: () -> Unit,
    onAddByHash: () -> Unit,
    onStartChat: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val requests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val localPublicKey by viewModel.localPublicKey.collectAsStateWithLifecycle()
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    val selected = selectedContactId?.let { id -> items.find { it.contact.id == id } }

    if (selected != null) {
        ContactDetailRoute(
            item = selected,
            localPublicKey = localPublicKey,
            onBack = { selectedContactId = null },
            onResend = { viewModel.resendRequest(selected.contact.id) },
            onStartChat = {
                onStartChat(selected.contact.id)
                selectedContactId = null
            },
        )
        return
    }

    VMessengerScaffold(
        title = stringResource(R.string.contacts_title),
        actions = {
            IconButton(onClick = onMyQr) {
                Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.contacts_my_qr))
            }
            IconButton(onClick = onScanQr) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.contacts_scan_qr))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddByHash,
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.contacts_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        ContactsListContent(
            items = items,
            requests = requests,
            padding = padding,
            onContactClick = { selectedContactId = it },
            requestActions = RequestActions(
                onApprove = viewModel::approveRequest,
                onReject = viewModel::rejectRequest,
            ),
        )
    }
}

private data class RequestActions(
    val onApprove: (ContactRequest) -> Unit,
    val onReject: (ContactRequest) -> Unit,
)

@Composable
private fun ContactsListContent(
    items: List<ContactListItem>,
    requests: List<ContactRequest>,
    padding: PaddingValues,
    onContactClick: (String) -> Unit,
    requestActions: RequestActions,
) {
    if (items.isEmpty() && requests.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.contacts_empty))
            Text(
                text = stringResource(R.string.contacts_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (requests.isNotEmpty()) {
            item(key = "requests_header") {
                Text(
                    text = stringResource(R.string.contacts_requests_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(requests, key = { "req_${it.requestId}" }) { request ->
                RequestRow(
                    request = request,
                    onApprove = { requestActions.onApprove(request) },
                    onReject = { requestActions.onReject(request) },
                )
            }
        }
        items(items, key = { it.contact.id }) { item ->
            ContactRow(item = item, onClick = { onContactClick(item.contact.id) })
        }
    }
}

@Composable
private fun RequestRow(request: ContactRequest, onApprove: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Identicon(seed = request.requesterIdentityHash)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(text = request.requesterDisplayName, style = MaterialTheme.typography.titleMedium)
            UserHashText(
                text = request.requesterUserHash,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
        }
        TextButton(onClick = onApprove) { Text(stringResource(R.string.contacts_request_approve)) }
        TextButton(onClick = onReject) {
            Text(stringResource(R.string.contacts_request_reject), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ContactRow(item: ContactListItem, onClick: () -> Unit) {
    val contact = item.contact
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Identicon(seed = contact.identityHash)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(text = contact.displayName, style = MaterialTheme.typography.titleMedium)
            UserHashText(
                text = contact.userHash,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
        }
        if (item.sharedLocation != null) {
            LocationBadge(distanceMeters = item.distanceMeters)
        }
        if (contact.relationshipStatus != ContactRelationshipStatus.APPROVED) {
            AssistChip(
                onClick = onClick,
                label = { Text(text = relationshipStatusLabel(contact.relationshipStatus)) },
            )
        }
    }
}

@Composable
private fun LocationBadge(distanceMeters: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Icon(
            imageVector = Icons.Outlined.LocationOn,
            contentDescription = stringResource(R.string.contacts_location_shared),
            tint = MaterialTheme.colorScheme.primary,
        )
        distanceMeters?.let {
            Text(
                text = formatDistance(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun ContactDetailRoute(
    item: ContactListItem,
    localPublicKey: ByteArray?,
    onBack: () -> Unit,
    onResend: () -> Unit,
    onStartChat: () -> Unit,
) {
    val contact = item.contact
    VMessengerScaffold(
        title = contact.displayName,
        onNavigateBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Identicon(seed = contact.identityHash, size = 56.dp)
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(text = contact.displayName, style = MaterialTheme.typography.titleLarge)
                    UserHashText(
                        text = contact.userHash,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
            }
            item.sharedLocation?.let { sample ->
                ContactLocationSection(sample = sample, distanceMeters = item.distanceMeters)
            }
            if (localPublicKey != null && contact.ed25519PublicKey.any { it != 0.toByte() }) {
                SafetyNumberDisplay(
                    localPublicKey = localPublicKey,
                    remotePublicKey = contact.ed25519PublicKey,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            Button(
                onClick = onStartChat,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                enabled = !contact.blocked && contact.isApproved,
            ) {
                Text(text = stringResource(R.string.contacts_start_chat))
            }
            if (contact.relationshipStatus == ContactRelationshipStatus.PENDING_OUT) {
                OutlinedButton(
                    onClick = onResend,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(text = stringResource(R.string.contacts_resend_request))
                }
            }
            if (!contact.isApproved) {
                Text(
                    text = relationshipStatusLabel(contact.relationshipStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ContactLocationSection(sample: LocationSample, distanceMeters: Double?) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.contacts_location_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            distanceMeters?.let {
                Text(
                    text = formatDistance(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        LocationMapView(
            samples = mapOf(sample.shareId to sample),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
    }
}

@Composable
private fun formatDistance(meters: Double): String =
    if (meters >= METERS_PER_KM) {
        stringResource(R.string.contacts_distance_km, meters / METERS_PER_KM)
    } else {
        stringResource(R.string.contacts_distance_meters, meters.toInt())
    }

@Composable
private fun relationshipStatusLabel(status: ContactRelationshipStatus): String = when (status) {
    ContactRelationshipStatus.PENDING_OUT -> stringResource(R.string.contacts_status_pending_out)
    ContactRelationshipStatus.PENDING_IN -> stringResource(R.string.contacts_status_pending_in)
    ContactRelationshipStatus.REJECTED -> stringResource(R.string.contacts_status_rejected)
    ContactRelationshipStatus.APPROVED -> ""
}

private const val METERS_PER_KM = 1000.0
