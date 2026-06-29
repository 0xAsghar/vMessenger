package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.StyledQrCode
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole

@Composable
fun NodesRoute(
    onNavigateBack: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var shareNode by remember { mutableStateOf<NetworkNode?>(null) }

    VMessengerScaffold(
        title = stringResource(R.string.nodes_title),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = onNavigateToScan) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = stringResource(R.string.nodes_scan_action),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.nodes_add_title))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RunNodeGuideSection()
            NodeSection(
                title = stringResource(R.string.nodes_bootstrap_section),
                nodes = state.bootstrapNodes,
                onToggle = viewModel::setEnabled,
                onRemove = viewModel::remove,
                onShare = { shareNode = it },
            )
            NodeSection(
                title = stringResource(R.string.nodes_relay_section),
                nodes = state.relayNodes,
                onToggle = viewModel::setEnabled,
                onRemove = viewModel::remove,
                onShare = { shareNode = it },
            )
        }
    }

    if (showAddDialog) {
        AddNodeDialog(
            error = addError,
            onAdd = { input, role -> viewModel.addNode(input, role) },
            onScan = {
                showAddDialog = false
                viewModel.clearAddError()
                onNavigateToScan()
            },
            onDismiss = {
                showAddDialog = false
                viewModel.clearAddError()
            },
        )
    }

    shareNode?.let { node ->
        ShareNodeDialog(
            link = viewModel.exportLink(node),
            onDismiss = { shareNode = null },
        )
    }
}

@Composable
private fun RunNodeGuideSection() {
    SettingsSection(title = stringResource(R.string.nodes_run_section)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.nodes_run_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.nodes_run_build),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = stringResource(R.string.nodes_run_production),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = stringResource(R.string.nodes_run_dev),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = stringResource(R.string.nodes_run_add_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.nodes_run_link_examples),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun NodeSection(
    title: String,
    nodes: List<NetworkNode>,
    onToggle: (NetworkNode, Boolean) -> Unit,
    onRemove: (NetworkNode) -> Unit,
    onShare: (NetworkNode) -> Unit,
) {
    SettingsSection(title = title) {
        if (nodes.isEmpty()) {
            Text(
                text = stringResource(R.string.nodes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            nodes.forEachIndexed { index, node ->
                if (index > 0) SettingsDivider()
                NodeRow(node = node, onToggle = onToggle, onRemove = onRemove, onShare = onShare)
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: NetworkNode,
    onToggle: (NetworkNode, Boolean) -> Unit,
    onRemove: (NetworkNode) -> Unit,
    onShare: (NetworkNode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nodeHealthText(node),
                style = MaterialTheme.typography.bodySmall,
                color = healthColor(node),
            )
        }
        Switch(checked = node.enabled, onCheckedChange = { onToggle(node, it) })
        IconButton(onClick = { onShare(node) }) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.nodes_share))
        }
        if (!node.builtIn) {
            IconButton(onClick = { onRemove(node) }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.nodes_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun nodeHealthText(node: NetworkNode): String {
    val prefix = if (node.builtIn) stringResource(R.string.nodes_builtin) + " · " else ""
    return prefix + when {
        node.failCount > 0 ->
            stringResource(R.string.nodes_health_fail) + " (" +
                stringResource(R.string.nodes_failures, node.failCount) + ")"
        node.lastOkUnixMs != null -> stringResource(R.string.nodes_health_ok)
        else -> stringResource(R.string.nodes_health_never)
    }
}

@Composable
private fun healthColor(node: NetworkNode) = when {
    node.failCount > 0 -> MaterialTheme.colorScheme.error
    node.lastOkUnixMs != null -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AddNodeDialog(
    error: String?,
    onAdd: (String, NetworkNodeRole) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(NetworkNodeRole.RELAY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nodes_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.nodes_add_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == NetworkNodeRole.RELAY,
                        onClick = { role = NetworkNodeRole.RELAY },
                        label = { Text(stringResource(R.string.nodes_role_relay)) },
                    )
                    FilterChip(
                        selected = role == NetworkNodeRole.BOOTSTRAP,
                        onClick = { role = NetworkNodeRole.BOOTSTRAP },
                        label = { Text(stringResource(R.string.nodes_role_bootstrap)) },
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onScan) {
                    Text(stringResource(R.string.nodes_scan_action))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(input, role) },
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.nodes_add_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.nodes_cancel)) }
        },
    )
}

@Composable
private fun ShareNodeDialog(
    link: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nodes_share_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StyledQrCode(payload = link)
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                Text(stringResource(R.string.nodes_copy_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.nodes_close)) }
        },
    )
}
