package ir.vmessenger.core.update

/** `<64 hex>  <name>` — one or more spaces/tabs, optionally a `*` binary marker before the name. */
private val DIGEST_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(\\S.*)$")

/**
 * Reads `sha256sum` output: the release's `SHA256SUMS.txt` and the per-asset
 * `<asset>.apk.sha256` share this format, so one parser covers both.
 *
 * Lines are trimmed before matching, which is what makes CRLF files (and the
 * stray trailing blank line every shell redirect leaves) parse the same.
 */
object ChecksumFile {
    /** Asset name (without any directory part) to its lower-case digest; later lines win. */
    fun parse(content: String): Map<String, String> = content.lineSequence()
        .mapNotNull { DIGEST_LINE.matchEntire(it.trim()) }
        .associate { match ->
            match.groupValues[2].trim().substringAfterLast('/') to match.groupValues[1].lowercase()
        }

    /**
     * The digest published for [assetName], or null when the file does not name it.
     *
     * The name has to match: a checksum file that lists some other artifact says
     * nothing about the bytes we just downloaded.
     */
    fun digestFor(content: String, assetName: String): String? = parse(content)[assetName]
}

internal fun ByteArray.toHexLowercase(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
