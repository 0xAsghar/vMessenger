package ir.vmessenger.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.datastore.ThemeMode

private data class SettingsNavigation(
    val onDebug: () -> Unit,
    val onAbout: () -> Unit,
    val onIdentity: () -> Unit,
    val onSecureWipe: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onNavigateToDebug: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showWipeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        SettingsContent(
            viewModel = viewModel,
            navigation = SettingsNavigation(
                onDebug = onNavigateToDebug,
                onAbout = onNavigateToAbout,
                onIdentity = onNavigateToIdentity,
                onSecureWipe = { showWipeDialog = true },
            ),
            modifier = Modifier.padding(padding),
        )
    }

    if (showWipeDialog) {
        WipeConfirmDialog(
            onConfirm = {
                viewModel.secureWipe()
                showWipeDialog = false
            },
            onDismiss = { showWipeDialog = false },
        )
    }
}

@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val screenSecurity by viewModel.screenSecurityEnabled.collectAsStateWithLifecycle()
    val hideNotifications by viewModel.hideNotificationContent.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.settings_theme_section))
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                label = { Text(text = mode.label()) },
            )
        }

        HorizontalDivider()
        Text(text = stringResource(R.string.settings_privacy_section))
        SettingsSwitchRow(
            label = stringResource(R.string.settings_screen_security),
            checked = screenSecurity,
            onCheckedChange = viewModel::setScreenSecurity,
        )
        SettingsSwitchRow(
            label = stringResource(R.string.settings_hide_notifications),
            checked = hideNotifications,
            onCheckedChange = viewModel::setHideNotificationContent,
        )
        TextButton(onClick = navigation.onSecureWipe) {
            Text(text = stringResource(R.string.settings_secure_wipe))
        }

        HorizontalDivider()
        Text(text = stringResource(R.string.settings_network_section))
        SettingsLinkRow(
            label = stringResource(R.string.settings_debug),
            onClick = navigation.onDebug,
        )

        HorizontalDivider()
        Text(text = stringResource(R.string.settings_identity_section))
        SettingsLinkRow(
            label = stringResource(R.string.settings_identity),
            onClick = navigation.onIdentity,
        )
        SettingsLinkRow(
            label = stringResource(R.string.settings_about),
            onClick = navigation.onAbout,
        )
    }
}

@Composable
private fun WipeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_wipe_confirm_title)) },
        text = { Text(text = stringResource(R.string.settings_wipe_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_wipe_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_wipe_cancel))
            }
        },
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}
