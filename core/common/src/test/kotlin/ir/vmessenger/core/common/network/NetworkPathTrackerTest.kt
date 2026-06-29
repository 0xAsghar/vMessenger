package ir.vmessenger.core.common.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NetworkPathTrackerTest {
    @Before
    fun reset() {
        NetworkPathTracker.clear()
    }

    @After
    fun tearDown() {
        NetworkPathTracker.clear()
    }

    @Test
    fun lastPathReflectsMostRecentEvent() {
        NetworkPathTracker.record(NetworkPath.DEFAULT_RELAY, "relay", atUnixMs = 1)
        NetworkPathTracker.record(NetworkPath.DIRECT, "1.2.3.4:9", atUnixMs = 2)

        val last = NetworkPathTracker.lastPath.value
        assertEquals(NetworkPath.DIRECT, last?.path)
        assertEquals("1.2.3.4:9", last?.detail)
        assertEquals(2, NetworkPathTracker.events.value.size)
        assertEquals(NetworkPath.DIRECT, NetworkPathTracker.events.value.first().path)
    }

    @Test
    fun historyIsCappedAndNewestFirst() {
        repeat(30) { i -> NetworkPathTracker.record(NetworkPath.DIRECT, "peer$i", atUnixMs = i.toLong()) }

        val events = NetworkPathTracker.events.value
        assertEquals(20, events.size)
        assertEquals("peer29", events.first().detail)
    }

    @Test
    fun clearResetsState() {
        NetworkPathTracker.record(NetworkPath.USER_RELAY, "x")
        NetworkPathTracker.clear()
        assertNull(NetworkPathTracker.lastPath.value)
        assertEquals(0, NetworkPathTracker.events.value.size)
    }
}
