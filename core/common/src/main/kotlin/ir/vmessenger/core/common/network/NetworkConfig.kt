package ir.vmessenger.core.common.network

/**
 * Production relay/DHT endpoints. Override [bootstrapAddress] or [relayAddress] for local dev
 * (e.g. emulator bootstrap at `10.0.2.2:46555`).
 */
object NetworkConfig {
    const val RELAY_HOST = "relay.vmessenger.ir"

    const val DEFAULT_DHT_URL = "wss://$RELAY_HOST/dht"
    const val DEFAULT_RELAY_URL = "wss://$RELAY_HOST/relay"

    const val DEV_BOOTSTRAP_ADDRESS = "10.0.2.2:46555"

    /**
     * Operator trust anchor: Ed25519 public key (64 hex chars) whose `SignedNodeRecord`s are
     * imported as [NodeTrust.OFFICIAL] and enabled automatically. Records signed by any other
     * key are stored as community nodes (disabled). Generate the key pair and sign records with
     * `scripts/sign-node-record` (see the README there).
     *
     * PLACEHOLDER — set before the 1.0 release. While it is all zeros
     * [operatorEd25519PublicKey] returns null and no record can become OFFICIAL.
     */
    const val OPERATOR_ED25519_PUBLIC_KEY_HEX =
        "0000000000000000000000000000000000000000000000000000000000000000"

    private const val ED25519_PUBLIC_KEY_HEX_LENGTH = 64

    /** Decoded operator key, or null while the placeholder is in place or the constant is malformed. */
    fun operatorEd25519PublicKey(): ByteArray? {
        val hex = OPERATOR_ED25519_PUBLIC_KEY_HEX
        val wellFormed = hex.length == ED25519_PUBLIC_KEY_HEX_LENGTH && hex.all { it.isHexDigit() }
        if (!wellFormed || hex.all { it == '0' }) return null
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
        }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    @Volatile
    var bootstrapAddress: String = DEFAULT_DHT_URL

    @Volatile
    var relayAddress: String = DEFAULT_RELAY_URL

    /** Health-ranked relay URLs (updated by [RelayDirectory] at runtime). */
    @Volatile
    var rankedRelayUrls: List<String> = emptyList()

    @Volatile
    var useDevBootstrap: Boolean = false

    fun effectiveBootstrapAddress(): String =
        if (useDevBootstrap) DEV_BOOTSTRAP_ADDRESS else bootstrapAddress

    fun effectiveRelayEndpoint(): Endpoint =
        Endpoint(transport = TransportIds.RELAY, address = relayAddress)

    /**
     * Our own enabled relays, to try a peer through when its record gives nothing better. Empty when
     * none is enabled: the built-in relay is only a fallback in the legacy single-node mode.
     */
    fun relayFallbackEndpoints(): List<Endpoint> {
        val urls = buildList {
            add(relayAddress)
            addAll(rankedRelayUrls)
            if (all { it.isBlank() } && !P2PConfig.multiNodeEnabled) add(DEFAULT_RELAY_URL)
        }.filter { it.isNotBlank() }.distinct()
        return urls.map { Endpoint(transport = TransportIds.RELAY, address = it) }
    }
}
