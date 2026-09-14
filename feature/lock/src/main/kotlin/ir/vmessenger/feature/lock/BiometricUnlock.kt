package ir.vmessenger.feature.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.data.lock.LockState

/** The pair the strict-mode key itself is bound to; anything weaker would not release it. */
private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Backing out of the prompt is the user changing their mind, not something to report back. */
private val CANCELLED = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
)

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

/**
 * The system prompt, or null when there is nothing to show it with.
 *
 * `BiometricPrompt` needs a `FragmentActivity`; :app is one for exactly this reason. If it ever
 * stops being one the button disappears instead of the screen crashing, which is survivable
 * because the PIN is a complete way in on its own.
 */
@Composable
private fun rememberBiometricAuthentication(onResult: (Boolean) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val activity = remember(context) { context.findFragmentActivity() }
    val prompt = remember(activity) {
        activity
            ?.takeIf { BiometricManager.from(it).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS }
            ?.let { BiometricPrompt(it, PromptCallback(latest)) }
    }
    val info = promptInfo(
        title = stringResource(R.string.app_lock_biometric_title),
        subtitle = stringResource(R.string.app_lock_biometric_subtitle),
    )
    if (prompt == null) return null
    return { prompt.authenticate(info) }
}

@Composable
private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
    remember(title, subtitle) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // No negative button: with DEVICE_CREDENTIAL allowed, the prompt supplies its own.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
    }

private fun Context.findFragmentActivity(): FragmentActivity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<FragmentActivity>()
        .firstOrNull()

private class PromptCallback(
    private val onResult: State<(Boolean) -> Unit>,
) : BiometricPrompt.AuthenticationCallback() {
    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        onResult.value(true)
    }

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        if (errorCode !in CANCELLED) onResult.value(false)
    }
}
