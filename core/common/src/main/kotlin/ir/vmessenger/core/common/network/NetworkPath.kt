package ir.vmessenger.core.common.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The transport path used to reach a peer. Surfaced in debug tooling so every
 * phase of the P2P migration can be observed: which deliveries used direct
 * connectivity, the central relay, a cached peer, a community node, or a
 * user-operated relay.
 */
enum class NetworkPath {
    DIRECT,
    NAT_TRAVERSAL,
    CACHED_PEER,
    COMMUNITY_NODE,
    USER_RELAY,
    DEFAULT_RELAY,
    STORE_AND_FORWARD,
    UNKNOWN,
}

data class NetworkPathEvent(
    val path: NetworkPath,
    val detail: String,
    val atUnixMs: Long,
)

/**
 * Process-wide, observable record of the most recent delivery paths. Kept as a
 * plain object (mirroring [NetworkConfig]) so every module can report into it
 * without a Hilt dependency edge, while UI layers observe [events].
 */
object NetworkPathTracker {
    private const val MAX_EVENTS = 20

    private val _events = MutableStateFlow<List<NetworkPathEvent>>(emptyList())
    val events: StateFlow<List<NetworkPathEvent>> = _events

    private val _lastPath = MutableStateFlow<NetworkPathEvent?>(null)
    val lastPath: StateFlow<NetworkPathEvent?> = _lastPath

    fun record(path: NetworkPath, detail: String, atUnixMs: Long = System.currentTimeMillis()) {
        val event = NetworkPathEvent(path = path, detail = detail, atUnixMs = atUnixMs)
        _lastPath.value = event
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
    }

    fun clear() {
        _events.value = emptyList()
        _lastPath.value = null
    }
}
