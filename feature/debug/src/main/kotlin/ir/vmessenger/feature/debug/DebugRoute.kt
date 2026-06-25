package ir.vmessenger.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.theme.UserHashTextStyle

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

    VMessengerScaffold(
        title = stringResource(R.string.feature_debug_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DebugNetworkStatusSection(state = state)
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
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DebugActionsSection(
    state: DebugUiState,
    onDevModeChange: (Boolean) -> Unit,
    onJoinAndPublish: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.feature_debug_actions_section)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.feature_debug_dev_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.feature_debug_dev_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.devMode,
                onCheckedChange = onDevModeChange,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        )
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onJoinAndPublish,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text = stringResource(R.string.feature_debug_join_publish))
            }
            OutlinedButton(
                onClick = onNavigateToLogs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Article,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text = stringResource(R.string.feature_debug_view_logs))
            }
        }
    }
}

@Composable
private fun DebugAdbSection(adbCommands: String) {
    val clipboard = LocalClipboardManager.current
    SettingsSection(title = stringResource(R.string.feature_debug_adb_section)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = stringResource(R.string.feature_debug_adb_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Text(
                text = adbCommands,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(adbCommands)) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(text = stringResource(R.string.feature_debug_copy_adb))
            }
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = if (positive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = value,
            style = if (monospaceValue) UserHashTextStyle else MaterialTheme.typography.bodyMedium,
            color = if (positive) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
    }
}
