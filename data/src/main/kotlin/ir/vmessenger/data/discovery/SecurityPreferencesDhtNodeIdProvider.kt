package ir.vmessenger.data.discovery

import ir.vmessenger.core.datastore.SecurityPreferences
import ir.vmessenger.network.dht.DhtNodeIdProvider
import javax.inject.Inject
import javax.inject.Singleton

/** [DhtNodeIdProvider] over the persisted per-device id in [SecurityPreferences]. */
@Singleton
class SecurityPreferencesDhtNodeIdProvider @Inject constructor(
    private val securityPreferences: SecurityPreferences,
) : DhtNodeIdProvider {
    override suspend fun nodeId(): ByteArray = securityPreferences.getOrCreateDhtNodeId()
}
