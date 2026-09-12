package ir.vmessenger.network.dht

/** Gate for embedded DHT participation (battery/network/feature flag); see [EmbeddedDhtPolicy]. */
interface DhtParticipationPolicy {
    fun shouldParticipate(): Boolean

    fun shouldAdvertise(host: String): Boolean
}
