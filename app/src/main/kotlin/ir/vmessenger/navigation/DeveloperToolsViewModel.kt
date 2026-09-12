package ir.vmessenger.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.datastore.PrivacyPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Gate for the Debug and Logs destinations. They are always available in a debug
 * build; in a release build they stay hidden until the user unlocks developer
 * mode with seven taps on the About version row.
 *
 * The value is null while the preference store is still being read, so the gate
 * can wait instead of bouncing the user back on a cold start.
 */
@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
    appBuildInfo: AppBuildInfo,
    privacyPreferences: PrivacyPreferences,
) : ViewModel() {
    val developerToolsEnabled: StateFlow<Boolean?> = privacyPreferences.developerModeEnabled
        .map { enabled -> appBuildInfo.isDebug || enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
