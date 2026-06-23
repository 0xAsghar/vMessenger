package ir.vmessenger.core.common.encoding

import java.security.MessageDigest

private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val PREFIX = "vm1"
private const val GROUP_SIZE = 5

object UserHashEncoder {
    fun identityHashFromPublicKey(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey)

    fun encode(identityHash: ByteArray): String {
        val checksum = identityHash.takeLast(2).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
        val payload = identityHash.copyOf(16) + byteArrayOf(checksum.toByte())
        val encoded = encodeCrockford(payload)
        return formatGrouped("$PREFIX-$encoded")
    }

    fun decode(userHash: String): ByteArray? {
        val normalized = userHash.trim().uppercase().removePrefix("$PREFIX-").replace("-", "")
        val bytes = if (normalized.length >= GROUP_SIZE) decodeCrockford(normalized) else null
        if (bytes == null || bytes.size < 17) return null
        val checksum = bytes.last().toInt() and 0xFF
        val computed = bytes.dropLast(1).takeLast(2).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
        return if (checksum == computed) bytes.copyOf(16) else null
    }

    fun isValid(userHash: String): Boolean = decode(userHash) != null

    private fun formatGrouped(value: String): String {
        val body = value.substringAfter('-')
        val groups = body.chunked(GROUP_SIZE).joinToString("-")
        return "$PREFIX-$groups"
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
}
