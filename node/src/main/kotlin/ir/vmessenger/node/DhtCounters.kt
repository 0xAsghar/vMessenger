package ir.vmessenger.node

/**
 * Metrics sink for [DhtRequestHandler]. Kept as a tiny interface so the handler
 * compiles without the node-wide stats holder; the stats holder implements it.
 */
interface DhtCounters {
    /** A record was accepted (new key or an update of an existing key). */
    fun onStored()

    /** A store was rejected; [reason] is a short stable token (e.g. `ttl`, `sequence`). */
    fun onRejected(reason: String)

    /** The current number of records held after a store or sweep. */
    fun setRecordCount(n: Int)

    object NoOp : DhtCounters {
        override fun onStored() = Unit
        override fun onRejected(reason: String) = Unit
        override fun setRecordCount(n: Int) = Unit
    }
}

/** Feeds the DHT record gauge of [NodeStats]; store/reject events are only logged by the handler. */
fun NodeStats.asDhtCounters(): DhtCounters = object : DhtCounters {
    override fun onStored() = Unit
    override fun onRejected(reason: String) = Unit
    override fun setRecordCount(n: Int) = dhtRecords.set(n)
}
