package ir.vmessenger.core.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DatabaseKeyProviderTest {
    /** Counts loads and yields inside [load] so an unguarded provider would overlap two of them. */
    private class CountingSource : DatabasePassphraseSource {
        val loads = AtomicInteger(0)

        override suspend fun load(): ByteArray {
            val ordinal = loads.incrementAndGet()
            delay(SLOW_LOAD_MS)
            return ByteArray(PASSPHRASE_BYTES) { ordinal.toByte() }
        }
    }

    @Test
    fun initializeIdempotentUnderConcurrency() = runTest {
        val source = CountingSource()
        val provider = DatabaseKeyProvider(source)

        withContext(Dispatchers.Default) {
            (1..CONCURRENT_CALLERS).map { async { provider.initialize() } }.awaitAll()
        }

        assertEquals(1, source.loads.get())
        // Every caller sees the one passphrase that was created.
        assertArrayEquals(ByteArray(PASSPHRASE_BYTES) { 1 }, provider.getPassphrase())
    }

    @Test
    fun repeatedInitializeDoesNotReload() = runTest {
        val source = CountingSource()
        val provider = DatabaseKeyProvider(source)

        provider.initialize()
        provider.initialize()
        provider.initialize()

        assertEquals(1, source.loads.get())
    }

    @Test
    fun resetForgetsPassphraseAndNextInitializeLoadsAgain() = runTest {
        val source = CountingSource()
        val provider = DatabaseKeyProvider(source)
        provider.initialize()

        provider.reset()

        provider.initialize()
        assertEquals(2, source.loads.get())
        assertArrayEquals(ByteArray(PASSPHRASE_BYTES) { 2 }, provider.getPassphrase())
    }

    /**
     * The safety net for the boot receiver, the keep-alive worker and the first
     * composition: they resolve a DAO before the application's warm-up finished
     * and must get the passphrase, not a crash.
     */
    @Test
    fun getPassphraseBeforeInitializeLoadsSynchronously() {
        val source = CountingSource()
        val provider = DatabaseKeyProvider(source)

        val passphrase = provider.getPassphrase()

        assertArrayEquals(ByteArray(PASSPHRASE_BYTES) { 1 }, passphrase)
        assertEquals(1, source.loads.get())
    }

    /** The blocking fallback goes through the same guarded [DatabaseKeyProvider.initialize]. */
    @Test
    fun getPassphraseAfterResetLoadsAgainWithoutInitialize() = runTest {
        val source = CountingSource()
        val provider = DatabaseKeyProvider(source)
        provider.initialize()

        provider.reset()

        assertArrayEquals(ByteArray(PASSPHRASE_BYTES) { 2 }, provider.getPassphrase())
        assertEquals(2, source.loads.get())
    }

    private companion object {
        const val CONCURRENT_CALLERS = 32
        const val PASSPHRASE_BYTES = 32
        const val SLOW_LOAD_MS = 20L
    }
}
