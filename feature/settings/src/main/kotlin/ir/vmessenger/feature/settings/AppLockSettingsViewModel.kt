package ir.vmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.designsystem.component.UiMessage
import ir.vmessenger.data.lock.AppLockCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** The auto-lock timeouts the picker offers, in minutes; zero means the moment the app leaves the screen. */
internal val AUTO_LOCK_CHOICES = listOf(0, 1, 5, 15)

/**
 * The app-lock rows, kept out of [SettingsViewModel].
 *
 * Its own view model rather than four more methods on the settings one: these four preferences are
 * the only ones on the screen whose writes can fail, and the failure has to reach the user.
 */
@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(
    private val appLock: AppLockCoordinator,
    private val privacyPreferences: PrivacyPreferences,
) : ViewModel() {

    /** A property of the hardware, so it is read once and never changes under the screen. */
    val strictModeSupported: Boolean = appLock.strictModeSupported

    val enabled: StateFlow<Boolean> = privacyPreferences.appLockEnabled
        .stateIn(viewModelScope, started(), PrivacyPreferences.DEFAULT_APP_LOCK)

    val strictEnabled: StateFlow<Boolean> = privacyPreferences.strictLockEnabled
        .stateIn(viewModelScope, started(), PrivacyPreferences.DEFAULT_STRICT_LOCK)

    val autoLockMinutes: StateFlow<Int> = privacyPreferences.autoLockMinutes
        .stateIn(viewModelScope, started(), PrivacyPreferences.DEFAULT_AUTO_LOCK_MINUTES)

    val wipeOnFailedAttempts: StateFlow<Boolean> = appLock.wipeOnFailedAttempts
        .stateIn(viewModelScope, started(), PrivacyPreferences.DEFAULT_WIPE_ON_FAILURES)

    private val localMessages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = localMessages.receiveAsFlow()

    /**
     * Sets or replaces the PIN, taking ownership of [pin] and zeroing it either way.
     *
     * The dialog that collects it lives in :feature:lock and is passed in from :app, so this
     * module never has to depend on that one.
     */
    fun setPin(pin: CharArray) {
        viewModelScope.launch {
            try {
                appLock.setPin(pin)
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    fun disableLock() {
        viewModelScope.launch { guarded("clearing the lock") { appLock.clearLock() } }
    }

    fun setStrictMode(strict: Boolean) {
        viewModelScope.launch {
            if (strict) enableStrictMode() else guarded("disabling strict mode") { appLock.disableStrictMode() }
        }
    }

    /**
     * Runs a lock change that touches the Keystore, and reports rather than dies.
     *
     * Enabling strict mode has been guarded since it was written, because wrapping is the obvious
     * place for the Keystore to refuse. Turning it *off* and clearing the lock outright were not,
     * and they reach the same hardware — so the two switches a user reaches for when something has
     * already gone wrong were the two that took the process down with them.
     */
    private suspend fun guarded(what: String, block: suspend () -> Boolean) {
        // Off the main thread like lockIfEnabled: these reach the Keystore and close a SQLCipher
        // database, and doing that on the frame the user tapped a switch is how a settings screen
        // stutters.
        val succeeded = runCatching { withContext(Dispatchers.IO) { block() } }
            .getOrElse { error ->
                AppLogger.warn(TAG, "$what failed: ${error.message}")
                false
            }
        if (!succeeded) localMessages.send(UiMessage.Text(R.string.settings_app_lock_change_failed))
    }

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { privacyPreferences.setAutoLockMinutes(minutes) }
    }

    fun setWipeOnFailedAttempts(enabled: Boolean) {
        viewModelScope.launch { privacyPreferences.setWipeOnFailedAttempts(enabled) }
    }

    /**
     * Wrapping the passphrase uses the auth-bound key, so the Keystore refuses it when the device
     * itself has not been unlocked recently — an ordinary outcome, not a crash. Nothing is written
     * when it fails: the coordinator gives up before it touches the old key.
     */
    private suspend fun enableStrictMode() {
        val succeeded = runCatching {
            withContext(Dispatchers.IO) { appLock.enableStrictMode(STRICT_AUTH_WINDOW_SECONDS) }
        }
            .getOrElse { error ->
                AppLogger.warn(TAG, "enabling strict mode failed: ${error.message}")
                false
            }
        if (!succeeded) localMessages.send(UiMessage.Text(R.string.settings_app_lock_strict_failed))
    }

    private fun started() = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS)

    private companion object {
        const val TAG = "AppLock"
        const val SECONDS_PER_MINUTE = 60

        /**
         * How long one device authentication keeps the strict-mode key usable.
         *
         * Fixed, and as long as the longest auto-lock timeout on offer: the window is baked into
         * the key, so following the timeout setting would mean destroying and re-issuing the key
         * every time it changed — and a re-issue that fails half-way leaves a lock nobody can open.
         * A shorter window would only add refusals on top of a PIN that is asked for anyway.
         */
        val STRICT_AUTH_WINDOW_SECONDS = AUTO_LOCK_CHOICES.max() * SECONDS_PER_MINUTE
    }
}
