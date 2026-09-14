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

/**
 * The one reachability fault worth putting in front of the user.
 *
 * Each of these leaves the app looking perfectly healthy while nothing can reach
 * it, and the transport cannot resolve any of them on its own: they end when a
 * person changes something. Everything else — an outage, a dead relay, no
 * network — is the retry loop's business and never appears here.
 */
enum class ListenerAlert {
    NONE,

    /**
     * TLS chain/validity failure. The device clock sits outside the server
     * certificate's validity window, i.e. the date is wrong by a lot.
     */
    CLOCK_CERTIFICATE,

    /**
     * The relay refused the listener proof as stale. The clock is off by more
     * than the relay's skew window — minutes, not months — which also stops this
     * device's discovery records from being accepted.
     */
    CLOCK_SKEW,

    /**
     * The relay handed this identity's listener slot to another device. Relays
     * key listeners by identity, so a backup restored elsewhere evicts this one.
     */
    IDENTITY_ELSEWHERE,
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

    private val _listenerAlert = MutableStateFlow(ListenerAlert.NONE)

    /**
     * The reachability fault the UI should be showing right now, or
     * [ListenerAlert.NONE]. See [ListenerAlert] for why only these three qualify.
     */
    val listenerAlert: StateFlow<ListenerAlert> = _listenerAlert

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

    /**
     * Report a failed connection attempt. If the failure is a TLS certificate
     * chain/validity error, raise [ListenerAlert.CLOCK_CERTIFICATE] so the UI can
     * hint that the device date & time may be wrong. Non-certificate failures
     * (plain offline, timeouts, resets) are ignored so an ordinary outage never
     * shows the hint.
     */
    fun reportConnectionError(error: Throwable?) {
        if (isCertValidityError(error)) {
            raise(ListenerAlert.CLOCK_CERTIFICATE)
        }
    }

    /**
     * A completed TLS handshake proves the certificate validated, so the date is
     * at least roughly right. It says nothing about the other two alerts: a clock
     * minutes out still gives a valid certificate, and a stolen listener slot does
     * not stop this device from dialling anywhere.
     */
    fun reportConnectionSuccess() {
        if (_listenerAlert.value == ListenerAlert.CLOCK_CERTIFICATE) {
            _listenerAlert.value = ListenerAlert.NONE
        }
    }

    /**
     * The relay held our listener registration through a whole keepalive interval.
     * Silence is the only acknowledgement it gives a listener, and it settles all
     * three at once: the proof was fresh enough, the certificate validated, and no
     * other device has taken the slot.
     */
    fun reportListenerAccepted() {
        if (_listenerAlert.value != ListenerAlert.NONE) {
            _listenerAlert.value = ListenerAlert.NONE
        }
    }

    /**
     * The relay turned the listener hello away, saying [reason] in its own words.
     * Only a stale proof is the user's to fix; every other rejection is the retry
     * loop's business.
     */
    fun reportListenerRejected(reason: String?) {
        if (RelayRejection.isStaleProof(reason)) {
            raise(ListenerAlert.CLOCK_SKEW)
        }
    }

    /** Another device installed a listener for this identity and took the slot. */
    fun reportListenerReplaced() {
        _listenerAlert.value = ListenerAlert.IDENTITY_ELSEWHERE
    }

    /**
     * [ListenerAlert.IDENTITY_ELSEWHERE] outranks the clock hints: while a second
     * device owns the slot, everything else that fails here is a symptom of that,
     * and sending the user off to check their clock would waste their time.
     */
    private fun raise(alert: ListenerAlert) {
        if (_listenerAlert.value != ListenerAlert.IDENTITY_ELSEWHERE) {
            _listenerAlert.value = alert
        }
    }

    fun clear() {
        _events.value = emptyList()
        _lastPath.value = null
        _attempts.value = emptyList()
        _snapshot.value = NetworkDiagnosticsSnapshot()
        _listenerAlert.value = ListenerAlert.NONE
    }

    /**
     * True when a throwable (or anything in its cause chain) is an X.509
     * certificate path/validity failure — e.g. Conscrypt's "Chain validation
     * failed", CertPathValidatorException, or a not-yet-valid/expired cert. We
     * deliberately do NOT treat a bare SSLHandshakeException as one, since those
     * also fire on protocol errors and mid-handshake resets that are unrelated
     * to the clock.
     */
    private fun isCertValidityError(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (matchesCertMarker(cause)) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun matchesCertMarker(error: Throwable): Boolean {
        val className = error.javaClass.name
        val message = error.message?.lowercase().orEmpty()
        return CERT_EXCEPTION_MARKERS.any { className.contains(it) } ||
            CERT_MESSAGE_MARKERS.any { message.contains(it) }
    }

    private const val MAX_CAUSE_DEPTH = 8

    private val CERT_EXCEPTION_MARKERS = listOf(
        "CertPathValidator",
        "CertificateExpired",
        "CertificateNotYetValid",
        "CertificateException",
    )

    private val CERT_MESSAGE_MARKERS = listOf(
        "chain validation failed",
        "trust anchor",
        "certificate expired",
        "certificate has expired",
        "not yet valid",
        "certpath",
        "certificate is not valid",
        "timestamp check failed",
    )
}
