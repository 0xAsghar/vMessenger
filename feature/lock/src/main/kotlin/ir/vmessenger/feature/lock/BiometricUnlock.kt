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
 * The biometric button, offered wherever it does something — which is both modes, for different
 * reasons.
 *
 * It used to be shown only in strict mode, on the argument that a PIN is the one way out of a soft
 * lock. That was never true of the code beside it: `AppLockCoordinator.unlockWithBiometric` opens a
 * soft lock outright and refuses a strict one, and it had no caller that could ever reach it, so
 * the feature existed and nobody could use it. A soft lock covers the screen, and a fingerprint is
 * a perfectly good way past a screen cover; making the user type a PIN after their fingerprint was
 * accepted would be the theatre.
 *
 * In strict mode the authentication is different work: the Keystore key is bound to a recent one,
 * and this is what gets it released, so the PIN that came back as
 * [UnlockFeedback.HardwareRefused] goes through on the next try. The view model already tells the
 * two apart by what `unlockWithBiometric` returns.
 */
@Composable
internal fun BiometricAction(state: AppLockUiState, onResult: (Boolean) -> Unit) {
    val strict = state.lockState == LockState.LockedStrict
    val authenticate = rememberBiometricAuthentication(strict, onResult)
    val locked = state.lockState == LockState.Locked || state.lockState == LockState.LockedStrict
    if (locked && authenticate != null) {
        OutlinedButton(
            onClick = authenticate,
            enabled = !state.checking,
            modifier = Modifier.heightIn(min = VmSizes.touchTarget),
        ) {
            Icon(imageVector = Icons.Filled.Fingerprint, contentDescription = null)
            Text(
                // Strict mode's prompt takes the device credential too; the soft one is narrowed
                // to a biometric on purpose, so it must not offer the device PIN in its label.
                text = stringResource(
                    if (strict) R.string.app_lock_biometric_action else R.string.app_lock_biometric_action_soft,
                ),
                modifier = Modifier.padding(start = VmSpacing.sm),
            )
        }
    }
}

@Composable
private fun rememberBiometricAuthentication(strict: Boolean, onResult: (Boolean) -> Unit): (() -> Unit)? =
    rememberDeviceAuthentication(
        title = stringResource(R.string.app_lock_biometric_title),
        // Strict mode needs the Keystore key released and says so. A soft lock is only uncovering
        // a screen, and promising to release a database key there would be a claim about the wrong
        // thing.
        subtitle = stringResource(
            if (strict) R.string.app_lock_biometric_subtitle else R.string.app_lock_biometric_subtitle_soft,
        ),
        // Only strict mode accepts the device credential, because only its Keystore key requires
        // one. Accepting it for the soft lock would mean the phone's own PIN opens the app lock.
        allowDeviceCredential = strict,
        onResult = onResult,
    )
