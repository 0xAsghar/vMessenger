package ir.vmessenger.network.discovery

interface PublishSequenceStore {
    suspend fun nextSequence(identityHash: ByteArray): Long

    suspend fun bumpToAtLeast(identityHash: ByteArray, minimum: Long): Long
}
