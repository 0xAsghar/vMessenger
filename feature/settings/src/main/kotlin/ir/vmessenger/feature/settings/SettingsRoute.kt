package ir.vmessenger.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.VMessengerScaffold

private data class SettingsNavigation(
    val onDebug: () -> Unit,
    val onNodes: () -> Unit,
    val onAbout: () -> Unit,
    val onIdentity: () -> Unit,
    val onSecureWipe: () -> Unit,
    val onBackup: () -> Unit,
)

/** The privacy section's switch states, bundled so the composable stays short on parameters. */
private data class PrivacyToggles(
    val screenSecurity: Boolean,
    val hideNotifications: Boolean,
    val sendReadReceipts: Boolean,
)

@Composable
fun SettingsRoute(
    onNavigateToDebug: () -> Unit = {},
    onNavigateToNodes: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showWipeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    val wipeInProgress by viewModel.wipeInProgress.collectAsStateWithLifecycle()
    // The passphrase is staged in the ViewModel so a configuration change while the picker is open keeps it.
    val createBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.exportTo(uri) }

    VMessengerScaffold(
        title = stringResource(R.string.settings_title),
    ) { padding ->
        SettingsContent(
            viewModel = viewModel,
            navigation = SettingsNavigation(
                onDebug = onNavigateToDebug,
                onNodes = onNavigateToNodes,
                onAbout = onNavigateToAbout,
                onIdentity = onNavigateToIdentity,
                onSecureWipe = { showWipeDialog = true },
                onBackup = {
                    viewModel.dismissBackupStatus()
                    showBackupDialog = true
                },
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
    // The wipe cannot be cancelled and ends by killing the process; the screen
    // is blocked meanwhile so nothing else touches the data being deleted.
    if (wipeInProgress) {
        WipeProgressDialog()
    }
    if (showBackupDialog) {
        BackupPassphraseDialog(
            onConfirm = { passphrase ->
                showBackupDialog = false
                viewModel.beginExport(passphrase)
                createBackupDocument.launch(SettingsViewModel.suggestedBackupFileName())
            },
            onDismiss = { showBackupDialog = false },
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
    val sendReadReceipts by viewModel.sendReadReceipts.collectAsStateWithLifecycle()
    val backupStatus by viewModel.backupExportStatus.collectAsStateWithLifecycle()
    val developerToolsVisible by viewModel.developerToolsVisible.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsThemeSection(themeMode = themeMode, onThemeMode = viewModel::setThemeMode)
        SettingsPrivacySection(
            toggles = PrivacyToggles(
                screenSecurity = screenSecurity,
                hideNotifications = hideNotifications,
                sendReadReceipts = sendReadReceipts,
            ),
            onScreenSecurity = viewModel::setScreenSecurity,
            onHideNotifications = viewModel::setHideNotificationContent,
            onSendReadReceipts = viewModel::setSendReadReceipts,
            onSecureWipe = navigation.onSecureWipe,
        )
        SettingsSection(title = stringResource(R.string.settings_network_section)) {
            SettingsActionRow(
                label = stringResource(R.string.settings_nodes),
                icon = Icons.Outlined.Hub,
                onClick = navigation.onNodes,
            )
            SettingsDivider()
            BatteryOptimizationRow()
            // Hidden in release builds until developer mode is unlocked in About.
            if (developerToolsVisible) {
                SettingsDivider()
                SettingsActionRow(
                    label = stringResource(R.string.settings_debug),
                    icon = Icons.Outlined.BugReport,
                    onClick = navigation.onDebug,
                )
            }
        }
        SettingsIdentitySection(
            onIdentity = navigation.onIdentity,
            onAbout = navigation.onAbout,
        )
        SettingsBackupSection(status = backupStatus, onExport = navigation.onBackup)
    }
}

@Composable
private fun SettingsBackupSection(
    status: BackupExportStatus,
    onExport: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_backup_section)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = status !is BackupExportStatus.InProgress, onClick = onExport)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Backup,
                    contentDescription = stringResource(R.string.settings_backup_icon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_backup_export),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = stringResource(R.string.settings_backup_export_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackupExportStatusText(status = status)
        }
    }
}

@Composable
private fun BackupExportStatusText(status: BackupExportStatus) {
    when (status) {
        BackupExportStatus.Idle -> Unit
        BackupExportStatus.InProgress -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.settings_backup_in_progress),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        BackupExportStatus.Saved -> Text(
            text = stringResource(R.string.settings_backup_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is BackupExportStatus.Failed -> Text(
            text = when (val failure = status.failure) {
                is BackupExportFailure.Bundle -> failure.message
                BackupExportFailure.Write -> stringResource(R.string.settings_backup_write_failed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
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
                    label = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = themeMode == mode,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingsPrivacySection(
    toggles: PrivacyToggles,
    onScreenSecurity: (Boolean) -> Unit,
    onHideNotifications: (Boolean) -> Unit,
    onSendReadReceipts: (Boolean) -> Unit,
    onSecureWipe: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_privacy_section)) {
        SettingsToggleRow(
            label = stringResource(R.string.settings_screen_security),
            icon = Icons.Outlined.Security,
            checked = toggles.screenSecurity,
            onCheckedChange = onScreenSecurity,
        )
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_hide_notifications),
            icon = Icons.Outlined.NotificationsOff,
            checked = toggles.hideNotifications,
            onCheckedChange = onHideNotifications,
        )
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_read_receipts),
            icon = Icons.Outlined.DoneAll,
            checked = toggles.sendReadReceipts,
            onCheckedChange = onSendReadReceipts,
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

/**
 * Aggressive OEM battery managers kill the network foreground service, which
 * silently stops message delivery and notifications. This row deep-links to the
 * system exemption dialog; once granted it shows a passive confirmation.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var exempt by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        exempt = isIgnoringBatteryOptimizations(context)
    }
    if (exempt) {
        SettingsActionRow(
            label = stringResource(R.string.settings_battery_optimization_done),
            icon = Icons.Outlined.BatteryFull,
            onClick = {},
        )
    } else {
        SettingsActionRow(
            label = stringResource(R.string.settings_battery_optimization),
            icon = Icons.Outlined.BatteryAlert,
            onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
                runCatching { launcher.launch(intent) }
            },
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
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

/** Modal, not dismissible: the wipe runs to the end and the process exits by itself. */
@Composable
private fun WipeProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(text = stringResource(R.string.settings_wipe_confirm_title)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(text = stringResource(R.string.settings_wipe_in_progress))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}
