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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

private data class SettingsNavigation(
    val onDebug: () -> Unit,
    val onAbout: () -> Unit,
    val onIdentity: () -> Unit,
    val onSecureWipe: () -> Unit,
)

@Composable
fun SettingsRoute(
    onNavigateToDebug: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showWipeDialog by remember { mutableStateOf(false) }

    VMessengerScaffold(
        title = stringResource(R.string.settings_title),
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsThemeSection(themeMode = themeMode, onThemeMode = viewModel::setThemeMode)
        SettingsPrivacySection(
            screenSecurity = screenSecurity,
            hideNotifications = hideNotifications,
            onScreenSecurity = viewModel::setScreenSecurity,
            onHideNotifications = viewModel::setHideNotificationContent,
            onSecureWipe = navigation.onSecureWipe,
        )
        SettingsSection(title = stringResource(R.string.settings_network_section)) {
            SettingsActionRow(
                label = stringResource(R.string.settings_debug),
                icon = Icons.Outlined.BugReport,
                onClick = navigation.onDebug,
            )
        }
        SettingsIdentitySection(
            onIdentity = navigation.onIdentity,
            onAbout = navigation.onAbout,
        )
    }
}

@Composable
private fun SettingsThemeSection(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_theme_section)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    label = { Text(text = mode.label()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SettingsPrivacySection(
    screenSecurity: Boolean,
    hideNotifications: Boolean,
    onScreenSecurity: (Boolean) -> Unit,
    onHideNotifications: (Boolean) -> Unit,
    onSecureWipe: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_privacy_section)) {
        SettingsToggleRow(
            label = stringResource(R.string.settings_screen_security),
            icon = Icons.Outlined.Security,
            checked = screenSecurity,
            onCheckedChange = onScreenSecurity,
        )
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_hide_notifications),
            icon = Icons.Outlined.NotificationsOff,
            checked = hideNotifications,
            onCheckedChange = onHideNotifications,
        )
        SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_secure_wipe),
            icon = Icons.Outlined.DeleteForever,
            onClick = onSecureWipe,
            destructive = true,
        )
    }
}

@Composable
private fun SettingsIdentitySection(
    onIdentity: () -> Unit,
    onAbout: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_identity_section)) {
        SettingsActionRow(
            label = stringResource(R.string.settings_identity),
            icon = Icons.Outlined.Fingerprint,
            onClick = onIdentity,
        )
        SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_about),
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            onClick = onAbout,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
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
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}
