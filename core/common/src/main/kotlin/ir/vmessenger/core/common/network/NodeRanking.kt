package ir.vmessenger.core.common.network

/** The health/priority facts [NodeRanking] orders by, independent of the DB entity type. */
data class NodeRankKey(
    val priority: Int,
    val failCount: Int,
    val lastOkUnixMs: Long?,
    val lastFailUnixMs: Long? = null,
)

/**
 * Pure ordering of candidate nodes so the DAO can return rows unordered and the
 * policy lives in one unit-testable place.
 *
 * Order: healthy bucket first (`failCount < UNHEALTHY_FAIL_COUNT`), then
 * `priority DESC`, then `failCount ASC`, then `lastOkUnixMs DESC`. Within the unhealthy
 * bucket the node that failed longest ago comes first: when every candidate is failing, the one
 * that just failed yields to the others instead of winning again on priority — a broken
 * relay would otherwise hold the listener forever while a working one waits. With the
 * default priorities (user 150, official 100, community 80) a node is displaced by a
 * lower-priority one only after three consecutive failures; a successful connection
 * (`markOk`) resets the counter and restores it.
 */
object NodeRanking {
    /** A node with this many consecutive failures is tried after every healthier one. */
    const val UNHEALTHY_FAIL_COUNT = 3

    const val PRIORITY_USER = 150
    const val PRIORITY_OFFICIAL = 100
    const val PRIORITY_COMMUNITY = 80

    fun <T> rank(nodes: List<T>, key: (T) -> NodeRankKey): List<T> =
        nodes.sortedWith(
            compareBy<T> { key(it).failCount >= UNHEALTHY_FAIL_COUNT }
                .thenBy { if (key(it).failCount >= UNHEALTHY_FAIL_COUNT) key(it).lastFailUnixMs ?: 0L else 0L }
                .thenByDescending { key(it).priority }
                .thenBy { key(it).failCount }
                .thenByDescending { key(it).lastOkUnixMs ?: Long.MIN_VALUE },
        )

    fun rankKeys(nodes: List<NodeRankKey>): List<NodeRankKey> = rank(nodes) { it }

    /** Default priority for a freshly stored node of the given trust level. */
    fun defaultPriority(trust: NodeTrust): Int = when (trust) {
        NodeTrust.USER -> PRIORITY_USER
        NodeTrust.OFFICIAL -> PRIORITY_OFFICIAL
        NodeTrust.COMMUNITY -> PRIORITY_COMMUNITY
    }

    /** Whether a freshly stored node of the given trust level is enabled without user consent. */
    fun autoEnabled(trust: NodeTrust): Boolean = trust != NodeTrust.COMMUNITY
}
