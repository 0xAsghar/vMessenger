package ir.vmessenger.core.designsystem.component

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity

/**
 * The pair an authentication-bound Keystore key is willing to answer to; anything weaker would not
 * release it.
 */
private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Backing out of the prompt is the user changing their mind, not something to report back. */
private val CANCELLED = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
)

/**
 * A system authentication prompt, or null when there is nothing to show it with.
 *
 * [allowDeviceCredential] is a security decision, not a convenience. An auth-bound Keystore key is
 * bound to both, so releasing one needs it true. The *soft* app lock is not a Keystore key at all —
 * it is a second gate in front of a phone the holder has already unlocked — so accepting the device
 * credential there would let the screen-lock PIN open the app lock, and the second factor would
 * collapse into the first for anyone who had been handed the unlocked phone.
 *
 * Here rather than in one feature because two of them need the same prompt for the same reason:
 * an auth-bound Keystore key refuses to be *used* unless the device was authenticated recently.
 * The lock screen needs it to get the database key released; the settings screen needs it to put
 * the key there in the first place, and without it turning strict mode on simply failed with
 * "User not authenticated" for anyone who had not unlocked their phone in the last few minutes.
 *
 * `BiometricPrompt` needs a `FragmentActivity`; the app's activity is one for exactly this reason.
 * If it ever stops being one, callers get null and can degrade rather than crash.
 */
@Composable
fun rememberDeviceAuthentication(
    title: String,
    subtitle: String,
    allowDeviceCredential: Boolean,
    onResult: (Boolean) -> Unit,
): (() -> Unit)? {
    val allowed = if (allowDeviceCredential) AUTHENTICATORS else BiometricManager.Authenticators.BIOMETRIC_STRONG
    val context = LocalContext.current
    val cancelLabel = stringResource(android.R.string.cancel)
    val latest = rememberUpdatedState(onResult)
    val activity = remember(context) { context.findFragmentActivity() }
    val prompt = remember(activity, allowed) {
        activity
            ?.takeIf { BiometricManager.from(it).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS }
            ?.let { BiometricPrompt(it, PromptCallback(latest)) }
    }
    val info = remember(title, subtitle, allowed) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
            .apply {
                // The prompt supplies its own negative button when the device credential is in the
                // allowed set, and demands one when it is not.
                if (!allowDeviceCredential) setNegativeButtonText(cancelLabel)
            }
            .build()
    }
    if (prompt == null) return null
    return { prompt.authenticate(info) }
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
