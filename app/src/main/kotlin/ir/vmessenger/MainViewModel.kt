package ir.vmessenger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.app.network.startNetworkService
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.datastore.NodeSetupChoice
import ir.vmessenger.core.datastore.NodeSetupPreferences
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.data.lock.AppLockCoordinator
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.domain.usecase.identity.HasIdentityUseCase
import ir.vmessenger.navigation.VmRoute
import ir.vmessenger.ui.share.PendingShareStore
import ir.vmessenger.ui.share.SharePayload
import ir.vmessenger.ui.themeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
@Suppress("LongParameterList") // the activity's view model: one collaborator per thing the window needs
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
    private val pendingShare: PendingShareStore,
    private val nodeSetupPreferences: NodeSetupPreferences,
) : ViewModel() {
    /**
     * Non-null while a share from another app is waiting for a destination. The picker screen
     * consumes the payload, which empties the store and so closes this trigger behind it.
     */
    val shareWaiting: StateFlow<SharePayload?> = pendingShare.pending
    val lockState: StateFlow<LockState> = appLock.state

    /**
     * Drives `FLAG_SECURE` on the activity window; starts secure until the store answers.
     *
     * Either the user asked for screen security, or they set an app lock — which is the same ask
     * about a different surface. Keyed on the lock being *configured*, not on it being engaged:
     * the lock arms when the app comes back, and the recents thumbnail was taken when it left.
     */
    val screenSecurityEnabled: StateFlow<Boolean> =
        combine(privacyPreferences.screenSecurityEnabled, privacyPreferences.appLockEnabled) { secure, locked ->
            secure || locked
        }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * True until the user has been told why this app needs notification permission.
     *
     * Starts false so nothing flashes before the store answers; the effect that reads it also
     * checks whether the permission is already granted, so an install that allowed it never sees
     * the explanation at all.
     */
    val notificationRationalePending: StateFlow<Boolean> = privacyPreferences.notificationRationaleShown
        .map { shown -> !shown }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), false)

    fun onNotificationRationaleAcknowledged() {
        viewModelScope.launch { privacyPreferences.setNotificationRationaleShown(true) }
    }

    val darkTheme: StateFlow<Boolean?> = themePreferences.themeChoice()
        .map { it.dark }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    private val _startRoute = MutableStateFlow<VmRoute?>(null)

    /**
     * Where the navigation graph starts, or `null` while it is still being
     * decided. The splash is held while this is null *and* the app is unlocked — MainActivity
     * explains the other half: under a strict lock this never resolves, so holding the splash on
     * null alone would hide the lock screen behind a blank window for good. Otherwise held as
     * long as
     * this is `null`, which replaces the old in-app splash and its fixed delay:
     * the first frame the user sees is already the right destination.
     */
    val startRoute: StateFlow<VmRoute?> = _startRoute.asStateFlow()

    private val _pendingConversationId = MutableStateFlow<String?>(null)

    /** Conversation a tapped message notification asked for; consumed once by the NavHost. */
    val pendingConversationId: StateFlow<String?> = _pendingConversationId.asStateFlow()

    init {
        viewModelScope.launch {
            // The lock goes up first. Under strict mode the database cannot be opened until the
            // user authenticates, so resolving the start destination — which asks whether an
            // identity exists — has to wait for it rather than the other way round.
            appLock.lockIfUndetermined()
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
        //
        // Guarded, and it was the one initialize() caller that was not. Every other one — the
        // application warm-up, the boot receiver — treats a refusal as "stay down"; this one let
        // it out into viewModelScope, where nothing catches it. Deferring instead means the next
        // unlock tries again, which is the right answer for a key that is temporarily unavailable
        // and no worse than a crash for one that is not.
        val route = withContext(Dispatchers.IO) {
            // Not throwing is not the same as being ready: initialize() returns early for a locked
            // provider, because loading is exactly what it must not do. That is the mistake the
            // boot receiver made, found two rounds ago, and this call had the same shape.
            runCatching {
                databaseKeyProvider.initialize()
                // Not throwing is not the same as being ready: initialize() returns early for a
                // locked provider, because loading is exactly what it must not do.
                check(!databaseKeyProvider.isLocked) { "the app lock holds the database" }
                startDestination()
            }
                .onFailure { AppLogger.warn(TAG, "start destination deferred: ${it.message}") }
                .getOrNull()
        }
        // Resolved inside the same guarded block, not after it. Provider.get() builds the identity
        // repository and with it the database, so it is the call that throws — doing it out here,
        // on the main thread, put the one throw this function exists to avoid outside the
        // runCatching that was written for it, in a scope with no handler.
        _startRoute.value = route ?: return
    }

    /**
     * Home once an identity exists. Otherwise the node question comes first and only once: a node
     * is what makes communication possible, so it is asked before the identity rather than left to
     * be discovered later in Settings. An install that has already answered goes straight on.
     */
    private suspend fun startDestination(): VmRoute = when {
        hasIdentity.get()() -> VmRoute.Home
        nodeSetupPreferences.current() == NodeSetupChoice.NotAsked -> VmRoute.NodeSetup
        else -> VmRoute.Onboarding
    }

    /**
     * Forwarded to the coordinator, which owns both the clock and the scope.
     *
     * [elapsedRealtimeMs] is monotonic, deliberately: a timeout measured against the wall clock
     * could be defeated by changing the device date.
     */
    fun onBackgrounded(elapsedRealtimeMs: Long) {
        appLock.onBackgrounded(elapsedRealtimeMs)
    }

    fun onForegrounded(elapsedRealtimeMs: Long) {
        viewModelScope.launch { appLock.onForegrounded(elapsedRealtimeMs) }
    }

    private companion object {
        const val TAG = "AppLock"
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
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

    /** Called from `onCreate`/`onNewIntent` with whatever another app shared into us. */
    fun onShared(payload: SharePayload) {
        pendingShare.set(payload)
    }
}
