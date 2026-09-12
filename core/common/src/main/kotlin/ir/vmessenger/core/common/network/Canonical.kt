package ir.vmessenger.core.common.network

import java.nio.ByteBuffer

/**
 * Canonical, unambiguous byte encodings shared by every signed transcript
 * (handshake, endpoint record, node record, relay proof, mailbox inner).
 * Length prefixes make concatenations injective, so no two field layouts can
 * collide on the same bytes.
 */
object Canonical {
    private const val U32_BYTES = 4
    private const val U64_BYTES = 8

    /** Big-endian unsigned 32-bit; [value] must fit in `0..0xFFFF_FFFF`. */
    fun u32be(value: Long): ByteArray {
        require(value in 0..MAX_U32) { "u32 out of range: $value" }
        return ByteBuffer.allocate(U32_BYTES).putInt(value.toInt()).array()
    }

    /**
     * Big-endian unsigned 32-bit of an Int taken as *unsigned* (proto `uint32`
     * and enum accessors hand back raw negative Ints for wire values `>= 2^31`,
     * and a peer-chosen field must never make a transcript throw).
     */
    fun u32be(value: Int): ByteArray = u32be(value.toLong() and MAX_U32)

    /** Big-endian 64-bit two's complement (unsigned values `< 2^63` round-trip as themselves). */
    fun u64be(value: Long): ByteArray = ByteBuffer.allocate(U64_BYTES).putLong(value).array()

    /** `u32be(len) || bytes`. */
    fun lp(bytes: ByteArray): ByteArray = u32be(bytes.size) + bytes

    /** `lp(utf8(text))`. */
    fun lpUtf8(text: String): ByteArray = lp(text.toByteArray(Charsets.UTF_8))

    private const val MAX_U32 = 0xFFFF_FFFFL
}
