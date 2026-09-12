package ir.vmessenger.core.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalTest {
    @Test
    fun fixedWidthIntegersAreBigEndian() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), Canonical.u32be(1))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), Canonical.u32be(0xFFFF_FFFFL))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0), Canonical.u64be(256L))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1), Canonical.u64be(-1L))
    }

    @Test
    fun u32RejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { Canonical.u32be(-1L) }
        assertThrows(IllegalArgumentException::class.java) { Canonical.u32be(0x1_0000_0000L) }
    }

    @Test
    fun u32OfIntIsUnsigned() {
        // proto uint32/enum accessors return raw negative Ints for wire values >= 2^31.
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), Canonical.u32be(-1))
        assertArrayEquals(byteArrayOf(-128, 0, 0, 0), Canonical.u32be(Int.MIN_VALUE))
        assertArrayEquals(Canonical.u32be(0xFFFF_FFFFL), Canonical.u32be(-1))
    }

    @Test
    fun lengthPrefixMakesConcatenationInjective() {
        val a = Canonical.lp(byteArrayOf(1, 2)) + Canonical.lp(byteArrayOf(3))
        val b = Canonical.lp(byteArrayOf(1)) + Canonical.lp(byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 1, 2, 0, 0, 0, 1, 3), a)
        assertEquals(false, a.contentEquals(b))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), Canonical.lp(ByteArray(0)))
    }

    @Test
    fun lpUtf8EncodesUtf8Bytes() {
        val encoded = Canonical.lpUtf8("سلام")
        val utf8 = "سلام".toByteArray(Charsets.UTF_8)
        assertEquals(4 + utf8.size, encoded.size)
        assertArrayEquals(utf8, encoded.copyOfRange(4, encoded.size))
        assertEquals(2, ProtocolVersion.MAJOR)
    }
}
