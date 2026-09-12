package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable

/**
 * Identicon seed of a row.
 *
 * A bare `ByteArray` inside a UI model would break both `equals` (identity, so every DB emission
 * looks like a change) and Compose stability. Wrapping it once keeps the row data classes plain
 * and gives content equality in one place.
 */
@Immutable
class IdentitySeed(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is IdentitySeed && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        val Empty = IdentitySeed(ByteArray(0))
    }
}
