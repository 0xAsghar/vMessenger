package ir.vmessenger.core.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRankingTest {
    private data class Node(val address: String, val key: NodeRankKey)

    private fun node(address: String, priority: Int, failCount: Int = 0, lastOk: Long? = null) =
        Node(address, NodeRankKey(priority = priority, failCount = failCount, lastOkUnixMs = lastOk))

    private fun rank(vararg nodes: Node): List<String> =
        NodeRanking.rank(nodes.toList()) { it.key }.map { it.address }

    @Test
    fun builtInNotDisplacedByOneFailure() {
        val builtIn = node("wss://relay.vmessenger.ir/relay", NodeRanking.PRIORITY_BUILT_IN, failCount = 1)
        val community = node("wss://community.example/relay", NodeRanking.PRIORITY_COMMUNITY, lastOk = 1_000L)
        assertEquals(listOf(builtIn.address, community.address), rank(community, builtIn))
        // Two failures still keep it in the healthy bucket.
        val twoFailures = builtIn.copy(key = builtIn.key.copy(failCount = 2))
        assertEquals(listOf(builtIn.address, community.address), rank(community, twoFailures))
    }

    @Test
    fun threeFailuresDemote() {
        val builtIn = node("wss://relay.vmessenger.ir/relay", NodeRanking.PRIORITY_BUILT_IN, failCount = 3)
        val community = node("wss://community.example/relay", NodeRanking.PRIORITY_COMMUNITY)
        assertEquals(listOf(community.address, builtIn.address), rank(builtIn, community))
        // markOk resets failCount and the built-in relay comes back on top.
        val recovered = builtIn.copy(key = builtIn.key.copy(failCount = 0, lastOkUnixMs = 5_000L))
        assertEquals(listOf(builtIn.address, community.address), rank(community, recovered))
    }

    @Test
    fun userNodeOutranksBuiltIn() {
        val builtIn = node("wss://relay.vmessenger.ir/relay", NodeRanking.PRIORITY_BUILT_IN, lastOk = 9_000L)
        val user = node("wss://my.example/relay", NodeRanking.PRIORITY_USER)
        assertEquals(listOf(user.address, builtIn.address), rank(builtIn, user))
        // ...but an unhealthy user node drops behind a healthy built-in one.
        val brokenUser = user.copy(key = user.key.copy(failCount = NodeRanking.UNHEALTHY_FAIL_COUNT))
        assertEquals(listOf(builtIn.address, user.address), rank(brokenUser, builtIn))
    }

    @Test
    fun tieBreaksByFailCountThenMostRecentSuccess() {
        val a = node("a", NodeRanking.PRIORITY_COMMUNITY, failCount = 1, lastOk = 10L)
        val b = node("b", NodeRanking.PRIORITY_COMMUNITY, failCount = 0, lastOk = 1L)
        val c = node("c", NodeRanking.PRIORITY_COMMUNITY, failCount = 0, lastOk = 20L)
        val d = node("d", NodeRanking.PRIORITY_COMMUNITY, failCount = 0, lastOk = null)
        assertEquals(listOf("c", "b", "d", "a"), rank(a, b, c, d))
    }

    @Test
    fun communityNeverAutoEnabled() {
        assertFalse(NodeRanking.autoEnabled(NodeTrust.COMMUNITY))
        assertTrue(NodeRanking.autoEnabled(NodeTrust.BUILT_IN))
        assertTrue(NodeRanking.autoEnabled(NodeTrust.USER))
        assertTrue(NodeRanking.autoEnabled(NodeTrust.OFFICIAL))
        assertEquals(NodeRanking.PRIORITY_COMMUNITY, NodeRanking.defaultPriority(NodeTrust.COMMUNITY))
        assertEquals(NodeRanking.PRIORITY_USER, NodeRanking.defaultPriority(NodeTrust.USER))
        assertEquals(NodeRanking.PRIORITY_BUILT_IN, NodeRanking.defaultPriority(NodeTrust.BUILT_IN))
        assertEquals(NodeTrust.COMMUNITY, NodeTrust.fromName("garbage"))
    }

    @Test
    fun whenAllAreFailingTheOneThatFailedLongestAgoGoesFirst() {
        val brokenUser = NodeRankKey(NodeRanking.PRIORITY_USER, 7, lastOkUnixMs = null, lastFailUnixMs = 2_000)
        val staleDefault = NodeRankKey(NodeRanking.PRIORITY_BUILT_IN, 10, lastOkUnixMs = 1, lastFailUnixMs = 1_000)
        assertEquals(listOf(staleDefault, brokenUser), NodeRanking.rankKeys(listOf(brokenUser, staleDefault)))
        // Once the default fails too, the user relay gets its turn again.
        val justFailedDefault = staleDefault.copy(lastFailUnixMs = 3_000)
        assertEquals(listOf(brokenUser, justFailedDefault), NodeRanking.rankKeys(listOf(brokenUser, justFailedDefault)))
    }
}
