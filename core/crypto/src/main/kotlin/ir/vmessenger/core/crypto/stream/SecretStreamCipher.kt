package ir.vmessenger.core.crypto.stream

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.SecretStream

/**
 * `crypto_secretstream_xchacha20poly1305`: an authenticated, ordered stream of
 * chunks under one 32-byte key. Each chunk carries a tag; the last one MUST be
 * [SecretStreamPuller.PulledChunk.isFinal] so a truncated stream is detected.
 */
interface SecretStreamCipher {
    /** Starts an encryption stream for [key]; the returned pusher exposes the 24-byte header. */
    fun push(key: ByteArray): SecretStreamPusher

    /** Starts a decryption stream for [key] from [header]; returns null when the header/key do not match. */
    fun pull(key: ByteArray, header: ByteArray): SecretStreamPuller?

    companion object {
        const val KEY_BYTES = SecretStream.KEYBYTES
        const val HEADER_BYTES = SecretStream.HEADERBYTES
        const val TAG_BYTES = SecretStream.ABYTES
    }
}

interface SecretStreamPusher : AutoCloseable {
    val header: ByteArray

    /** Encrypts one chunk; [isFinal] marks the last chunk of the stream. */
    fun push(plaintext: ByteArray, length: Int, isFinal: Boolean): ByteArray
}

interface SecretStreamPuller : AutoCloseable {
    class PulledChunk(val data: ByteArray, val isFinal: Boolean)

    /** Decrypts one chunk; null when authentication fails (tampered, reordered or wrong key). */
    fun pull(ciphertext: ByteArray, length: Int): PulledChunk?
}

class LazysodiumSecretStream(private val lazySodium: LazySodium) : SecretStreamCipher {
    override fun push(key: ByteArray): SecretStreamPusher {
        require(key.size == SecretStream.KEYBYTES) { "secretstream key must be ${SecretStream.KEYBYTES} bytes" }
        val state = SecretStream.State()
        val header = ByteArray(SecretStream.HEADERBYTES)
        check(lazySodium.cryptoSecretStreamInitPush(state, header, key)) { "secretstream init_push failed" }
        return Pusher(state, header)
    }

    override fun pull(key: ByteArray, header: ByteArray): SecretStreamPuller? {
        if (key.size != SecretStream.KEYBYTES || header.size != SecretStream.HEADERBYTES) return null
        val state = SecretStream.State()
        return if (lazySodium.cryptoSecretStreamInitPull(state, header, key)) Puller(state) else null
    }

    private inner class Pusher(
        private val state: SecretStream.State,
        override val header: ByteArray,
    ) : SecretStreamPusher {
        override fun push(plaintext: ByteArray, length: Int, isFinal: Boolean): ByteArray {
            require(length in 0..plaintext.size) { "invalid chunk length" }
            val ciphertext = ByteArray(length + SecretStream.ABYTES)
            val tag = if (isFinal) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
            check(lazySodium.cryptoSecretStreamPush(state, ciphertext, plaintext, length.toLong(), tag)) {
                "secretstream push failed"
            }
            return ciphertext
        }

        override fun close() = wipe(state)
    }

    private inner class Puller(private val state: SecretStream.State) : SecretStreamPuller {
        override fun pull(ciphertext: ByteArray, length: Int): SecretStreamPuller.PulledChunk? {
            if (length < SecretStream.ABYTES || length > ciphertext.size) return null
            val plaintext = ByteArray(length - SecretStream.ABYTES)
            val tag = ByteArray(1)
            val ok = lazySodium.cryptoSecretStreamPull(state, plaintext, tag, ciphertext, length.toLong())
            return if (ok) {
                SecretStreamPuller.PulledChunk(plaintext, tag[0] == SecretStream.TAG_FINAL)
            } else {
                plaintext.fill(0)
                null
            }
        }

        override fun close() = wipe(state)
    }

    private fun wipe(state: SecretStream.State) {
        state.k?.fill(0)
        state.nonce?.fill(0)
        runCatching { state.write() }
    }
}
