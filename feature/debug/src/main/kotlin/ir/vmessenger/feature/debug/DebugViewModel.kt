package ir.vmessenger.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
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
    val lastPath: String? = null,
    val recentPaths: List<String> = emptyList(),
    val flags: P2PFlagsUiState = P2PFlagsUiState(),
)

data class P2PFlagsUiState(
    val multiNode: Boolean = P2PConfig.multiNodeEnabled,
    val peerCache: Boolean = P2PConfig.peerCacheEnabled,
    val peerExchange: Boolean = P2PConfig.peerExchangeEnabled,
    val dhtParticipation: Boolean = P2PConfig.dhtParticipationEnabled,
    val relayPeerMode: Boolean = P2PConfig.relayPeerModeEnabled,
    val natTraversal: Boolean = P2PConfig.natTraversalEnabled,
    val storeAndForward: Boolean = P2PConfig.storeAndForwardEnabled,
    val reduceDefaultRelay: Boolean = P2PConfig.reduceDefaultRelayEnabled,
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
        viewModelScope.launch {
            NetworkPathTracker.events.collect { events ->
                _uiState.update { state ->
                    state.copy(
                        lastPath = events.firstOrNull()?.let { "${it.path.name}  (${it.detail})" },
                        recentPaths = events.map { "${it.path.name} · ${it.detail}" },
                    )
                }
            }
        }
    }

    fun setDevMode(enabled: Boolean) {
        _uiState.update { it.copy(devMode = enabled) }
        AppLogger.info("Debug", "devMode=$enabled")
    }

    fun setFlag(flag: P2PFlag, enabled: Boolean) {
        when (flag) {
            P2PFlag.MULTI_NODE -> P2PConfig.multiNodeEnabled = enabled
            P2PFlag.PEER_CACHE -> P2PConfig.peerCacheEnabled = enabled
            P2PFlag.PEER_EXCHANGE -> P2PConfig.peerExchangeEnabled = enabled
            P2PFlag.DHT_PARTICIPATION -> P2PConfig.dhtParticipationEnabled = enabled
            P2PFlag.RELAY_PEER_MODE -> P2PConfig.relayPeerModeEnabled = enabled
            P2PFlag.NAT_TRAVERSAL -> P2PConfig.natTraversalEnabled = enabled
            P2PFlag.STORE_AND_FORWARD -> P2PConfig.storeAndForwardEnabled = enabled
            P2PFlag.REDUCE_DEFAULT_RELAY -> P2PConfig.reduceDefaultRelayEnabled = enabled
        }
        AppLogger.info("Debug", "p2p flag ${flag.name}=$enabled")
        _uiState.update { it.copy(flags = currentFlags()) }
    }

    private fun currentFlags() = P2PFlagsUiState(
        multiNode = P2PConfig.multiNodeEnabled,
        peerCache = P2PConfig.peerCacheEnabled,
        peerExchange = P2PConfig.peerExchangeEnabled,
        dhtParticipation = P2PConfig.dhtParticipationEnabled,
        relayPeerMode = P2PConfig.relayPeerModeEnabled,
        natTraversal = P2PConfig.natTraversalEnabled,
        storeAndForward = P2PConfig.storeAndForwardEnabled,
        reduceDefaultRelay = P2PConfig.reduceDefaultRelayEnabled,
    )

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

enum class P2PFlag {
    MULTI_NODE,
    PEER_CACHE,
    PEER_EXCHANGE,
    DHT_PARTICIPATION,
    RELAY_PEER_MODE,
    NAT_TRAVERSAL,
    STORE_AND_FORWARD,
    REDUCE_DEFAULT_RELAY,
}
