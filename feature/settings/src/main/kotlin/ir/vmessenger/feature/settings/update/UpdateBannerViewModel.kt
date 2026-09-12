package ir.vmessenger.feature.settings.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.usecase.update.ObserveUpdateStatusUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * "Version x is available" above the tabs.
 *
 * It reads stored state only — the check that fills it in happens elsewhere — so the home
 * screen never waits on the network. "Later" hides it for this session rather than for good:
 * permanently dismissing a version is what the updater screen's "skip" is for, and a banner
 * that could be dismissed forever by accident would be worse than one that comes back.
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    observeStatus: ObserveUpdateStatusUseCase,
) : ViewModel() {
    private val dismissed = MutableStateFlow(false)

    val availableVersion: StateFlow<String?> =
        combine(observeStatus(), dismissed) { status, hidden ->
            status.availableVersion.takeUnless { hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    fun dismiss() {
        dismissed.value = true
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
