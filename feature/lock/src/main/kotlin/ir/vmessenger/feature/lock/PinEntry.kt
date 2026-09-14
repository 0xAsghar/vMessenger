package ir.vmessenger.feature.lock

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ir.vmessenger.core.crypto.lock.PinVerifier

/** What a PIN character is overwritten with once it is no longer needed. */
internal const val ZEROED = '\u0000'

/**
 * The digits typed so far, in one buffer that is overwritten rather than replaced.
 *
 * A PIN kept in a `String` cannot be erased: every intermediate value stays on the heap until a
 * garbage collection nobody controls. So the entry owns a fixed array, hands out a copy only when
 * the PIN is submitted, and zeroes itself the moment the screen leaves composition.
 */
@Stable
internal class PinEntry {
    private val buffer = CharArray(PinVerifier.MAX_PIN_LENGTH)

    var length by mutableIntStateOf(0)
        private set

    val isSubmittable: Boolean get() = length >= PinVerifier.MIN_PIN_LENGTH

    fun append(digit: Char) {
        if (length < buffer.size) buffer[length++] = digit
    }

    fun backspace() {
        if (length > 0) buffer[--length] = ZEROED
    }

    /** Hands out a fresh array the caller owns and must zero, and forgets this copy of it. */
    fun take(): CharArray = buffer.copyOf(length).also { clear() }

    fun clear() {
        buffer.fill(ZEROED)
        length = 0
    }
}
