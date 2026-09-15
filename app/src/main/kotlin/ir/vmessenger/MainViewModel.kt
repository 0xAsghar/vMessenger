package ir.vmessenger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.app.network.startNetworkService
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.data.lock.AppLockCoordinator
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.domain.usecase.identity.HasIdentityUseCase
import ir.vmessenger.navigation.VmRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    themePreferences: ThemePreferences,
    private val privacyPreferences: PrivacyPreferences,
    // Provider, not the use case itself: resolving it builds the identity repository, which
    // builds the encrypted database, which asks for a passphrase that strict mode keeps behind
    // the lock. Injecting it directly crashed the activity before it could draw the lock screen
    // to unlock with — an install nobody could open. It is only ever called after unlock.
    private val hasIdentity: Provider<HasIdentityUseCase>,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val appLock: AppLockCoordinator,
) : ViewModel() {
    val lockState: StateFlow<LockState> = appLock.state

    /** Drives `FLAG_SECURE` on the activity window; starts secure until the store answers. */
    val screenSecurityEnabled: StateFlow<Boolean> = privacyPreferences.screenSecurityEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val darkTheme: StateFlow<Boolean?> = themePreferences.themeMode
        .map { mode ->
            when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _startRoute = MutableStateFlow<VmRoute?>(null)

    /**
     * Where the navigation graph starts, or `null` while it is still being
     * decided. The system splash screen is held on screen for exactly as long as
     * this is `null`, which replaces the old in-app splash and its fixed delay:
     * the first frame the user sees is already the right destination.
     */
    val startRoute: StateFlow<VmRoute?> = _startRoute.asStateFlow()

    private val _pendingConversationId = MutableStateFlow<String?>(null)

    /** Conversation a tapped message notification asked for; consumed once by the NavHost. */
    val pendingConversationId: StateFlow<String?> = _pendingConversationId.asStateFlow()

    /** Monotonic time the app was last backgrounded; null while it is in the foreground. */
    private var awaySince: Long? = null

    init {
        viewModelScope.launch {
            // The lock goes up first. Under strict mode the database cannot be opened until the
            // user authenticates, so resolving the start destination — which asks whether an
            // identity exists — has to wait for it rather than the other way round.
            appLock.lockIfEnabled()
            appLock.state.collect { if (it == LockState.Unlocked) onUnlocked() }
        }
    }

    /**
     * Every unlock, not only the first: the start destination is decided once, but the network
     * service is not.
     *
     * A strict lock stops the service — that is what makes "nothing is delivered while locked"
     * true rather than a caption — so something has to bring it back, and this is the only place
     * that knows the app is both unlocked and in the foreground, which is where starting a
     * foreground service is allowed.
     */
    private suspend fun onUnlocked() {
        resolveStartRoute()
        if (!databaseKeyProvider.isLocked) {
            withContext(Dispatchers.Main) { startNetworkService(context, reason = "unlock") }
        }
    }

    private suspend fun resolveStartRoute() {
        if (_startRoute.value != null) return
        // The application starts this off the main thread; the splash waits for it (the call is
        // idempotent) because the identity lookup opens the encrypted database.
        withContext(Dispatchers.IO) { databaseKeyProvider.initialize() }
        _startRoute.value = if (hasIdentity.get()()) VmRoute.Home else VmRoute.Onboarding
    }

    fun onBackgrounded(elapsedRealtimeMs: Long) {
        awaySince = elapsedRealtimeMs
    }

    /**
     * Re-arms the lock if the app has been away past the user's timeout.
     *
     * [elapsedRealtimeMs] is monotonic, deliberately: a timeout measured against the wall clock
     * could be defeated by changing the device date.
     */
    fun onForegrounded(elapsedRealtimeMs: Long) {
        val since = awaySince ?: return
        awaySince = null
        viewModelScope.launch {
            val minutes = privacyPreferences.autoLockMinutes.first()
            if (elapsedRealtimeMs - since >= minutes * MILLIS_PER_MINUTE) appLock.lockIfEnabled()
        }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }

    /** Called from `onCreate`/`onNewIntent` with the notification's conversation id, if any. */
    fun onDeepLink(conversationId: String?) {
        if (!conversationId.isNullOrBlank()) {
            _pendingConversationId.value = conversationId
        }
    }

    fun consumePendingConversation() {
        _pendingConversationId.value = null
    }
}
