package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
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
        val sharedHex = lazySodium.cryptoBoxBeforeNm(privateKey, publicKey)
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
