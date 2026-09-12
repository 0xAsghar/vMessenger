package ir.vmessenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    themePreferences: ThemePreferences,
    privacyPreferences: PrivacyPreferences,
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
}
