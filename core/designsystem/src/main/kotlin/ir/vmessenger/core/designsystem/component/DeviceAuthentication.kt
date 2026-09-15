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
    onResult: (Boolean) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    val activity = remember(context) { context.findFragmentActivity() }
    val prompt = remember(activity) {
        activity
            ?.takeIf { BiometricManager.from(it).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS }
            ?.let { BiometricPrompt(it, PromptCallback(latest)) }
    }
    val info = remember(title, subtitle) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // No negative button: with DEVICE_CREDENTIAL allowed, the prompt supplies its own.
            .setAllowedAuthenticators(AUTHENTICATORS)
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
