package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.entity.EndpointCacheEntity
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.network.dht.EndpointRecordVerifier
import ir.vmessenger.network.dht.toEndpoints
import ir.vmessenger.network.discovery.PeerEndpointCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB-backed [PeerEndpointCache] (docs/P2P-Phases.md Phase 3).
 *
 * - Records are signature-verified before storing and again when read.
 * - Entries expire by the record's own TTL.
 * - A cached entry is never overwritten by an older/equal-sequence record, so a
 *   newer signed record always wins.
 */
@Singleton
class PeerEndpointCacheImpl @Inject constructor(
    private val endpointCacheDao: EndpointCacheDao,
    private val verifier: EndpointRecordVerifier,
) : PeerEndpointCache {

    override suspend fun store(record: EndpointRecord) {
        val now = System.currentTimeMillis()
        if (!verifier.verify(record, now)) {
            AppLogger.warn("PeerCache", "refused to cache unverifiable record")
            return
        }
        val hash = record.identityHash.toByteArray()
        val existing = endpointCacheDao.get(hash)
        if (existing != null && existing.expiresAtUnixMs > now && existing.sequence >= record.sequence) {
            // Do not let a cached entry override a newer or equal signed record.
            return
        }
        endpointCacheDao.upsert(
            EndpointCacheEntity(
                identityHash = hash,
                endpointsProto = record.toByteArray(),
                sequence = record.sequence,
                fetchedAtUnixMs = now,
                expiresAtUnixMs = record.publishedAtUnixMs + record.ttlMs,
            ),
        )
    }

    override suspend fun lookup(identityHash: ByteArray): List<Endpoint>? {
        val now = System.currentTimeMillis()
        endpointCacheDao.purgeExpired(now)
        val cached = endpointCacheDao.get(identityHash) ?: return null
        if (cached.expiresAtUnixMs <= now) return null
        val record = runCatching { EndpointRecord.parseFrom(cached.endpointsProto) }.getOrNull() ?: return null
        if (!verifier.verify(record, now)) return null
        return record.toEndpoints()
    }
}
