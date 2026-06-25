package ir.vmessenger.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val bootstrapped: Boolean = false,
    val knownNodes: Int = 0,
    val publishedEndpoint: String? = null,
    val lastError: String? = null,
    val listenPort: Int = 48_555,
    val forwardPort: Int = 48_555,
    val devMode: Boolean = false,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
    private val joinNetworkUseCase: JoinNetworkUseCase,
    private val publishNetworkEndpointsUseCase: PublishNetworkEndpointsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            discoveryRepository.observeStatus().collect { status ->
                _uiState.update {
                    it.copy(
                        bootstrapped = status.bootstrapped,
                        knownNodes = status.knownNodes,
                        publishedEndpoint = status.publishedEndpoint,
                        lastError = status.lastError,
                    )
                }
            }
        }
    }

    fun setDevMode(enabled: Boolean) {
        _uiState.update { it.copy(devMode = enabled) }
        AppLogger.info("Debug", "devMode=$enabled")
    }

    fun joinAndPublish() {
        viewModelScope.launch {
            val devMode = _uiState.value.devMode
            AppLogger.info("Debug", "joinAndPublish started (devMode=$devMode)")
            when (val join = joinNetworkUseCase()) {
                is AppResult.Success -> AppLogger.info("Debug", "join network success")
                is AppResult.Error -> {
                    AppLogger.error("Debug", "join network failed: ${join.error.message}")
                    return@launch
                }
            }
            val publish = if (devMode) {
                NetworkConfig.useDevBootstrap = true
                val port = _uiState.value.forwardPort
                AppLogger.info("Debug", "publishing dev endpoints 10.0.2.2:$port")
                publishNetworkEndpointsUseCase(directHost = "10.0.2.2", directPort = port)
            } else {
                NetworkConfig.useDevBootstrap = false
                AppLogger.info("Debug", "publishing production relay endpoint")
                publishNetworkEndpointsUseCase()
            }
            when (publish) {
                is AppResult.Success -> AppLogger.info("Debug", "publish success")
                is AppResult.Error -> AppLogger.error("Debug", "publish failed: ${publish.error.message}")
            }
        }
    }
}
