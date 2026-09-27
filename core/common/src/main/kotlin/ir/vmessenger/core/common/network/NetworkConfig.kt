package ir.vmessenger.core.common.network

/**
 * Runtime network settings. The app ships no node of its own: every bootstrap and relay node is one
 * the person added (or set up with New node), stored in the database. Until 2.2.2 a built-in test
 * node was compiled in here; it is gone, and an upgrade deletes its rows (schema 26).
 *
 * [DEV_BOOTSTRAP_ADDRESS] is the one address left: a debug build's developer bootstrap on the
 * machine running the emulator (`:node:run --args="--tcp"`).
 */
object NetworkConfig {
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

    /** The relay this device listens on now, or blank while none is enabled ([RelayDirectory] sets it). */
    @Volatile
    var relayAddress: String = ""

    /** Health-ranked relay URLs (updated by [RelayDirectory] at runtime). */
    @Volatile
    var rankedRelayUrls: List<String> = emptyList()

    @Volatile
    var useDevBootstrap: Boolean = false

    /** The developer bootstrap while a debug build asks for it, otherwise null. */
    fun devBootstrapAddress(): String? = DEV_BOOTSTRAP_ADDRESS.takeIf { useDevBootstrap }

    /**
     * Our own enabled relays, to try a peer through when its record gives nothing better. Empty when
     * none is enabled: there is no built-in relay to fall back on.
     */
    fun relayFallbackEndpoints(): List<Endpoint> =
        (listOf(relayAddress) + rankedRelayUrls)
            .filter { it.isNotBlank() }
            .distinct()
            .map { Endpoint(transport = TransportIds.RELAY, address = it) }
}
