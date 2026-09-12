package ir.vmessenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.domain.usecase.identity.HasIdentityUseCase
import ir.vmessenger.navigation.VmRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    themePreferences: ThemePreferences,
    privacyPreferences: PrivacyPreferences,
    private val hasIdentity: HasIdentityUseCase,
    private val databaseKeyProvider: DatabaseKeyProvider,
) : ViewModel() {
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

    init {
        viewModelScope.launch {
            // The application starts this off the main thread; the splash must
            // wait for it (the call is idempotent) because the identity lookup
            // opens the encrypted database.
            withContext(Dispatchers.IO) { databaseKeyProvider.initialize() }
            _startRoute.value = if (hasIdentity()) VmRoute.Home else VmRoute.Onboarding
        }
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
