package ir.vmessenger.data.discovery

import ir.vmessenger.core.datastore.DiscoveryPreferences
import ir.vmessenger.network.discovery.PublishSequenceStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStorePublishSequenceStore @Inject constructor(
    private val discoveryPreferences: DiscoveryPreferences,
) : PublishSequenceStore {
    override suspend fun nextSequence(identityHash: ByteArray): Long =
        discoveryPreferences.nextPublishSequence(identityHash)

    override suspend fun bumpToAtLeast(identityHash: ByteArray, minimum: Long): Long =
        discoveryPreferences.bumpPublishSequence(identityHash, minimum)
}
