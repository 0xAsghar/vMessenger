package ir.vmessenger.node

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 `verify(signature, message, publicKey)` on the JDK's own provider (Java 15+), holding the
 * line libsodium's `crypto_sign_verify_detached` holds.
 *
 * The node used to call libsodium through lazysodium-java, whose current release is built for Java
 * 21 and loads a native library through JNA. The node now runs on whatever JRE the server's apt
 * offers — Java 17 on Debian 12 — and a JNA extraction into a `noexec` `/tmp` is one more way for a
 * start to fail, so it verifies with the JDK instead.
 *
 * The JDK follows RFC 8032, which is looser than libsodium in three places. They are checked here
 * first, so the node refuses what the app's libsodium refuses:
 * - `S` must be below the group order `L` (canonical scalar);
 * - the public key's `y` must be below `p` (canonical encoding);
 * - neither the public key nor `R` may be a point of small order.
 */
object Ed25519Verifier {

    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE || publicKey.size != PUBLIC_KEY_SIZE) return false
        val r = signature.copyOfRange(0, POINT_SIZE)
        val s = signature.copyOfRange(POINT_SIZE, SIGNATURE_SIZE)
        val strict = isCanonicalScalar(s) && isCanonicalPoint(publicKey) &&
            !hasSmallOrder(publicKey) && !hasSmallOrder(r)
        return strict && jdkVerify(signature, message, publicKey)
    }

    private fun jdkVerify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
        Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    }

    /** `S < L`, with `S` read little-endian. */
    internal fun isCanonicalScalar(s: ByteArray): Boolean = littleEndian(s) < GROUP_ORDER

    /** The encoded `y` (sign bit cleared) is below `p = 2^255 - 19`. */
    internal fun isCanonicalPoint(encoded: ByteArray): Boolean {
        val y = encoded.copyOf().also { it[POINT_SIZE - 1] = (it[POINT_SIZE - 1].toInt() and SIGN_MASK).toByte() }
        return littleEndian(y) < FIELD_PRIME
    }

    /** libsodium's `ge25519_has_small_order`: the encoding, sign bit ignored, is one of seven. */
    internal fun hasSmallOrder(encoded: ByteArray): Boolean = SMALL_ORDER.any { blocked ->
        (0 until POINT_SIZE).all { i ->
            val byte = if (i == POINT_SIZE - 1) encoded[i].toInt() and SIGN_MASK else encoded[i].toInt() and BYTE
            byte == (blocked[i].toInt() and BYTE)
        }
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private const val ALGORITHM = "Ed25519"
    private const val POINT_SIZE = 32
    private const val SIGN_MASK = 0x7f
    private const val BYTE = 0xff

    /** DER `SubjectPublicKeyInfo` header for a raw 32-byte Ed25519 key (OID 1.3.101.112). */
    private val X509_PREFIX = hex("302a300506032b6570032100")

    private val FIELD_PRIME: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val GROUP_ORDER: BigInteger =
        BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

    /** The same seven encodings, in the same order, as libsodium's blocklist. */
    private val SMALL_ORDER: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000", // 0, order 4
        "0100000000000000000000000000000000000000000000000000000000000000", // 1, order 1
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05", // order 8
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a", // order 8
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p - 1, order 2
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p (= 0), order 4
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p + 1 (= 1), order 1
    ).map(::hex)

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
    }

    private const val HEX_RADIX = 16
}
