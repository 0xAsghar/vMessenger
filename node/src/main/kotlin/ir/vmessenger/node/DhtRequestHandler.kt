package ir.vmessenger.node

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.proto.dht.v1.DhtNodeInfo
import ir.vmessenger.core.proto.dht.v1.DhtRpcRequest
import ir.vmessenger.core.proto.dht.v1.DhtRpcResponse
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.core.proto.dht.v1.FindNodeResponse
import ir.vmessenger.core.proto.dht.v1.FindValueResponse
import ir.vmessenger.core.proto.dht.v1.PingResponse
import ir.vmessenger.core.proto.dht.v1.StoreResponse
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory DHT record store behind the `/dht` WebSocket and the legacy TCP node.
 *
 * Records are keyed by [IdentityHashMatcher.routingKeyHex] (the 16-byte identity
 * hash prefix) so lookups with a partial, User-Hash-derived key still resolve.
 * Stores are bounded by TTL, publish-time skew and a total record cap; expired
 * records are dropped lazily on lookup and eagerly by [sweepExpired].
 */
class DhtRequestHandler
@Suppress("LongParameterList") // Mirrors the node's DHT limits one-to-one; all but two have defaults.
constructor(
    private val nodeId: ByteArray,
    private val advertisedAddress: String,
    private val peerNodes: List<String> = emptyList(),
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val maxRecordTtlMs: Long = DEFAULT_MAX_RECORD_TTL_MS,
    private val maxFutureSkewMs: Long = DEFAULT_MAX_FUTURE_SKEW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stats: DhtCounters? = null,
    private val verifier: NodeEndpointRecordVerifier = NodeEndpointRecordVerifier(),
) {
    /**
     * Compatibility constructor for the pre-config call sites: derives the legacy
     * fixed node id and advertises `host:port` exactly as the old node did.
     */
    constructor(port: Int, publicHost: String) : this(
        nodeId = legacyNodeId(),
        advertisedAddress = "$publicHost:$port",
    )

    private val log = LoggerFactory.getLogger(DhtRequestHandler::class.java)
    private val records = ConcurrentHashMap<String, EndpointRecord>()
    private val storeLock = Any()

    fun handle(request: DhtRpcRequest): DhtRpcResponse {
        val builder = DhtRpcResponse.newBuilder()
        when {
            request.hasPing() -> builder.setPing(
                PingResponse.newBuilder()
                    .setNodeId(ByteString.copyFrom(nodeId)),
            )
            request.hasFindNode() -> builder.setFindNode(
                FindNodeResponse.newBuilder()
                    .addAllNodes(knownNodes()),
            )
            request.hasStore() -> builder.setStore(
                StoreResponse.newBuilder().setAccepted(acceptStore(request.store.record)),
            )
            request.hasFindValue() -> builder.setFindValue(findValue(request.findValue.key.toByteArray()))
        }
        return builder.build()
    }

    /** Number of records currently held (expired-but-unswept records included). */
    fun recordCount(): Int = records.size

    /** Drops every record whose TTL has elapsed at [now]; returns how many were removed. */
    fun sweepExpired(now: Long = clock()): Int {
        var removed = 0
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value, now)) {
                iterator.remove()
                removed++
            }
        }
        stats?.setRecordCount(records.size)
        if (removed > 0) {
            log.info("dht sweep removed={} remaining={}", removed, records.size)
        }
        return removed
    }

    private fun findValue(key: ByteArray): FindValueResponse.Builder {
        val record = records[IdentityHashMatcher.routingKeyHex(key)]
        return if (record != null && !isExpired(record, clock())) {
            FindValueResponse.newBuilder()
                .setFound(true)
                .setRecord(record)
        } else {
            FindValueResponse.newBuilder()
                .setFound(false)
                .addAllNodes(knownNodes())
        }
    }

    private fun knownNodes(): List<DhtNodeInfo> =
        listOf(nodeInfo(nodeId, advertisedAddress)) +
            peerNodes.map { address -> nodeInfo(sha256(address.toByteArray(Charsets.UTF_8)), address) }

    private fun nodeInfo(id: ByteArray, address: String): DhtNodeInfo =
        DhtNodeInfo.newBuilder()
            .setNodeId(ByteString.copyFrom(id))
            .setAddress(address)
            .build()

    private fun acceptStore(record: EndpointRecord): Boolean {
        val now = clock()
        val rejection = validate(record, now) ?: synchronized(storeLock) { insert(record) }
        if (rejection == null) {
            stats?.onStored()
            stats?.setRecordCount(records.size)
            return true
        }
        stats?.onRejected(rejection)
        log.debug(
            "dht store rejected reason={} key={}",
            rejection,
            IdentityHashMatcher.hashPrefixHex(record.identityHash.toByteArray()),
        )
        return false
    }

    /** Stateless bounds and authenticity checks; returns a rejection reason or null. */
    @Suppress("ReturnCount")
    private fun validate(record: EndpointRecord, now: Long): String? {
        if (record.ttlMs <= 0 || record.ttlMs > maxRecordTtlMs) return REASON_TTL
        if (record.publishedAtUnixMs > now + maxFutureSkewMs) return REASON_FUTURE
        if (isExpired(record, now)) return REASON_EXPIRED
        if (!verifier.verify(record, now)) return REASON_INVALID
        return null
    }

    /** Must run under [storeLock] so the capacity check and the insert are atomic. */
    private fun insert(record: EndpointRecord): String? {
        val key = IdentityHashMatcher.routingKeyHex(record.identityHash.toByteArray())
        val existing = records[key]
        return when {
            existing != null && record.sequence <= existing.sequence -> REASON_SEQUENCE
            existing == null && records.size >= maxRecords -> REASON_CAPACITY
            else -> {
                records[key] = record
                null
            }
        }
    }

    private fun isExpired(record: EndpointRecord, now: Long): Boolean =
        now >= record.publishedAtUnixMs + record.ttlMs

    companion object {
        const val DEFAULT_MAX_RECORDS = 100_000
        const val DEFAULT_MAX_RECORD_TTL_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MAX_FUTURE_SKEW_MS = 5L * 60 * 1000

        const val REASON_TTL = "ttl"
        const val REASON_FUTURE = "future_published_at"
        const val REASON_EXPIRED = "expired"
        const val REASON_INVALID = "invalid"
        const val REASON_SEQUENCE = "sequence"
        const val REASON_CAPACITY = "capacity"

        /** Node id used before ids were persisted per install: SHA-256("vmessenger-node"). */
        fun legacyNodeId(): ByteArray = sha256("vmessenger-node".toByteArray(Charsets.UTF_8))

        private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }
}
