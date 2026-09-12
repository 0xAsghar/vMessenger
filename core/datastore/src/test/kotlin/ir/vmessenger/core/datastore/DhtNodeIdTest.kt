package ir.vmessenger.core.datastore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DhtNodeIdTest {
    private class MemoryStorage : DhtNodeIdStorage {
        var value: String? = null
        var writes = 0

        override suspend fun read(): String? = value

        override suspend fun write(encoded: String) {
            value = encoded
            writes++
        }
    }

    @Test
    fun persistedAndStable() = runBlocking {
        val storage = MemoryStorage()
        val first = DhtNodeId.getOrCreate(storage)
        assertEquals(DhtNodeId.SIZE_BYTES, first.size)
        assertNotNull(storage.value)
        assertEquals(1, storage.writes)

        val second = DhtNodeId.getOrCreate(storage)
        assertArrayEquals(first, second)
        assertEquals("a persisted id is never regenerated", 1, storage.writes)

        // A fresh install (empty storage) gets its own, different id.
        val other = DhtNodeId.getOrCreate(MemoryStorage())
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun corruptValueIsReplaced() = runBlocking {
        val storage = MemoryStorage().apply { value = "not-base64!" }
        val id = DhtNodeId.getOrCreate(storage)
        assertEquals(DhtNodeId.SIZE_BYTES, id.size)
        assertEquals(1, storage.writes)
        assertArrayEquals(id, DhtNodeId.getOrCreate(storage))
    }
}
