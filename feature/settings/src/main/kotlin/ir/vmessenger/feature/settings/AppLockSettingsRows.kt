package ir.vmessenger.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.SettingsDivider
import ir.vmessenger.core.designsystem.component.SettingsRow
import ir.vmessenger.core.designsystem.component.SettingsTrailing
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

/** Android 11 is where the Keystore learned to hold a key behind a recent authentication. */
private const val STRICT_MODE_MIN_ANDROID = "11"

/**
 * What the app-lock rows show and what they call.
 *
 * Setting or changing a PIN is not in here: that screen belongs to the lock module, so the intent
 * leaves through [onSetUp] and [onChangePin] and `:app` decides what opens.
 */
internal data class AppLockSettings(
    val enabled: Boolean,
    val strictEnabled: Boolean,
    val strictSupported: Boolean,
    val autoLockMinutes: Int,
    val wipeOnFailedAttempts: Boolean,
    val onSetUp: () -> Unit,
    val onDisable: () -> Unit,
    val onChangePin: () -> Unit,
    val onStrictMode: (Boolean) -> Unit,
    val onAutoLockMinutes: (Int) -> Unit,
    val onWipeOnFailedAttempts: (Boolean) -> Unit,
)

/**
 * The app-lock block of the privacy section. Everything under the first switch only has meaning
 * once there is a lock, so those rows appear and disappear as a group.
 */
@Composable
internal fun AppLockRows(state: AppLockSettings) {
    SettingsRow(
        label = stringResource(R.string.settings_app_lock),
        icon = Icons.Outlined.Lock,
        supporting = stringResource(R.string.settings_app_lock_body),
        trailing = SettingsTrailing.Switch(
            checked = state.enabled,
            onCheckedChange = { on -> if (on) state.onSetUp() else state.onDisable() },
        ),
    )
    if (!state.enabled) return
    SettingsDivider()
    SettingsRow(
        label = stringResource(R.string.settings_app_lock_change_pin),
        icon = Icons.Outlined.Password,
        onClick = state.onChangePin,
    )
    SettingsDivider()
    StrictModeRow(state = state)
    SettingsDivider()
    AutoLockRow(state = state)
    SettingsDivider()
    WipeAfterFailuresRow(state = state)
}

/**
 * The one switch on this screen that changes what the data is worth at rest — and the one that can
 * cost the user every message, so it asks first and says why.
 */
@Composable
private fun StrictModeRow(state: AppLockSettings) {
    var confirming by remember { mutableStateOf(false) }
    SettingsRow(
        label = stringResource(R.string.settings_app_lock_strict),
        icon = Icons.Outlined.EnhancedEncryption,
        supporting = if (state.strictSupported) {
            stringResource(R.string.settings_app_lock_strict_body)
        } else {
            stringResource(
                R.string.settings_app_lock_strict_unsupported,
                VmTextFormat.persianDigits(STRICT_MODE_MIN_ANDROID),
            )
        },
        trailing = SettingsTrailing.Switch(
            checked = state.strictEnabled,
            onCheckedChange = { on -> if (on) confirming = true else state.onStrictMode(false) },
        ),
        enabled = state.strictSupported,
    )
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_app_lock_strict_confirm_title),
            body = stringResource(R.string.settings_app_lock_strict_confirm_body),
            confirmLabel = stringResource(R.string.settings_app_lock_strict_confirm_action),
            onConfirm = {
                confirming = false
                state.onStrictMode(true)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun AutoLockRow(state: AppLockSettings) {
    var picking by remember { mutableStateOf(false) }
    SettingsRow(
        label = stringResource(R.string.settings_app_lock_timeout),
        icon = Icons.Outlined.Timer,
        supporting = stringResource(R.string.settings_app_lock_timeout_body),
        trailing = SettingsTrailing.Text(autoLockLabel(state.autoLockMinutes)),
        onClick = { picking = true },
    )
    if (picking) {
        AutoLockDialog(
            selected = state.autoLockMinutes,
            onSelect = { minutes ->
                picking = false
                state.onAutoLockMinutes(minutes)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Off by default, and confirmed on the way on rather than on the way off: it destroys data, and
 * nothing but a wrong PIN is needed to fire it.
 */
@Composable
private fun WipeAfterFailuresRow(state: AppLockSettings) {
    var confirming by remember { mutableStateOf(false) }
    SettingsRow(
        label = stringResource(R.string.settings_app_lock_wipe),
        icon = Icons.Outlined.DeleteForever,
        supporting = stringResource(R.string.settings_app_lock_wipe_body),
        trailing = SettingsTrailing.Switch(
            checked = state.wipeOnFailedAttempts,
            onCheckedChange = { on -> if (on) confirming = true else state.onWipeOnFailedAttempts(false) },
        ),
    )
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_app_lock_wipe_confirm_title),
            body = stringResource(R.string.settings_app_lock_wipe_confirm_body),
            confirmLabel = stringResource(R.string.settings_app_lock_wipe_confirm_action),
            onConfirm = {
                confirming = false
                state.onWipeOnFailedAttempts(true)
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}

@Composable
private fun AutoLockDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_app_lock_timeout_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                AUTO_LOCK_CHOICES.forEach { minutes ->
                    AutoLockChoice(
                        minutes = minutes,
                        selected = minutes == selected,
                        onSelect = { onSelect(minutes) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_app_lock_timeout_close))
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun AutoLockChoice(
    minutes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .heightIn(min = VmSizes.touchTarget)
            .padding(horizontal = VmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = autoLockLabel(minutes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun autoLockLabel(minutes: Int): String = if (minutes <= 0) {
    stringResource(R.string.settings_app_lock_timeout_immediate)
} else {
    stringResource(
        R.string.settings_app_lock_timeout_minutes,
        VmTextFormat.persianDigits(minutes.toString()),
    )
}
