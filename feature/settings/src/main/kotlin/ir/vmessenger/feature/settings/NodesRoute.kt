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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.StyledQrCode
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmChip
import ir.vmessenger.core.designsystem.component.VmFab
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmInputDialog
import ir.vmessenger.core.designsystem.component.VmSwitch
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.foundation.rememberCopyToClipboard
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
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
    var showRunGuide by remember { mutableStateOf(false) }
    var shareNode by remember { mutableStateOf<NetworkNode?>(null) }
    val scroll = rememberScrollState()

    VMessengerScaffold(
        title = stringResource(R.string.nodes_title),
        onNavigateBack = onNavigateBack,
        scrolled = scroll.canScrollBackward,
        actions = {
            VmIconButton(
                icon = Icons.Outlined.PriorityHigh,
                contentDescription = stringResource(R.string.nodes_run_guide_action),
                onClick = { showRunGuide = true },
            )
            VmIconButton(
                icon = Icons.Outlined.QrCodeScanner,
                contentDescription = stringResource(R.string.nodes_scan_action),
                onClick = onNavigateToScan,
            )
        },
        floatingActionButton = {
            VmFab(
                icon = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.nodes_add_title),
                onClick = { showAddDialog = true },
            )
        },
    ) { padding ->
        // Edge to edge: the sections pad their own rows. The bottom keeps the last row clear of the FAB.
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scroll)
                .padding(bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
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
        // The dialog scrolls its own content, so the guide can run as long as it needs.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            VmText(text = stringResource(R.string.nodes_run_intro))
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
            VmText(text = stringResource(R.string.nodes_run_add_hint))
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
    val copy = rememberCopyToClipboard()
    Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.xs)) {
        VmText(
            text = label,
            style = VmTheme.typography.bodySmMedium,
            color = VmTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            VmText(
                text = code,
                style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
                color = VmTheme.colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = VmSpacing.xs),
            )
            VmIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.nodes_copy_code),
                onClick = { copy(code) },
                tint = VmTheme.colors.iconSecondary,
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
            VmText(
                text = stringResource(R.string.nodes_empty),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
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
            .padding(start = VmSpacing.lg, end = VmSpacing.xs, top = VmSpacing.sm, bottom = VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = node.address,
                style = VmTheme.typography.bodyMd.copy(fontFamily = FontFamily.Monospace),
                color = VmTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VmText(
                text = nodeHealthText(node),
                style = VmTheme.typography.bodySm,
                color = healthColor(node),
            )
        }
        VmSwitch(checked = node.enabled, onCheckedChange = { onToggle(node, it) })
        VmIconButton(
            icon = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.nodes_share),
            onClick = { onShare(node) },
            tint = VmTheme.colors.iconSecondary,
        )
        if (!node.builtIn) {
            VmIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.nodes_delete),
                onClick = { onRemove(node) },
                tint = VmTheme.colors.iconCritical,
            )
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
    node.failCount > 0 -> VmTheme.colors.textCritical
    node.lastOkUnixMs != null -> VmTheme.colors.textSuccess
    else -> VmTheme.colors.textSecondary
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
        VmTextField(
            value = input,
            onValueChange = { input = it },
            config = VmTextFieldConfig(
                label = stringResource(R.string.nodes_add_hint),
                isError = error != null,
                supportingText = error,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
            VmChip(
                selected = role == NetworkNodeRole.RELAY,
                onClick = { role = NetworkNodeRole.RELAY },
                label = stringResource(R.string.nodes_role_relay),
            )
            VmChip(
                selected = role == NetworkNodeRole.BOOTSTRAP,
                onClick = { role = NetworkNodeRole.BOOTSTRAP },
                label = stringResource(R.string.nodes_role_bootstrap),
            )
        }
        VmTextButton(
            text = stringResource(R.string.nodes_scan_action),
            onClick = onScan,
            leadingIcon = Icons.Outlined.QrCodeScanner,
        )
    }
}

@Composable
private fun ShareNodeDialog(
    link: String,
    onDismiss: () -> Unit,
) {
    val copy = rememberCopyToClipboard()
    VmInputDialog(
        title = stringResource(R.string.nodes_share_title),
        confirmLabel = stringResource(R.string.nodes_copy_link),
        onConfirm = { copy(link) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.nodes_close),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StyledQrCode(payload = link)
            VmText(
                text = link,
                style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Room under the last row for the FAB and its margin, so it never sits on a node's controls. */
private val FAB_CLEARANCE = 88.dp
