package ir.vmessenger.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.database.DatabaseSchema
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.domain.model.NetworkNode
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.usecase.nodes.ObserveNetworkNodesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feedback shown under the version row while the developer-mode tap sequence runs. */
sealed interface DeveloperModeStatus {
    data object Idle : DeveloperModeStatus

    /** [remaining] more taps toggle developer mode. */
    data class Countdown(val remaining: Int) : DeveloperModeStatus

    data object Enabled : DeveloperModeStatus

    data object Disabled : DeveloperModeStatus
}

/** The nodes this build is dialling right now, split the way the About page lists them. */
data class AboutNodes(
    val bootstrap: List<NetworkNode> = emptyList(),
    val relay: List<NetworkNode> = emptyList(),
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    appBuildInfo: AppBuildInfo,
    observeNetworkNodes: ObserveNetworkNodesUseCase,
    private val privacyPreferences: PrivacyPreferences,
) : ViewModel() {
    /** `۱٫۰٫۰` and `۴۵` — the real running build, in Persian digits. */
    val versionName: String = VmTextFormat.persianDigits(appBuildInfo.versionName)
    val versionCode: String = VmTextFormat.persianDigits(appBuildInfo.versionCode.toString())

    /** `۲.۰`. Only the major gates compatibility; the minor is shown because it dates the build. */
    val protocolVersion: String =
        VmTextFormat.persianDigits("${ProtocolVersion.MAJOR}.${ProtocolVersion.MINOR}")

    /** Room's schema version, read from `:core:common` so the screen cannot drift from the database. */
    val databaseVersion: String = VmTextFormat.persianDigits(DatabaseSchema.VERSION.toString())

    /**
     * Enabled nodes only. The page answers "what is this build talking to", not "what could it
     * talk to" — the Nodes screen owns the full list and every switch on it.
     */
    val nodes: StateFlow<AboutNodes> = observeNetworkNodes()
        .map { all ->
            val active = all.filter { it.enabled }
            AboutNodes(
                bootstrap = active.filter { it.role == NetworkNodeRole.BOOTSTRAP },
                relay = active.filter { it.role == NetworkNodeRole.RELAY },
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AboutNodes())

    private val _developerModeStatus = MutableStateFlow<DeveloperModeStatus>(DeveloperModeStatus.Idle)
    val developerModeStatus: StateFlow<DeveloperModeStatus> = _developerModeStatus.asStateFlow()

    private var taps = 0

    /**
     * Counts a tap on the version row. Seven of them toggle developer mode, which
     * is what makes the Debug and Logs screens reachable in a release build; the
     * countdown only appears once the user is clearly doing it on purpose.
     */
    fun onVersionTapped() {
        taps += 1
        val remaining = TAPS_TO_TOGGLE - taps
        when {
            remaining <= 0 -> {
                taps = 0
                toggleDeveloperMode()
            }
            remaining <= COUNTDOWN_FROM_REMAINING -> {
                _developerModeStatus.value = DeveloperModeStatus.Countdown(remaining)
            }
            else -> _developerModeStatus.value = DeveloperModeStatus.Idle
        }
    }

    private fun toggleDeveloperMode() {
        viewModelScope.launch {
            val enabled = !privacyPreferences.developerModeEnabled.first()
            privacyPreferences.setDeveloperModeEnabled(enabled)
            _developerModeStatus.value = if (enabled) {
                DeveloperModeStatus.Enabled
            } else {
                DeveloperModeStatus.Disabled
            }
        }
    }

    private companion object {
        const val TAPS_TO_TOGGLE = 7

        /** The countdown becomes visible on the fourth tap (three left of seven). */
        const val COUNTDOWN_FROM_REMAINING = 3
    }
}
