package ir.vmessenger.core.common.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The transport path used to reach a peer. Surfaced in debug tooling so every
 * phase of the P2P migration can be observed.
 */
enum class NetworkPath {
    DIRECT,
    UDP_ATTEMPT,
    CACHED_PEER,
    COMMUNITY_NODE,
    USER_RELAY,
    DEFAULT_RELAY,
    STORE_AND_FORWARD,
    UNKNOWN,
}

/** Where an endpoint candidate came from during resolution. */
enum class EndpointSource {
    CACHE,
    DHT,
    PEER_EXCHANGE,
    DEFAULT_FALLBACK,
    DIRECT_PUBLISH,
    UNKNOWN,
}

enum class RelayPeerPolicy {
    OFF,
    CONTACTS_ONLY,
    WIFI_ONLY,
    CHARGING_ONLY,
}

data class NetworkPathEvent(
    val path: NetworkPath,
    val detail: String,
    val atUnixMs: Long,
)

data class EndpointAttempt(
    val endpoint: String,
    val source: EndpointSource,
    val success: Boolean,
    val failureReason: String? = null,
    val atUnixMs: Long,
)

data class NetworkDiagnosticsSnapshot(
    val activeRelayUrl: String? = null,
    val activeBootstrapUrl: String? = null,
    val dhtParticipating: Boolean = false,
    val relayPeerActive: Boolean = false,
    val relayPeerPolicy: RelayPeerPolicy = RelayPeerPolicy.OFF,
    val activeRelayCircuits: Int = 0,
    val relayBytesForwarded: Long = 0,
    val mailboxPendingCount: Int = 0,
    val dhtStoredRecords: Int = 0,
    val dhtServedLookups: Long = 0,
    val dhtRejectedRecords: Long = 0,
)

/**
 * Process-wide diagnostics for P2P migration testing.
 */
object NetworkPathTracker {
    private const val MAX_EVENTS = 20
    private const val MAX_ATTEMPTS = 50

    private val _events = MutableStateFlow<List<NetworkPathEvent>>(emptyList())
    val events: StateFlow<List<NetworkPathEvent>> = _events

    private val _lastPath = MutableStateFlow<NetworkPathEvent?>(null)
    val lastPath: StateFlow<NetworkPathEvent?> = _lastPath

    private val _attempts = MutableStateFlow<List<EndpointAttempt>>(emptyList())
    val attempts: StateFlow<List<EndpointAttempt>> = _attempts

    private val _snapshot = MutableStateFlow(NetworkDiagnosticsSnapshot())
    val snapshot: StateFlow<NetworkDiagnosticsSnapshot> = _snapshot

    fun record(path: NetworkPath, detail: String, atUnixMs: Long = System.currentTimeMillis()) {
        val event = NetworkPathEvent(path = path, detail = detail, atUnixMs = atUnixMs)
        _lastPath.value = event
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
    }

    fun recordAttempt(
        endpoint: String,
        source: EndpointSource,
        success: Boolean,
        failureReason: String? = null,
        atUnixMs: Long = System.currentTimeMillis(),
    ) {
        val attempt = EndpointAttempt(
            endpoint = endpoint,
            source = source,
            success = success,
            failureReason = failureReason,
            atUnixMs = atUnixMs,
        )
        _attempts.update { current -> (listOf(attempt) + current).take(MAX_ATTEMPTS) }
    }

    fun updateSnapshot(transform: (NetworkDiagnosticsSnapshot) -> NetworkDiagnosticsSnapshot) {
        _snapshot.update(transform)
    }

    fun setActiveRelay(url: String?) {
        _snapshot.update { it.copy(activeRelayUrl = url) }
    }

    fun setActiveBootstrap(url: String?) {
        _snapshot.update { it.copy(activeBootstrapUrl = url) }
    }

    fun setDhtParticipating(active: Boolean) {
        _snapshot.update { it.copy(dhtParticipating = active) }
    }

    fun setRelayPeerStatus(active: Boolean, policy: RelayPeerPolicy) {
        _snapshot.update { it.copy(relayPeerActive = active, relayPeerPolicy = policy) }
    }

    fun setRelayCircuitStats(activeCircuits: Int, bytesForwarded: Long) {
        _snapshot.update {
            it.copy(activeRelayCircuits = activeCircuits, relayBytesForwarded = bytesForwarded)
        }
    }

    fun setMailboxPendingCount(count: Int) {
        _snapshot.update { it.copy(mailboxPendingCount = count) }
    }

    fun setDhtCounters(stored: Int, servedLookups: Long, rejected: Long) {
        _snapshot.update {
            it.copy(
                dhtStoredRecords = stored,
                dhtServedLookups = servedLookups,
                dhtRejectedRecords = rejected,
            )
        }
    }

    fun snapshotSummary(): String {
        val snap = _snapshot.value
        val parts = buildList {
            snap.activeRelayUrl?.let { add("relay=$it") }
            snap.activeBootstrapUrl?.let { add("bootstrap=$it") }
            if (snap.dhtParticipating) add("dht=on")
            if (snap.relayPeerActive) add("relay-peer=${snap.relayPeerPolicy.name}")
            if (snap.mailboxPendingCount > 0) add("mailbox=${snap.mailboxPendingCount}")
            if (snap.activeRelayCircuits > 0) add("circuits=${snap.activeRelayCircuits}")
            val rate = attemptSuccessRate()
            if (rate != null) add("ok=$rate")
        }
        return parts.joinToString(" · ").ifBlank { "—" }
    }

    /** Recent endpoint attempt success rate (e.g. "73% 11/15"). */
    fun attemptSuccessRate(): String? {
        val list = _attempts.value
        if (list.isEmpty()) return null
        val ok = list.count { it.success }
        val pct = ok * 100 / list.size
        return "$pct% $ok/${list.size}"
    }

    fun clear() {
        _events.value = emptyList()
        _lastPath.value = null
        _attempts.value = emptyList()
        _snapshot.value = NetworkDiagnosticsSnapshot()
    }
}
