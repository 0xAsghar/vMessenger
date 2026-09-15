package ir.vmessenger.feature.lock

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.rememberDeviceAuthentication
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.data.lock.LockState

/**
 * The biometric button, offered only where it does something.
 *
 * That limit is deliberate rather than an omission. `AppLockCoordinator.unlock` is the one way out
 * of the lock and it takes a PIN, so no biometric result can open the soft lock — a button
 * promising otherwise would be a lie with a fingerprint on it. In strict mode the authentication
 * is real work: the Keystore key is bound to a recent one, and this is what gets it released, so
 * the PIN that came back as [UnlockFeedback.HardwareRefused] goes through on the next try.
 */
@Composable
internal fun BiometricAction(state: AppLockUiState, onResult: (Boolean) -> Unit) {
    val authenticate = rememberBiometricAuthentication(onResult)
    if (state.lockState == LockState.LockedStrict && authenticate != null) {
        OutlinedButton(
            onClick = authenticate,
            enabled = !state.checking,
            modifier = Modifier.heightIn(min = VmSizes.touchTarget),
        ) {
            Icon(imageVector = Icons.Filled.Fingerprint, contentDescription = null)
            Text(
                text = stringResource(R.string.app_lock_biometric_action),
                modifier = Modifier.padding(start = VmSpacing.sm),
            )
        }
    }
}

@Composable
private fun rememberBiometricAuthentication(onResult: (Boolean) -> Unit): (() -> Unit)? =
    rememberDeviceAuthentication(
        title = stringResource(R.string.app_lock_biometric_title),
        subtitle = stringResource(R.string.app_lock_biometric_subtitle),
        onResult = onResult,
    )
