package ir.vmessenger.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishEndpointUseCase
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
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
    private val joinNetworkUseCase: JoinNetworkUseCase,
    private val publishEndpointUseCase: PublishEndpointUseCase,
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

    fun joinAndPublish() {
        viewModelScope.launch {
            when (joinNetworkUseCase()) {
                is AppResult.Success -> Unit
                is AppResult.Error -> return@launch
            }
            val port = _uiState.value.forwardPort
            publishEndpointUseCase(host = "10.0.2.2", port = port)
        }
    }
}
