package ir.vmessenger.core.designsystem.component

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonSeedTest {
    private val full = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `the full hash, the padded prefix and the bare prefix draw the same person`() {
        val prefix = full.copyOf(16)
        val padded = prefix.copyOf(32)

        assertArrayEquals(personSeed(full), personSeed(padded))
        assertArrayEquals(personSeed(full), personSeed(prefix))
    }

    @Test
    fun `a short or empty seed is left as it is`() {
        assertEquals(4, personSeed(ByteArray(4)).size)
        assertEquals(0, personSeed(ByteArray(0)).size)
    }
}
