package ir.vmessenger.core.common.concurrency

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class KeyedMutexTest {
    private class CountingSlot : KeyedSlot() {
        var payload: Int = 0
    }

    @Test
    fun refCountRemovesIdleEntries() = runBlocking {
        val keyed = KeyedMutex<String, CountingSlot> { CountingSlot() }
        keyed.withLock("a") { slot ->
            assertEquals(1, slot.refCount)
            assertSame(slot, keyed.peek("a"))
            assertEquals(1, keyed.size)
        }
        assertNull(keyed.peek("a"))
        assertEquals(0, keyed.size)

        val retained = keyed.retain("b")
        keyed.withLock("b") { slot ->
            assertSame(retained, slot)
            assertEquals(2, slot.refCount)
        }
        assertEquals(1, retained.refCount)
        assertNotNull(keyed.peek("b"))
        keyed.release("b", retained)
        assertNull(keyed.peek("b"))
        assertEquals(0, keyed.size)
    }

    @Test
    fun sameKeySerialized() = runBlocking {
        val keyed = KeyedMutex<String>()
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        coroutineScope {
            repeat(8) {
                launch(Dispatchers.Default) {
                    keyed.withLock("x") {
                        val now = inside.incrementAndGet()
                        maxInside.accumulateAndGet(now, ::maxOf)
                        delay(20)
                        inside.decrementAndGet()
                    }
                }
            }
        }
        assertEquals(1, maxInside.get())
        assertEquals(0, keyed.size)
    }

    @Test
    fun differentKeysParallel() = runBlocking {
        val keyed = KeyedMutex<String>()
        val started = System.nanoTime()
        withContext(Dispatchers.Default) {
            (1..4).map { i ->
                async { keyed.withLock("key-$i") { delay(300) } }
            }.awaitAll()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("four disjoint keys must not serialize (took $elapsedMs ms)", elapsedMs < 900)
        assertEquals(0, keyed.size)
    }

    @Test
    fun slotStateSurvivesWhileRetained() = runBlocking {
        val keyed = KeyedMutex<String, CountingSlot> { CountingSlot() }
        val retained = keyed.retain("s").also { it.payload = 42 }
        keyed.withLock("s") { slot -> assertEquals(42, slot.payload) }
        keyed.release("s", retained)
        keyed.withLock("s") { slot -> assertEquals("fresh slot after release", 0, slot.payload) }
    }

    @Test
    fun releaseOfStaleSlotDoesNotTouchReplacement() = runBlocking {
        val keyed = KeyedMutex<String, CountingSlot> { CountingSlot() }
        val old = keyed.retain("k")
        keyed.release("k", old)
        val fresh = keyed.retain("k")
        keyed.release("k", old) // stale: must be a no-op
        assertSame(fresh, keyed.peek("k"))
        assertEquals(1, fresh.refCount)
        keyed.release("k", fresh)
        assertNull(keyed.peek("k"))
    }
}
