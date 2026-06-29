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

    fun relayFallbackEndpoints(): List<Endpoint> {
        val urls = buildList {
            add(relayAddress)
            addAll(rankedRelayUrls)
            if (isEmpty()) add(DEFAULT_RELAY_URL)
        }.distinct()
        return urls.map { Endpoint(transport = TransportIds.RELAY, address = it) }
    }
}
