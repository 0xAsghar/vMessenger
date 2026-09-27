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
    fun officialNotDisplacedByOneFailure() {
        val official = node("wss://official.example/relay", NodeRanking.PRIORITY_OFFICIAL, failCount = 1)
        val community = node("wss://community.example/relay", NodeRanking.PRIORITY_COMMUNITY, lastOk = 1_000L)
        assertEquals(listOf(official.address, community.address), rank(community, official))
        // Two failures still keep it in the healthy bucket.
        val twoFailures = official.copy(key = official.key.copy(failCount = 2))
        assertEquals(listOf(official.address, community.address), rank(community, twoFailures))
    }

    @Test
    fun threeFailuresDemote() {
        val official = node("wss://official.example/relay", NodeRanking.PRIORITY_OFFICIAL, failCount = 3)
        val community = node("wss://community.example/relay", NodeRanking.PRIORITY_COMMUNITY)
        assertEquals(listOf(community.address, official.address), rank(official, community))
        // markOk resets failCount and the official relay comes back on top.
        val recovered = official.copy(key = official.key.copy(failCount = 0, lastOkUnixMs = 5_000L))
        assertEquals(listOf(official.address, community.address), rank(community, recovered))
    }

    @Test
    fun userNodeOutranksOfficial() {
        val official = node("wss://official.example/relay", NodeRanking.PRIORITY_OFFICIAL, lastOk = 9_000L)
        val user = node("wss://my.example/relay", NodeRanking.PRIORITY_USER)
        assertEquals(listOf(user.address, official.address), rank(official, user))
        // ...but an unhealthy user node drops behind a healthy official one.
        val brokenUser = user.copy(key = user.key.copy(failCount = NodeRanking.UNHEALTHY_FAIL_COUNT))
        assertEquals(listOf(official.address, user.address), rank(brokenUser, official))
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
        assertTrue(NodeRanking.autoEnabled(NodeTrust.USER))
        assertTrue(NodeRanking.autoEnabled(NodeTrust.OFFICIAL))
        assertEquals(NodeRanking.PRIORITY_COMMUNITY, NodeRanking.defaultPriority(NodeTrust.COMMUNITY))
        assertEquals(NodeRanking.PRIORITY_USER, NodeRanking.defaultPriority(NodeTrust.USER))
        assertEquals(NodeRanking.PRIORITY_OFFICIAL, NodeRanking.defaultPriority(NodeTrust.OFFICIAL))
        assertEquals(NodeTrust.COMMUNITY, NodeTrust.fromName("garbage"))
        // A test-node row from 2.0.x, had one survived schema 26, would read as a community node.
        assertEquals(NodeTrust.COMMUNITY, NodeTrust.fromName("BUILT_IN"))
    }

    @Test
    fun whenAllAreFailingTheOneThatFailedLongestAgoGoesFirst() {
        val brokenUser = NodeRankKey(NodeRanking.PRIORITY_USER, 7, lastOkUnixMs = null, lastFailUnixMs = 2_000)
        val staleOfficial = NodeRankKey(NodeRanking.PRIORITY_OFFICIAL, 10, lastOkUnixMs = 1, lastFailUnixMs = 1_000)
        assertEquals(listOf(staleOfficial, brokenUser), NodeRanking.rankKeys(listOf(brokenUser, staleOfficial)))
        // Once the official relay fails too, the user relay gets its turn again.
        val justFailed = staleOfficial.copy(lastFailUnixMs = 3_000)
        assertEquals(listOf(brokenUser, justFailed), NodeRanking.rankKeys(listOf(brokenUser, justFailed)))
    }
}
