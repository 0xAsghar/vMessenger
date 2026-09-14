package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
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
import ir.vmessenger.core.designsystem.component.VmFab
import ir.vmessenger.core.designsystem.component.VmInputDialog
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole

/** Enough of the guide to read without the dialog swallowing the screen. */
private val GuideMaxHeight = 420.dp

@Composable
fun NodesRoute(
    onNavigateBack: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showRunGuide by remember { mutableStateOf(false) }
    var shareNode by remember { mutableStateOf<NetworkNode?>(null) }

    VMessengerScaffold(
        title = stringResource(R.string.nodes_title),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { showRunGuide = true }) {
                Icon(
                    Icons.Outlined.PriorityHigh,
                    contentDescription = stringResource(R.string.nodes_run_guide_action),
                )
            }
            IconButton(onClick = onNavigateToScan) {
                Icon(
                    Icons.Outlined.QrCodeScanner,
                    contentDescription = stringResource(R.string.nodes_scan_action),
                )
            }
        },
        floatingActionButton = {
            VmFab(
                icon = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.nodes_add_title),
                onClick = { showAddDialog = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xl),
        ) {
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

    if (showRunGuide) {
        RunNodeGuideDialog(onDismiss = { showRunGuide = false })
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
private fun RunNodeGuideDialog(onDismiss: () -> Unit) {
    VmInputDialog(
        title = stringResource(R.string.nodes_run_section),
        confirmLabel = stringResource(R.string.nodes_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        // Nothing to accept or reject: the guide is read and closed.
        dismissLabel = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = GuideMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.nodes_run_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_install_label),
                code = stringResource(R.string.nodes_run_install_one_liner),
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_build_label),
                code = stringResource(R.string.nodes_run_build_manual),
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_production_label),
                code = stringResource(R.string.nodes_run_production_cmd),
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_dev_label),
                code = stringResource(R.string.nodes_run_dev_cmd),
            )
            Text(
                text = stringResource(R.string.nodes_run_add_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_link_bootstrap_label),
                code = stringResource(R.string.nodes_run_link_bootstrap),
            )
            CopyableCodeBlock(
                label = stringResource(R.string.nodes_run_link_relay_label),
                code = stringResource(R.string.nodes_run_link_relay),
            )
        }
    }
}

@Composable
private fun CopyableCodeBlock(
    label: String,
    code: String,
) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = VmSpacing.xs),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.nodes_copy_code),
                )
            }
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
                modifier = Modifier.padding(VmSpacing.lg),
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
            .heightIn(min = VmSizes.touchTarget)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
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

    VmInputDialog(
        title = stringResource(R.string.nodes_add_title),
        confirmLabel = stringResource(R.string.nodes_add_action),
        onConfirm = { onAdd(input, role) },
        onDismiss = onDismiss,
        confirmEnabled = input.isNotBlank(),
        dismissLabel = stringResource(R.string.nodes_cancel),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.nodes_add_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
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
}

@Composable
private fun ShareNodeDialog(
    link: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    VmInputDialog(
        title = stringResource(R.string.nodes_share_title),
        confirmLabel = stringResource(R.string.nodes_copy_link),
        onConfirm = { clipboard.setText(AnnotatedString(link)) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.nodes_close),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
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
    }
}
