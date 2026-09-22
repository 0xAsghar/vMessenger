package ir.vmessenger.core.common.encoding

import java.security.MessageDigest

private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val PREFIX = "vm2"

/**
 * Prefixes accepted on decode: the current `vm2-` and the shorter `vm-` the identity is migrating to,
 * so a peer can read a `vm-…` hash before any build emits one. The trailing dash keeps them
 * unambiguous — `VM2-…` never starts with `VM-`, and `VM-…` never starts with `VM2-` — so the list is
 * correct in both migration phases regardless of which one `encode` currently emits.
 */
private val ACCEPTED_PREFIXES_UPPER = listOf("VM2", "VM")
private const val GROUP_SIZE = 5
private const val PREFIX_BYTES = 16
private const val CHECKSUM_BYTES = 2
private const val PAYLOAD_BYTES = PREFIX_BYTES + CHECKSUM_BYTES

/** 18 payload bytes = 144 bits -> 29 base32 symbols (the last one carries 4 data bits + 1 zero pad bit). */
private const val ENCODED_LENGTH = 29
private const val CHECKSUM_TAG = "vmessenger-userhash-v2"
private val UNICODE_DASHES = Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]")
private val INVISIBLE_CHARS = Regex("[\\u200B-\\u200D\\uFEFF]")

/**
 * Human-shareable identity hash, format v2: `vm2-` + Crockford base32 of
 * `prefix16 || SHA256("vmessenger-userhash-v2" || prefix16)[0..2)`, grouped `5-5-5-5-5-4`.
 *
 * The checksum covers every prefix byte (v1 only XOR-ed the last two). Decode accepts both `vm2-` and
 * the shorter `vm-` form the identity is migrating to — the encoded body is identical because the
 * checksum tag is unchanged, so the prefix is only a label. A `vm1-` or prefix-less string decodes to
 * null with reason `missing_prefix`.
 */
object UserHashEncoder {
    fun identityHashFromPublicKey(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey)

    fun encode(identityHash: ByteArray): String {
        require(identityHash.size >= PREFIX_BYTES) { "identity hash shorter than $PREFIX_BYTES bytes" }
        val prefix = identityHash.copyOf(PREFIX_BYTES)
        val encoded = encodeCrockford(prefix + checksumOf(prefix))
        return "$PREFIX-" + encoded.chunked(GROUP_SIZE).joinToString("-")
    }

    fun decode(userHash: String): ByteArray? =
        normalizedBody(userHash)
            ?.takeIf { it.length == ENCODED_LENGTH }
            ?.let(::decodeCrockford)
            ?.let(::decodePayload)

    fun isValid(userHash: String): Boolean = decode(userHash) != null

    fun decodeFailureReason(userHash: String): String =
        failureReasonForTrimmed(userHash.trim())
}

private fun decodePayload(bytes: ByteArray): ByteArray? {
    if (bytes.size < PAYLOAD_BYTES) return null
    val prefix = bytes.copyOf(PREFIX_BYTES)
    val checksum = bytes.copyOfRange(PREFIX_BYTES, PAYLOAD_BYTES)
    return if (checksum.contentEquals(checksumOf(prefix))) prefix else null
}

private fun checksumOf(prefix: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256")
        .digest(CHECKSUM_TAG.toByteArray(Charsets.UTF_8) + prefix)
        .copyOf(CHECKSUM_BYTES)

private fun normalizeChars(userHash: String): String =
    userHash.trim().uppercase()
        .replace(UNICODE_DASHES, "-")
        .replace(INVISIBLE_CHARS, "")

/** Base32 body without prefix and dashes, or null when no accepted prefix (`vm2-`/`vm-`) is present. */
private fun normalizedBody(userHash: String): String? {
    val normalized = normalizeChars(userHash)
    val prefix = ACCEPTED_PREFIXES_UPPER.firstOrNull { normalized.startsWith("$it-") } ?: return null
    return normalized.removePrefix("$prefix-").replace("-", "")
}

private fun failureReasonForTrimmed(trimmed: String): String = when {
    trimmed.isEmpty() -> "empty"
    else -> normalizedBody(trimmed)?.let(::failureReasonForBody) ?: "missing_prefix"
}

private fun failureReasonForBody(body: String): String = when {
    body.length < ENCODED_LENGTH -> "too_short(len=${body.length})"
    body.length > ENCODED_LENGTH -> "too_long(len=${body.length})"
    else -> failureReasonForCrockford(body)
}

private fun failureReasonForCrockford(body: String): String {
    val bytes = decodeCrockford(body) ?: return "invalid_character"
    return if (decodePayload(bytes) != null) "ok" else "checksum_mismatch"
}

private fun encodeCrockford(data: ByteArray): String {
    var buffer = 0L
    var bits = 0
    val out = StringBuilder()
    for (byte in data) {
        buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            val index = ((buffer shr bits) and 0x1F).toInt()
            out.append(CROCKFORD_ALPHABET[index])
        }
    }
    if (bits > 0) {
        val index = ((buffer shl (5 - bits)) and 0x1F).toInt()
        out.append(CROCKFORD_ALPHABET[index])
    }
    return out.toString()
}

private fun decodeCrockford(value: String): ByteArray? {
    var buffer = 0L
    var bits = 0
    val out = ArrayList<Byte>()
    for (ch in value) {
        val index = CROCKFORD_ALPHABET.indexOf(ch)
        if (index < 0) return null
        buffer = (buffer shl 5) or index.toLong()
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    // Canonical form only: leftover pad bits must be zero, otherwise two strings map to one payload.
    val padMask = (1L shl bits) - 1
    return if (buffer and padMask == 0L) out.toByteArray() else null
}
