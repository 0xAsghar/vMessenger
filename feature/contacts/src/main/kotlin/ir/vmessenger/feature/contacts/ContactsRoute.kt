package ir.vmessenger.feature.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.Identicon
import ir.vmessenger.core.designsystem.component.SafetyNumberDisplay
import ir.vmessenger.domain.model.Contact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsRoute(
    onMyQr: () -> Unit,
    onScanQr: () -> Unit,
    onAddByHash: () -> Unit,
    onContactSelected: (String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                actions = {
                    IconButton(onClick = onMyQr) {
                        Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.contacts_my_qr))
                    }
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.contacts_scan_qr))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddByHash,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.contacts_add)) },
            )
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.contacts_empty))
                Text(
                    text = stringResource(R.string.contacts_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(contact = contact, onClick = { onContactSelected(contact.id) })
                }
            }
        }
    }
}

@Composable
fun ContactDetailRoute(
    contact: Contact,
    localPublicKey: ByteArray?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Identicon(seed = contact.identityHash, size = 56.dp)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = contact.displayName, style = MaterialTheme.typography.titleLarge)
                Text(text = contact.userHash, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (localPublicKey != null && contact.ed25519PublicKey.any { it != 0.toByte() }) {
            SafetyNumberDisplay(
                localPublicKey = localPublicKey,
                remotePublicKey = contact.ed25519PublicKey,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Identicon(seed = contact.identityHash)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = contact.displayName, style = MaterialTheme.typography.titleMedium)
            Text(text = contact.userHash, style = MaterialTheme.typography.bodySmall)
        }
    }
}
