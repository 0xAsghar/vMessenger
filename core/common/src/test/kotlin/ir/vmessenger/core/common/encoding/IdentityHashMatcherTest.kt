package ir.vmessenger.core.common.encoding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityHashMatcherTest {
    @Test
    fun matchesFullHash() {
        val hash = ByteArray(32) { it.toByte() }
        assertTrue(IdentityHashMatcher.matches(hash, hash))
    }

    @Test
    fun matchesStoredPrefixToFullHash() {
        val full = ByteArray(32) { it.toByte() }
        val stored = ByteArray(32).also { full.copyInto(it, 0, 0, 16) }
        assertTrue(IdentityHashMatcher.matches(stored, full))
        assertTrue(IdentityHashMatcher.matches(full, stored))
    }

    @Test
    fun rejectsDifferentPrefix() {
        val full = ByteArray(32) { it.toByte() }
        val stored = ByteArray(32) { (it + 1).toByte() }
        assertFalse(IdentityHashMatcher.matches(stored, full))
    }
}
