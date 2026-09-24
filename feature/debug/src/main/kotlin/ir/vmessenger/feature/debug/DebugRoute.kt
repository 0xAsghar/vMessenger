package ir.vmessenger.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.foundation.rememberCopyToClipboard
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

@Composable
fun DebugRoute(
    onNavigateBack: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val adbCommands = remember(state.forwardPort, state.listenPort) {
        "adb forward tcp:46555 tcp:46555\nadb forward tcp:${state.forwardPort} tcp:${state.listenPort}"
    }

    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = stringResource(R.string.feature_debug_title),
        onNavigateBack = onNavigateBack,
        scrolled = scroll.canScrollBackward,
    ) { padding ->
        // No side padding: the sections run edge to edge and pad their own rows.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(bottom = VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            DebugNetworkStatusSection(state = state)
            DebugPathSection(state = state)
            DebugP2PFlagsSection(
                flags = state.flags,
                onFlagChange = viewModel::setFlag,
                onResetFlags = viewModel::resetFlagsToDefaults,
            )
            DebugActionsSection(
                state = state,
                onDevModeChange = viewModel::setDevMode,
                onJoinAndPublish = viewModel::joinAndPublish,
                onNavigateToLogs = onNavigateToLogs,
            )
            DebugAdbSection(adbCommands = adbCommands)
        }
    }
}

@Composable
private fun DebugNetworkStatusSection(state: DebugUiState) {
    SettingsSection(title = stringResource(R.string.feature_debug_dht_section)) {
        DebugStatusRow(
            label = stringResource(R.string.feature_debug_bootstrapped_label),
            value = if (state.bootstrapped) {
                stringResource(R.string.feature_debug_status_yes)
            } else {
                stringResource(R.string.feature_debug_status_no)
            },
            positive = state.bootstrapped,
        )
        SettingsDivider()
        DebugStatusRow(
            label = stringResource(R.string.feature_debug_known_nodes_label),
            value = state.knownNodes.toString(),
            positive = state.knownNodes > 0,
        )
        SettingsDivider()
        DebugStatusRow(
            label = stringResource(R.string.feature_debug_endpoint_label),
            value = state.publishedEndpoint ?: stringResource(R.string.feature_debug_not_published),
            positive = state.publishedEndpoint != null,
            monospaceValue = state.publishedEndpoint != null,
        )
        state.lastError?.let { error ->
            SettingsDivider()
            VmSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
                shape = VmShapes.field,
                color = VmTheme.colors.bgCriticalSubtle,
            ) {
                VmText(
                    text = error,
                    modifier = Modifier.padding(VmSpacing.md),
                    style = VmTheme.typography.bodyMd,
                    color = VmTheme.colors.textCritical,
                )
            }
        }
    }
}

@Composable
private fun DebugPathSection(state: DebugUiState) {
    SettingsSection(title = "Active network path") {
        DebugStatusRow(
            label = "Diagnostics",
            value = state.networkSnapshot ?: "—",
            positive = state.networkSnapshot != null,
            monospaceValue = state.networkSnapshot != null,
        )
        SettingsDivider()
        DebugStatusRow(
            label = "Last delivery path",
            value = state.lastPath ?: "—",
            positive = state.lastPath != null,
            monospaceValue = state.lastPath != null,
        )
        if (state.recentPaths.size > 1) {
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)) {
                state.recentPaths.take(6).forEach { entry ->
                    VmText(
                        text = entry,
                        style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
                        color = VmTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = VmSpacing.xxs),
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugP2PFlagsSection(
    flags: P2PFlagsUiState,
    onFlagChange: (P2PFlag, Boolean) -> Unit,
    onResetFlags: () -> Unit,
) {
    SettingsSection(title = "P2P migration flags") {
        DebugFlagRow("Multiple nodes (P1)", flags.multiNode) { onFlagChange(P2PFlag.MULTI_NODE, it) }
        SettingsDivider()
        DebugFlagRow("Peer cache (P3)", flags.peerCache) { onFlagChange(P2PFlag.PEER_CACHE, it) }
        SettingsDivider()
        DebugFlagRow("Peer exchange (P4)", flags.peerExchange) { onFlagChange(P2PFlag.PEER_EXCHANGE, it) }
        SettingsDivider()
        DebugFlagRow("DHT participation (P5)", flags.dhtParticipation) {
            onFlagChange(P2PFlag.DHT_PARTICIPATION, it)
        }
        SettingsDivider()
        DebugFlagRow("Relay-capable peer (P6)", flags.relayPeerMode) {
            onFlagChange(P2PFlag.RELAY_PEER_MODE, it)
        }
        SettingsDivider()
        DebugFlagRow("UDP attempts (P7)", flags.natTraversal) { onFlagChange(P2PFlag.UDP_ATTEMPTS, it) }
        SettingsDivider()
        DebugFlagRow("Store & forward (P8)", flags.storeAndForward) {
            onFlagChange(P2PFlag.STORE_AND_FORWARD, it)
        }
        SettingsDivider()
        DebugFlagRow("Demote default relay (P9)", flags.reduceDefaultRelay) {
            onFlagChange(P2PFlag.REDUCE_DEFAULT_RELAY, it)
        }
        SettingsDivider()
        VmTextButton(
            text = "Reset P2P flags to defaults",
            onClick = onResetFlags,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.sm),
        )
    }
}

@Composable
private fun DebugFlagRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(label = label, trailing = SettingsTrailing.Switch(checked, onCheckedChange))
}

@Composable
private fun DebugActionsSection(
    state: DebugUiState,
    onDevModeChange: (Boolean) -> Unit,
    onJoinAndPublish: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.feature_debug_actions_section)) {
        SettingsRow(
            label = stringResource(R.string.feature_debug_dev_mode),
            supporting = stringResource(R.string.feature_debug_dev_mode_hint),
            trailing = SettingsTrailing.Switch(state.devMode, onDevModeChange),
        )
        SettingsDivider()
        Column(
            modifier = Modifier.padding(VmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            VmButton(
                text = stringResource(R.string.feature_debug_join_publish),
                onClick = onJoinAndPublish,
                leadingIcon = Icons.Outlined.CloudUpload,
                modifier = Modifier.fillMaxWidth(),
            )
            VmOutlinedButton(
                text = stringResource(R.string.feature_debug_view_logs),
                onClick = onNavigateToLogs,
                leadingIcon = Icons.Outlined.Article,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DebugAdbSection(adbCommands: String) {
    val copy = rememberCopyToClipboard()
    SettingsSection(title = stringResource(R.string.feature_debug_adb_section)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = VmSpacing.lg, end = VmSpacing.sm, top = VmSpacing.md, bottom = VmSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VmIcon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = VmTheme.colors.iconSecondary,
                modifier = Modifier.padding(end = VmSpacing.sm),
            )
            VmText(
                text = stringResource(R.string.feature_debug_adb_instructions),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
            )
        }
        VmSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            shape = VmShapes.field,
            color = VmTheme.colors.bgSubtle,
        ) {
            VmText(
                text = adbCommands,
                modifier = Modifier.padding(VmSpacing.md),
                style = VmTheme.typography.bodySm.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = VmSpacing.sm, bottom = VmSpacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            VmTextButton(
                text = stringResource(R.string.feature_debug_copy_adb),
                onClick = { copy(adbCommands) },
                leadingIcon = Icons.Outlined.ContentCopy,
            )
        }
    }
}

@Composable
private fun DebugStatusRow(
    label: String,
    value: String,
    positive: Boolean,
    monospaceValue: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = VmSizes.touchTarget)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VmIcon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = if (positive) VmTheme.colors.iconSuccess else VmTheme.colors.iconSecondary,
                modifier = Modifier.padding(end = VmSpacing.lg),
            )
            VmText(
                text = label,
                style = VmTheme.typography.bodyLg,
                color = VmTheme.colors.textPrimary,
            )
        }
        VmText(
            text = value,
            style = if (monospaceValue) UserHashTextStyle else VmTheme.typography.bodyMd,
            color = if (positive) VmTheme.colors.textPrimary else VmTheme.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = VmSpacing.md),
        )
    }
}
