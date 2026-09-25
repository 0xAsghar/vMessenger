package ir.vmessenger.core.designsystem.foundation

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screens that must never be captured, whatever the user's screen-security switch says (typing a
 * server password, say). The activity ORs [active] into its `FLAG_SECURE`.
 */
object SecureWindowRequests {
    private val count = MutableStateFlow(0)
    private val flag = MutableStateFlow(false)

    val active: StateFlow<Boolean> = flag.asStateFlow()

    fun acquire() = update(+1)

    fun release() = update(-1)

    @Synchronized
    private fun update(delta: Int) {
        count.value = (count.value + delta).coerceAtLeast(0)
        flag.value = count.value > 0
    }
}

/** While this is composed, the window is `FLAG_SECURE` (no screenshots, no recents thumbnail). */
@Composable
fun RequireSecureWindow() {
    DisposableEffect(Unit) {
        SecureWindowRequests.acquire()
        onDispose { SecureWindowRequests.release() }
    }
}

/** While this is composed, the screen stays on (a long install the person is watching). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * While this is composed, autofill services are told this window has nothing for them: no saving
 * a server password into a password manager's cloud behind the person's back.
 */
@Composable
fun ExcludeFromAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = before }
    }
}
