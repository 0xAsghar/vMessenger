package ir.vmessenger.network.dht

/**
 * Supplies this device's persisted random DHT node id (32 bytes). Bound by the
 * data layer over `SecurityPreferences.getOrCreateDhtNodeId()` so every install
 * has its own id instead of one shared, hard-coded value.
 */
fun interface DhtNodeIdProvider {
    suspend fun nodeId(): ByteArray
}
