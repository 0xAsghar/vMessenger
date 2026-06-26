package ir.vmessenger.core.common.encoding

import java.security.MessageDigest

private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val PREFIX = "vm1"
private const val PREFIX_UPPER = "VM1"
private const val GROUP_SIZE = 5
private const val PREFIX_BYTES = 16
private val UNICODE_DASHES = Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]")
private val INVISIBLE_CHARS = Regex("[\\u200B-\\u200D\\uFEFF]")

object UserHashEncoder {
    fun identityHashFromPublicKey(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey)

    fun encode(identityHash: ByteArray): String {
        val prefix = identityHash.copyOf(PREFIX_BYTES)
        val checksum = checksumOf(prefix)
        val payload = prefix + byteArrayOf(checksum.toByte())
        val encoded = encodeCrockford(payload)
        return formatGrouped("$PREFIX-$encoded")
    }

    fun decode(userHash: String): ByteArray? {
        val normalized = normalizeInput(userHash)
        val bytes = if (normalized.length >= GROUP_SIZE) decodeCrockford(normalized) else null
        return bytes?.let(::decodePayload)
    }

    fun isValid(userHash: String): Boolean = decode(userHash) != null

    fun decodeFailureReason(userHash: String): String =
        failureReasonForTrimmed(userHash.trim())
}

private fun decodePayload(bytes: ByteArray): ByteArray? {
    if (bytes.size < PREFIX_BYTES + 1) return null
    val payload = bytes.copyOfRange(0, PREFIX_BYTES + 1)
    val prefix = payload.copyOf(PREFIX_BYTES)
    return if (payload[PREFIX_BYTES].toInt() and 0xFF == checksumOf(prefix)) prefix else null
}

private fun checksumOf(prefix: ByteArray): Int =
    prefix.takeLast(2).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }

private fun normalizeInput(userHash: String): String =
    userHash.trim().uppercase()
        .replace(UNICODE_DASHES, "-")
        .replace(INVISIBLE_CHARS, "")
        .removePrefix("$PREFIX_UPPER-")
        .replace("-", "")

private fun formatGrouped(value: String): String {
    val body = value.substringAfter('-')
    val groups = body.chunked(GROUP_SIZE).joinToString("-")
    return "$PREFIX-$groups"
}

private fun failureReasonForTrimmed(trimmed: String): String = when {
    trimmed.isEmpty() -> "empty"
    !trimmed.uppercase().startsWith("$PREFIX_UPPER-") -> "missing_prefix"
    else -> failureReasonForBody(trimmed)
}

private fun failureReasonForBody(trimmed: String): String {
    val normalized = normalizeInput(trimmed)
    return when {
        normalized.length < GROUP_SIZE -> "too_short(len=${normalized.length})"
        else -> failureReasonForCrockford(normalized)
    }
}

private fun failureReasonForCrockford(normalized: String): String {
    val bytes = decodeCrockford(normalized)
    return when {
        bytes == null -> "invalid_character"
        bytes.size < PREFIX_BYTES + 1 -> "decoded_too_short(bytes=${bytes.size})"
        else -> checksumFailureReason(bytes)
    }
}

private fun checksumFailureReason(bytes: ByteArray): String {
    val payload = bytes.copyOfRange(0, PREFIX_BYTES + 1)
    val prefix = payload.copyOf(PREFIX_BYTES)
    return if (payload[PREFIX_BYTES].toInt() and 0xFF == checksumOf(prefix)) "ok" else "checksum_mismatch"
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
    return out.toByteArray()
}
