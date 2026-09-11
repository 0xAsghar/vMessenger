package ir.vmessenger.node

/**
 * Runtime configuration of a relay/DHT node, read from environment variables
 * (see `deploy/systemd/vmessenger-node.service.template` and
 * `scripts/setup-node.sh` for the variables an operator sets).
 *
 * Every numeric variable falls back to its default when unset or unparsable so
 * a typo in `node.env` never keeps the node from starting.
 */
@Suppress("LongParameterList") // Flat, immutable config: one property per tunable is the clearest shape.
data class NodeConfig(
    val port: Int = DEFAULT_PORT,
    val publicHost: String = DEFAULT_PUBLIC_HOST,
    val advertisedDhtUrl: String = "wss://$publicHost/dht",
    val peerNodes: List<String> = emptyList(),
    val stateDir: String = DEFAULT_STATE_DIR,
    val trustProxy: Boolean = false,
    val maxListeners: Int = DEFAULT_MAX_LISTENERS,
    val maxListenersPerIp: Int = DEFAULT_MAX_LISTENERS_PER_IP,
    val maxPendingDialers: Int = DEFAULT_MAX_PENDING_DIALERS,
    val maxPendingPerListener: Int = DEFAULT_MAX_PENDING_PER_LISTENER,
    val pendingDialerTtlMs: Long = DEFAULT_PENDING_DIALER_TTL_MS,
    val proofMaxSkewMs: Long = DEFAULT_PROOF_MAX_SKEW_MS,
    val maxRecords: Int = DEFAULT_MAX_RECORDS,
    val maxRecordTtlMs: Long = DEFAULT_MAX_RECORD_TTL_MS,
    val maxCircuits: Int = DEFAULT_MAX_CIRCUITS,
    val circuitIdleTimeoutMs: Long = DEFAULT_CIRCUIT_IDLE_TIMEOUT_MS,
    val dialRatePerMin: Int = DEFAULT_DIAL_RATE_PER_MIN,
    val dialBurst: Int = DEFAULT_DIAL_BURST,
    val storeRatePerMin: Int = DEFAULT_STORE_RATE_PER_MIN,
    val storeBurst: Int = DEFAULT_STORE_BURST,
    val wsMaxFrameBytes: Long = DEFAULT_WS_MAX_FRAME_BYTES,
    val wsPingPeriodMs: Long = DEFAULT_WS_PING_PERIOD_MS,
    val wsTimeoutMs: Long = DEFAULT_WS_TIMEOUT_MS,
) {

    /** One-line, secret-free summary for the startup log. */
    fun describe(): String = listOf(
        "port=$port",
        "publicHost=$publicHost",
        "advertisedDhtUrl=$advertisedDhtUrl",
        "peerNodes=${peerNodes.size}",
        "stateDir=$stateDir",
        "trustProxy=$trustProxy",
        "maxListeners=$maxListeners",
        "maxListenersPerIp=$maxListenersPerIp",
        "maxPendingDialers=$maxPendingDialers",
        "maxPendingPerListener=$maxPendingPerListener",
        "pendingDialerTtlMs=$pendingDialerTtlMs",
        "proofMaxSkewMs=$proofMaxSkewMs",
        "maxRecords=$maxRecords",
        "maxRecordTtlMs=$maxRecordTtlMs",
        "maxCircuits=$maxCircuits",
        "circuitIdleTimeoutMs=$circuitIdleTimeoutMs",
        "dialRatePerMin=$dialRatePerMin",
        "dialBurst=$dialBurst",
        "storeRatePerMin=$storeRatePerMin",
        "storeBurst=$storeBurst",
        "wsMaxFrameBytes=$wsMaxFrameBytes",
        "wsPingPeriodMs=$wsPingPeriodMs",
        "wsTimeoutMs=$wsTimeoutMs",
    ).joinToString(" ")

    companion object {
        const val DEFAULT_PORT = 8443
        const val DEFAULT_PUBLIC_HOST = "relay.vmessenger.ir"
        const val DEFAULT_STATE_DIR = "./state"
        const val DEFAULT_MAX_LISTENERS = 20_000
        const val DEFAULT_MAX_LISTENERS_PER_IP = 64
        const val DEFAULT_MAX_PENDING_DIALERS = 5_000
        const val DEFAULT_MAX_PENDING_PER_LISTENER = 8
        const val DEFAULT_PENDING_DIALER_TTL_MS = 30_000L
        const val DEFAULT_PROOF_MAX_SKEW_MS = 300_000L
        const val DEFAULT_MAX_RECORDS = 100_000
        const val DEFAULT_MAX_RECORD_TTL_MS = 86_400_000L
        const val DEFAULT_MAX_CIRCUITS = 10_000
        const val DEFAULT_CIRCUIT_IDLE_TIMEOUT_MS = 600_000L
        const val DEFAULT_DIAL_RATE_PER_MIN = 30
        const val DEFAULT_DIAL_BURST = 10
        const val DEFAULT_STORE_RATE_PER_MIN = 60
        const val DEFAULT_STORE_BURST = 20
        const val DEFAULT_WS_MAX_FRAME_BYTES = 1_048_576L
        const val DEFAULT_WS_PING_PERIOD_MS = 30_000L
        const val DEFAULT_WS_TIMEOUT_MS = 60_000L

        /**
         * Builds a config from [env] (defaults to the process environment).
         * Unset or malformed values fall back to the defaults above.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): NodeConfig {
            val publicHost = env.string("VMESSENGER_PUBLIC_HOST", DEFAULT_PUBLIC_HOST)
            return NodeConfig(
                port = env.int("VMESSENGER_NODE_PORT", DEFAULT_PORT),
                publicHost = publicHost,
                advertisedDhtUrl = env.string("VMESSENGER_ADVERTISED_DHT_URL", "wss://$publicHost/dht"),
                peerNodes = env.list("VMESSENGER_PEER_NODES"),
                stateDir = env.string("VMESSENGER_STATE_DIR", DEFAULT_STATE_DIR),
                trustProxy = env.flag("VMESSENGER_TRUST_PROXY"),
                maxListeners = env.int("VMESSENGER_MAX_LISTENERS", DEFAULT_MAX_LISTENERS),
                maxListenersPerIp = env.int("VMESSENGER_MAX_LISTENERS_PER_IP", DEFAULT_MAX_LISTENERS_PER_IP),
                maxPendingDialers = env.int("VMESSENGER_MAX_PENDING_DIALERS", DEFAULT_MAX_PENDING_DIALERS),
                maxPendingPerListener = env.int(
                    "VMESSENGER_MAX_PENDING_PER_LISTENER",
                    DEFAULT_MAX_PENDING_PER_LISTENER,
                ),
                pendingDialerTtlMs = env.long("VMESSENGER_PENDING_DIALER_TTL_MS", DEFAULT_PENDING_DIALER_TTL_MS),
                proofMaxSkewMs = env.long("VMESSENGER_PROOF_MAX_SKEW_MS", DEFAULT_PROOF_MAX_SKEW_MS),
                maxRecords = env.int("VMESSENGER_MAX_RECORDS", DEFAULT_MAX_RECORDS),
                maxRecordTtlMs = env.long("VMESSENGER_MAX_RECORD_TTL_MS", DEFAULT_MAX_RECORD_TTL_MS),
                maxCircuits = env.int("VMESSENGER_MAX_CIRCUITS", DEFAULT_MAX_CIRCUITS),
                circuitIdleTimeoutMs = env.long("VMESSENGER_CIRCUIT_IDLE_TIMEOUT_MS", DEFAULT_CIRCUIT_IDLE_TIMEOUT_MS),
                dialRatePerMin = env.int("VMESSENGER_DIAL_RATE_PER_MIN", DEFAULT_DIAL_RATE_PER_MIN),
                dialBurst = env.int("VMESSENGER_DIAL_BURST", DEFAULT_DIAL_BURST),
                storeRatePerMin = env.int("VMESSENGER_STORE_RATE_PER_MIN", DEFAULT_STORE_RATE_PER_MIN),
                storeBurst = env.int("VMESSENGER_STORE_BURST", DEFAULT_STORE_BURST),
                wsMaxFrameBytes = env.long("VMESSENGER_WS_MAX_FRAME_BYTES", DEFAULT_WS_MAX_FRAME_BYTES),
                wsPingPeriodMs = env.long("VMESSENGER_WS_PING_PERIOD_MS", DEFAULT_WS_PING_PERIOD_MS),
                wsTimeoutMs = env.long("VMESSENGER_WS_TIMEOUT_MS", DEFAULT_WS_TIMEOUT_MS),
            )
        }

        private fun Map<String, String>.raw(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, String>.string(name: String, default: String): String = raw(name) ?: default

        private fun Map<String, String>.int(name: String, default: Int): Int =
            raw(name)?.toIntOrNull()?.takeIf { it > 0 } ?: default

        private fun Map<String, String>.long(name: String, default: Long): Long =
            raw(name)?.toLongOrNull()?.takeIf { it > 0 } ?: default

        private fun Map<String, String>.flag(name: String): Boolean =
            raw(name)?.lowercase() in setOf("1", "true")

        private fun Map<String, String>.list(name: String): List<String> =
            raw(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
