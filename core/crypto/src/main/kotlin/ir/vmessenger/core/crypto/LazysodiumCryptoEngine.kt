package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.DiffieHellman
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.Sign
import com.sun.jna.NativeLong
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * libsodium facade over lazysodium's `byte[]` Native API only: no hex-string round trips, so key
 * material and plaintext never get copied into immutable [String]s that cannot be wiped. Every
 * temporary that holds a secret is `memzero`'d before the function returns.
 */
@Suppress("TooManyFunctions") // one method per libsodium primitive the app uses; see CryptoEngine
class LazysodiumCryptoEngine(
    private val lazySodium: LazySodium,
) : CryptoEngine {

    override fun generateEd25519KeyPair(): KeyPair {
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)
        check(lazySodium.cryptoSignKeypair(publicKey, secretKey)) { "ed25519 keypair generation failed" }
        return KeyPair(publicKey, secretKey)
    }

    override fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray {
        val secretKey = ed25519SecretKeyBytes(privateKey)
        try {
            val signature = ByteArray(Sign.BYTES)
            check(
                lazySodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey),
            ) { "ed25519 sign failed" }
            return signature
        } finally {
            if (secretKey !== privateKey) memzero(secretKey)
        }
    }

    override fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        signature.size == Sign.BYTES &&
            publicKey.size == Sign.PUBLICKEYBYTES &&
            lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    override fun generateX25519KeyPair(): KeyPair {
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val secretKey = ByteArray(Box.SECRETKEYBYTES)
        check(lazySodium.cryptoBoxKeypair(publicKey, secretKey)) { "x25519 keypair generation failed" }
        return KeyPair(publicKey, secretKey)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == DiffieHellman.SCALARMULT_SCALARBYTES) { "x25519 private key must be 32 bytes" }
        require(publicKey.size == DiffieHellman.SCALARMULT_BYTES) { "x25519 public key must be 32 bytes" }
        val shared = ByteArray(DiffieHellman.SCALARMULT_BYTES)
        val ok = lazySodium.cryptoScalarMult(shared, privateKey, publicKey)
        // libsodium itself fails on an all-zero result; check again so a permissive build cannot leak one.
        if (!ok || shared.all { it == 0.toByte() }) {
            memzero(shared)
            error("x25519 shared secret rejected: low-order public key")
        }
        return shared
    }

    override fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = randomBytes(AEAD.CHACHA20POLY1305_IETF_NPUBBYTES)
        return nonce + sealWithNonce(plaintext, key, nonce, associatedData)
    }

    /** ChaCha20-Poly1305-IETF with a caller-supplied 12-byte nonce (output excludes the nonce). */
    internal fun sealWithNonce(plaintext: ByteArray, key: ByteArray, nonce: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == AEAD.CHACHA20POLY1305_IETF_KEYBYTES) { "chacha20poly1305 key must be 32 bytes" }
        require(nonce.size == AEAD.CHACHA20POLY1305_IETF_NPUBBYTES) { "chacha20poly1305 nonce must be 12 bytes" }
        val ciphertext = ByteArray(plaintext.size + AEAD.CHACHA20POLY1305_IETF_ABYTES)
        val ok = lazySodium.cryptoAeadChaCha20Poly1305IetfEncrypt(
            ciphertext,
            null,
            plaintext,
            plaintext.size.toLong(),
            ad,
            ad.size.toLong(),
            null,
            nonce,
            key,
        )
        check(ok) { "chacha20poly1305 seal failed" }
        return ciphertext
    }

    override fun open(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray? {
        val nonceSize = AEAD.CHACHA20POLY1305_IETF_NPUBBYTES
        val wellFormed = key.size == AEAD.CHACHA20POLY1305_IETF_KEYBYTES &&
            ciphertext.size >= nonceSize + AEAD.CHACHA20POLY1305_IETF_ABYTES
        if (!wellFormed) return null
        val nonce = ciphertext.copyOfRange(0, nonceSize)
        val encrypted = ciphertext.copyOfRange(nonceSize, ciphertext.size)
        val plaintext = ByteArray(encrypted.size - AEAD.CHACHA20POLY1305_IETF_ABYTES)
        val ok = lazySodium.cryptoAeadChaCha20Poly1305IetfDecrypt(
            plaintext,
            null,
            null,
            encrypted,
            encrypted.size.toLong(),
            associatedData,
            associatedData.size.toLong(),
            nonce,
            key,
        )
        return plaintext.takeIf { ok } ?: run {
            memzero(plaintext)
            null
        }
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(SHA256_BYTES) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        try {
            while (offset < length) {
                val block = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
                memzero(t)
                t = block
                val copyLen = minOf(t.size, length - offset)
                t.copyInto(out, offset, 0, copyLen)
                offset += copyLen
                counter++
            }
            return out
        } finally {
            memzero(prk)
            memzero(t)
        }
    }

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun randomBytes(length: Int): ByteArray = lazySodium.randomBytesBuf(length)

    override fun pwhashArgon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        keyLen: Int,
    ): ByteArray {
        require(salt.size == PwHash.SALTBYTES) { "argon2id salt must be ${PwHash.SALTBYTES} bytes" }
        require(keyLen >= PwHash.BYTES_MIN) { "argon2id output must be at least ${PwHash.BYTES_MIN} bytes" }
        val key = ByteArray(keyLen)
        val ok = lazySodium.cryptoPwHash(
            key,
            keyLen,
            passphrase,
            passphrase.size,
            salt,
            opsLimit,
            NativeLong(memLimitBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        if (!ok) {
            memzero(key)
            error("argon2id key derivation failed (opsLimit=$opsLimit, memLimit=$memLimitBytes)")
        }
        return key
    }

    override fun xchacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        ad: ByteArray,
    ): ByteArray {
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) { "xchacha20poly1305 key must be 32 bytes" }
        require(nonce.size == AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES) { "xchacha20poly1305 nonce must be 24 bytes" }
        val ciphertext = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext,
            null,
            plaintext,
            plaintext.size.toLong(),
            ad,
            ad.size.toLong(),
            null,
            nonce,
            key,
        )
        check(ok) { "xchacha20poly1305 seal failed" }
        return ciphertext
    }

    override fun xchacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        ad: ByteArray,
    ): ByteArray? {
        val wellFormed = key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES &&
            nonce.size == AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES &&
            ciphertext.size >= AEAD.XCHACHA20POLY1305_IETF_ABYTES
        if (!wellFormed) return null
        val plaintext = ByteArray(ciphertext.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext,
            null,
            null,
            ciphertext,
            ciphertext.size.toLong(),
            ad,
            ad.size.toLong(),
            nonce,
            key,
        )
        return plaintext.takeIf { ok } ?: run {
            memzero(plaintext)
            null
        }
    }

    override fun sealedBoxSeal(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == Box.PUBLICKEYBYTES) { "sealed box recipient key must be 32 bytes" }
        val ciphertext = ByteArray(plaintext.size + Box.SEALBYTES)
        check(
            lazySodium.cryptoBoxSeal(ciphertext, plaintext, plaintext.size.toLong(), recipientPublicKey),
        ) { "crypto_box_seal failed" }
        return ciphertext
    }

    override fun sealedBoxOpen(
        ciphertext: ByteArray,
        recipientPublicKey: ByteArray,
        recipientPrivateKey: ByteArray,
    ): ByteArray? {
        val wellFormed = recipientPublicKey.size == Box.PUBLICKEYBYTES &&
            recipientPrivateKey.size == Box.SECRETKEYBYTES &&
            ciphertext.size >= Box.SEALBYTES
        if (!wellFormed) return null
        val plaintext = ByteArray(ciphertext.size - Box.SEALBYTES)
        val ok = lazySodium.cryptoBoxSealOpen(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            recipientPublicKey,
            recipientPrivateKey,
        )
        return plaintext.takeIf { ok } ?: run {
            memzero(plaintext)
            null
        }
    }

    override fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == DiffieHellman.SCALARMULT_SCALARBYTES) { "x25519 private key must be 32 bytes" }
        val publicKey = ByteArray(DiffieHellman.SCALARMULT_BYTES)
        check(lazySodium.cryptoScalarMultBase(publicKey, privateKey)) { "crypto_scalarmult_base failed" }
        return publicKey
    }

    override fun ed25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        val secretKey = ed25519SecretKeyBytes(privateKey)
        try {
            require(secretKey.size == Sign.SECRETKEYBYTES) {
                "ed25519 private key must be 64 bytes (or a 32-byte seed)"
            }
            val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            check(lazySodium.cryptoSignEd25519SkToPk(publicKey, secretKey)) { "crypto_sign_ed25519_sk_to_pk failed" }
            return publicKey
        } finally {
            if (secretKey !== privateKey) memzero(secretKey)
        }
    }

    override fun memzero(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!lazySodium.sodiumMemZero(bytes, bytes.size)) bytes.fill(0)
    }

    /** Returns [privateKey] itself for a 64-byte secret key, or a freshly expanded copy for a 32-byte seed. */
    private fun ed25519SecretKeyBytes(privateKey: ByteArray): ByteArray = when (privateKey.size) {
        Sign.SECRETKEYBYTES -> privateKey
        Sign.SEEDBYTES -> {
            val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            val secretKey = ByteArray(Sign.SECRETKEYBYTES)
            check(lazySodium.cryptoSignSeedKeypair(publicKey, secretKey, privateKey)) {
                "ed25519 seed keypair expansion failed"
            }
            secretKey
        }
        else -> privateKey
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private companion object {
        const val SHA256_BYTES = 32
    }
}
