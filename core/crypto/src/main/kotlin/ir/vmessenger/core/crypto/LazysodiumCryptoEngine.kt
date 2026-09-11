package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.DiffieHellman
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import com.sun.jna.NativeLong
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Suppress("TooManyFunctions")
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
        val signature = ByteArray(Sign.BYTES)
        check(
            lazySodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey),
        ) { "ed25519 sign failed" }
        return signature
    }

    override fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        lazySodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    override fun generateX25519KeyPair(): KeyPair {
        val keyPair = lazySodium.cryptoBoxKeypair()
        return KeyPair(keyPair.publicKey.asBytes, keyPair.secretKey.asBytes)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val sharedHex = lazySodium.cryptoBoxBeforeNm(publicKey, privateKey)
        return LazySodium.toBin(sharedHex)
    }

    override fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        val aeadKey = Key.fromBytes(key)
        val nonce = lazySodium.nonce(AEAD.CHACHA20POLY1305_IETF_NPUBBYTES)
        val ciphertextHex = lazySodium.encrypt(
            LazySodium.toHex(plaintext),
            LazySodium.toHex(associatedData),
            nonce,
            aeadKey,
            AEAD.Method.CHACHA20_POLY1305_IETF,
        )
        return nonce + LazySodium.toBin(ciphertextHex)
    }

    override fun open(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray? {
        if (ciphertext.size < AEAD.CHACHA20POLY1305_IETF_NPUBBYTES) return null
        return try {
            val nonce = ciphertext.copyOfRange(0, AEAD.CHACHA20POLY1305_IETF_NPUBBYTES)
            val encrypted = ciphertext.copyOfRange(AEAD.CHACHA20POLY1305_IETF_NPUBBYTES, ciphertext.size)
            val plaintextHex = lazySodium.decrypt(
                LazySodium.toHex(encrypted),
                LazySodium.toHex(associatedData),
                nonce,
                Key.fromBytes(key),
                AEAD.Method.CHACHA20_POLY1305_IETF,
            )
            LazySodium.toBin(plaintextHex)
        } catch (_: Exception) {
            null
        }
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val copyLen = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, copyLen)
            offset += copyLen
            counter++
        }
        return out
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
        return if (ok) {
            plaintext
        } else {
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
        require(secretKey.size == Sign.SECRETKEYBYTES) { "ed25519 private key must be 64 bytes (or a 32-byte seed)" }
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        check(lazySodium.cryptoSignEd25519SkToPk(publicKey, secretKey)) { "crypto_sign_ed25519_sk_to_pk failed" }
        if (secretKey !== privateKey) memzero(secretKey)
        return publicKey
    }

    override fun memzero(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!lazySodium.sodiumMemZero(bytes, bytes.size)) bytes.fill(0)
    }

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
}
