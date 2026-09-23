package ir.vmessenger.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.common.text.VmLocale
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.component.UiMessageSnackbarEffect
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.component.VmChip
import ir.vmessenger.core.designsystem.component.VmDialog
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmSnackbarHost
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.rememberVmSnackbar
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

@Suppress("LongParameterList") // one entry per destination the settings tab can reach
private data class SettingsNavigation(
    val onDebug: () -> Unit,
    val onNodes: () -> Unit,
    val onIdentity: () -> Unit,
    val onSecureWipe: () -> Unit,
    val onBackup: () -> Unit,
    val onBlockedContacts: () -> Unit,
    val onActivityLog: () -> Unit,
    val onUpdate: () -> Unit,
    /** Both open the lock module's PIN screen, which lives outside this module. */
    val onSetUpAppLock: () -> Unit,
    val onChangeAppLockPin: () -> Unit,
)

/** The app's language and the setter for it, bundled so [SettingsContent] keeps its signature. */
@Immutable
internal data class LanguageSetting(
    val current: VmLocale,
    val onSelect: (VmLocale) -> Unit,
)

/** The privacy section's switches, each with the setter that belongs to it. */
private data class PrivacyToggles(
    val screenSecurity: Boolean,
    val onScreenSecurity: (Boolean) -> Unit,
    val hideNotifications: Boolean,
    val onHideNotifications: (Boolean) -> Unit,
    val sendReadReceipts: Boolean,
    val onSendReadReceipts: (Boolean) -> Unit,
)

/** Shows the PIN dialog when asked and hands the result to the ViewModel, which owns the array. */
@Composable
private fun PinPrompt(
    visible: Boolean,
    dialog: @Composable (onDone: (CharArray?) -> Unit) -> Unit,
    viewModel: AppLockSettingsViewModel,
    onDismissed: () -> Unit,
) {
    if (!visible) return
    dialog { pin ->
        onDismissed()
        pin?.let(viewModel::setPin)
    }
}

// Navigation callbacks only; they are forwarded one-for-one to rows and never combined.
@Composable
@Suppress("LongParameterList")
fun SettingsRoute(
    onNavigateToDebug: () -> Unit = {},
    onNavigateToNodes: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    onNavigateToBlockedContacts: () -> Unit = {},
    onNavigateToActivityLog: () -> Unit = {},
    /**
     * The app's language and the setter for it. Supplied by :app, which owns the AppCompat
     * per-app-language API, so settings never depends on it — the same arrangement as [pinDialog].
     */
    language: VmLocale = VmLocale.current,
    onLanguage: (VmLocale) -> Unit = {},
    onNavigateToUpdate: () -> Unit = {},
    /**
     * Collects a PIN. Supplied by :app from :feature:lock, so settings never depends on it —
     * null means the user cancelled, and the caller owns and zeroes the array.
     */
    pinDialog: @Composable (onDone: (CharArray?) -> Unit) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    appLockViewModel: AppLockSettingsViewModel = hiltViewModel(),
) {
    var askingForPin by remember { mutableStateOf(false) }
    PinPrompt(visible = askingForPin, dialog = pinDialog, viewModel = appLockViewModel) { askingForPin = false }
    var showWipeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    val wipeInProgress by viewModel.wipeInProgress.collectAsStateWithLifecycle()
    val snackbar = rememberVmSnackbar()
    UiMessageSnackbarEffect(messages = appLockViewModel.messages, hostState = snackbar)
    // The passphrase is staged in the ViewModel so a configuration change while the picker is open keeps it.
    val createBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> viewModel.exportTo(uri) }

    val scroll = rememberScrollState()
    VMessengerScaffold(
        title = stringResource(R.string.settings_title),
        snackbarHost = { VmSnackbarHost(hostState = snackbar) },
        actions = { AboutAction(onClick = onNavigateToAbout) },
        scrolled = scroll.canScrollBackward,
    ) { padding ->
        val navigation = SettingsNavigation(
            onDebug = onNavigateToDebug,
            onNodes = onNavigateToNodes,
            onIdentity = onNavigateToIdentity,
            onBlockedContacts = onNavigateToBlockedContacts,
            onActivityLog = onNavigateToActivityLog,
            onUpdate = onNavigateToUpdate,
            onSetUpAppLock = { askingForPin = true },
            onChangeAppLockPin = { askingForPin = true },
            onSecureWipe = { showWipeDialog = true },
            onBackup = {
                viewModel.dismissBackupStatus()
                showBackupDialog = true
            },
        )
        SettingsContent(
            viewModel = viewModel,
            appLockViewModel = appLockViewModel,
            navigation = navigation,
            language = LanguageSetting(language, onLanguage),
            scroll = scroll,
            modifier = Modifier.padding(padding),
        )
    }

    SettingsDialogs(
        state = SettingsDialogState(showWipeDialog, wipeInProgress, showBackupDialog),
        viewModel = viewModel,
        onWipeDismiss = { showWipeDialog = false },
        onBackupDismiss = { showBackupDialog = false },
        onBackupConfirmed = { createBackupDocument.launch(SettingsViewModel.suggestedBackupFileName()) },
    )
}

/** Which of the screen's three modals is up. */
@Immutable
private data class SettingsDialogState(
    val confirmWipe: Boolean,
    val wipeInProgress: Boolean,
    val backupPassphrase: Boolean,
)

@Composable
private fun SettingsDialogs(
    state: SettingsDialogState,
    viewModel: SettingsViewModel,
    onWipeDismiss: () -> Unit,
    onBackupDismiss: () -> Unit,
    onBackupConfirmed: () -> Unit,
) {
    if (state.confirmWipe) {
        WipeConfirmDialog(
            onConfirm = {
                viewModel.secureWipe()
                onWipeDismiss()
            },
            onDismiss = onWipeDismiss,
        )
    }
    // The wipe cannot be cancelled and ends by killing the process; the screen
    // is blocked meanwhile so nothing else touches the data being deleted.
    if (state.wipeInProgress) {
        WipeProgressDialog()
    }
    if (state.backupPassphrase) {
        BackupPassphraseDialog(
            onConfirm = { passphrase ->
                onBackupDismiss()
                viewModel.beginExport(passphrase)
                onBackupConfirmed()
            },
            onDismiss = onBackupDismiss,
        )
    }
}

@Composable
@Suppress("LongParameterList") // The two view models, two bundles, and the scroll the top bar reads.
private fun SettingsContent(
    viewModel: SettingsViewModel,
    appLockViewModel: AppLockSettingsViewModel,
    navigation: SettingsNavigation,
    language: LanguageSetting,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val backupStatus by viewModel.backupExportStatus.collectAsStateWithLifecycle()
    val developerToolsVisible by viewModel.developerToolsVisible.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    // No side padding: the sections run edge to edge and pad their own rows, as in Element X.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = VmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        profile?.let { ProfileHeader(profile = it, onClick = navigation.onIdentity) }
        SettingsLanguageSection(language = language.current, onLanguage = language.onSelect)
        SettingsThemeSection(themeMode = themeMode, onThemeMode = viewModel::setThemeMode)
        SettingsPrivacySection(
            toggles = privacyToggles(viewModel),
            appLock = appLockSettings(appLockViewModel, navigation),
            onBlockedContacts = navigation.onBlockedContacts,
            onActivityLog = navigation.onActivityLog,
            onSecureWipe = navigation.onSecureWipe,
        )
        SettingsSection(title = stringResource(R.string.settings_network_section)) {
            SettingsActionRow(
                label = stringResource(R.string.settings_nodes),
                icon = Icons.Outlined.Hub,
                onClick = navigation.onNodes,
            )
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
        SettingsUpdateSection(available = updateAvailable, onUpdate = navigation.onUpdate)
        SettingsBackupSection(status = backupStatus, onExport = navigation.onBackup)
    }
}

@Composable
private fun SettingsBackupSection(
    status: BackupExportStatus,
    onExport: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_backup_section)) {
        SettingsRow(
            label = stringResource(R.string.settings_backup_export),
            icon = Icons.Outlined.Backup,
            supporting = stringResource(R.string.settings_backup_export_body),
            trailing = SettingsTrailing.None,
            enabled = status !is BackupExportStatus.InProgress,
            onClick = onExport,
        )
        // Under the row's label, not under its icon.
        Column(modifier = Modifier.padding(start = STATUS_INDENT, end = VmSpacing.lg)) {
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
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            VmProgressIndicator(size = VmSizes.iconSm, color = VmTheme.colors.iconSecondary)
            VmText(
                text = stringResource(R.string.settings_backup_in_progress),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
            )
        }
        BackupExportStatus.Saved -> VmText(
            text = stringResource(R.string.settings_backup_saved),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSuccess,
        )
        is BackupExportStatus.Failed -> VmText(
            text = when (val failure = status.failure) {
                is BackupExportFailure.Bundle -> failure.message
                BackupExportFailure.Write -> stringResource(R.string.settings_backup_write_failed)
            },
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textCritical,
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
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            ThemeMode.entries.forEach { mode ->
                VmChip(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    label = mode.label(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The bar's one action. It sits in the actions slot, at the layout end of the bar. */
@Composable
private fun AboutAction(onClick: () -> Unit) {
    VmIconButton(
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
        contentDescription = stringResource(R.string.settings_about),
        onClick = onClick,
    )
}

/** Collected here rather than in [SettingsContent] so that one stays a list of sections. */
@Composable
private fun privacyToggles(viewModel: SettingsViewModel): PrivacyToggles {
    val screenSecurity by viewModel.screenSecurityEnabled.collectAsStateWithLifecycle()
    val hideNotifications by viewModel.hideNotificationContent.collectAsStateWithLifecycle()
    val sendReadReceipts by viewModel.sendReadReceipts.collectAsStateWithLifecycle()
    return PrivacyToggles(
        screenSecurity = screenSecurity,
        onScreenSecurity = viewModel::setScreenSecurity,
        hideNotifications = hideNotifications,
        onHideNotifications = viewModel::setHideNotificationContent,
        sendReadReceipts = sendReadReceipts,
        onSendReadReceipts = viewModel::setSendReadReceipts,
    )
}

@Composable
private fun appLockSettings(
    viewModel: AppLockSettingsViewModel,
    navigation: SettingsNavigation,
): AppLockSettings {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val strictEnabled by viewModel.strictEnabled.collectAsStateWithLifecycle()
    val autoLockMinutes by viewModel.autoLockMinutes.collectAsStateWithLifecycle()
    val wipeOnFailedAttempts by viewModel.wipeOnFailedAttempts.collectAsStateWithLifecycle()
    return AppLockSettings(
        enabled = enabled,
        strictEnabled = strictEnabled,
        strictSupported = viewModel.strictModeSupported,
        autoLockMinutes = autoLockMinutes,
        wipeOnFailedAttempts = wipeOnFailedAttempts,
        onSetUp = navigation.onSetUpAppLock,
        onDisable = viewModel::disableLock,
        onChangePin = navigation.onChangeAppLockPin,
        onStrictMode = viewModel::setStrictMode,
        onAutoLockMinutes = viewModel::setAutoLockMinutes,
        onWipeOnFailedAttempts = viewModel::setWipeOnFailedAttempts,
    )
}

@Composable
private fun SettingsPrivacySection(
    toggles: PrivacyToggles,
    appLock: AppLockSettings,
    onBlockedContacts: () -> Unit,
    onActivityLog: () -> Unit,
    onSecureWipe: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_privacy_section)) {
        AppLockRows(state = appLock)
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_screen_security),
            icon = Icons.Outlined.Security,
            checked = toggles.screenSecurity,
            onCheckedChange = toggles.onScreenSecurity,
        )
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_hide_notifications),
            icon = Icons.Outlined.NotificationsOff,
            checked = toggles.hideNotifications,
            onCheckedChange = toggles.onHideNotifications,
        )
        SettingsDivider()
        SettingsToggleRow(
            label = stringResource(R.string.settings_read_receipts),
            icon = Icons.Outlined.DoneAll,
            checked = toggles.sendReadReceipts,
            onCheckedChange = toggles.onSendReadReceipts,
        )
        SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_blocked_contacts),
            icon = Icons.Outlined.Block,
            onClick = onBlockedContacts,
        )
        SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.feature_settings_activity_row),
            icon = Icons.Outlined.History,
            onClick = onActivityLog,
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
 * Aggressive OEM battery managers kill the network foreground service, which silently stops message
 * delivery and notifications. This row deep-links to the system exemption dialog — and once the
 * exemption is granted it emits nothing at all, because a settings row that does nothing when
 * tapped is worse than no row. It also re-reads on resume: the exemption can be granted or revoked
 * from the system settings app, which never calls back here.
 *
 * Emits its own leading divider, since a section cannot know whether this row will render.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        exempt = isIgnoringBatteryOptimizations(context)
    }
    LifecycleResumeEffect(Unit) {
        exempt = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    if (exempt) return
    SettingsDivider()
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

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

@Composable
private fun SettingsUpdateSection(available: Boolean, onUpdate: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_update_section)) {
        SettingsActionRow(
            label = stringResource(R.string.settings_update_row),
            icon = Icons.Outlined.SystemUpdate,
            onClick = onUpdate,
            badge = available,
        )
    }
}

/**
 * Who this device is. The user hash is the one thing they hand to other people, so it sits in
 * the header rather than a screen deeper; tapping opens the identity screen with the QR.
 */
@Composable
private fun ProfileHeader(profile: SettingsProfile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.lg),
    ) {
        Avatar(seed = profile.identityHash, name = profile.displayName, size = VmSizes.avatarLg)
        Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs)) {
            VmText(
                text = profile.displayName,
                style = VmTheme.typography.headingSm,
                color = VmTheme.colors.textPrimary,
            )
            UserHashText(
                text = profile.userHash,
                style = VmTheme.typography.bodySm.copy(color = VmTheme.colors.textSecondary),
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        label = label,
        icon = icon,
        trailing = SettingsTrailing.Switch(checked = checked, onCheckedChange = onCheckedChange),
    )
}

@Composable
private fun SettingsActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    badge: Boolean = false,
) {
    SettingsRow(
        label = label,
        icon = icon,
        // A word, not a dot: "new version" says what is waiting without the user having to open it.
        trailing = if (badge) {
            SettingsTrailing.Badge(stringResource(R.string.settings_update_badge))
        } else {
            SettingsTrailing.None
        },
        destructive = destructive,
        onClick = onClick,
    )
}

@Composable
private fun WipeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.settings_wipe_confirm_title),
        body = stringResource(R.string.settings_wipe_confirm_body),
        confirmLabel = stringResource(R.string.settings_wipe_confirm_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        dismissLabel = stringResource(R.string.settings_wipe_cancel),
    )
}

/** Modal, not dismissible: the wipe runs to the end and the process exits by itself. */
@Composable
private fun WipeProgressDialog() {
    // A dismiss request that does nothing: neither back nor a tap outside can close it.
    VmDialog(
        onDismissRequest = {},
        title = stringResource(R.string.settings_wipe_confirm_title),
        buttons = {},
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VmProgressIndicator(size = VmSizes.iconMd)
            VmText(text = stringResource(R.string.settings_wipe_in_progress))
        }
    }
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

/** Where a row's label starts: its side padding, its icon and the gap after it. */
private val STATUS_INDENT = VmSpacing.lg + VmSpacing.xl + VmSpacing.lg
