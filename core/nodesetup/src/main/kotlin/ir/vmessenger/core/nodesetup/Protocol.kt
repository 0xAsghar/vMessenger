package ir.vmessenger.core.nodesetup

import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/** The installer protocol version this engine speaks (`PROTOCOL_VERSION` in setup-node.sh). */
const val INSTALLER_PROTOCOL = 1

/** How much an issue matters (the marker's `severity`). */
enum class Severity { FATAL, CONSENT, WARN, INFO }

/**
 * Every issue code the installer can report, and the app's own for what happens before and around
 * it. A code this build does not know still arrives, as [Issue.code] text with a null [Issue.known].
 */
enum class IssueCode {
    // setup-node.sh
    USAGE, CMD_MISSING, NOT_ROOT, OS_UNSUPPORTED, OS_UNTESTED, OS_EOL, ARCH_UNSUPPORTED, NO_SYSTEMD,
    RAM_TOO_LOW, DISK_LOW, DISK_CLEANED, SWAP_ADDED, SWAP_SKIPPED, CLOCK_SKEW, CLOCK_FIXED,
    APT_LOCKED, DPKG_INTERRUPTED, APT_REPO_EXCLUDED, APT_MIRROR_SWITCHED, APT_MIRROR_UNREACHABLE,
    APT_EOL_RELEASE, APT_SECURITY_GONE, APT_INDEX_STALE, APT_NETWORK_RETRY, APT_FIXED_BROKEN,
    APT_UPDATE_FAILED, APT_INSTALL_FAILED, JAVA_UNAVAILABLE, JAVA_REUSED,
    PORT_APACHE, PORT_BUSY, PUBLIC_PORT_TAKEN, HTTP_SKIPPED, NODE_PORT_MOVED, UFW_OPENED,
    NGINX_CONFIG_BROKEN, NGINX_NEW_CONFIG_FAILED, TLS_CERT_FAILED, TLS_CERT_MISSING,
    ACME_UNREACHABLE, LE_FAILED, LE_RATE_LIMITED, LE_SKIPPED, DOMAIN_NOT_HERE,
    HEALTH_LOCAL_FAILED, HEALTH_TLS_FAILED, RELAY_UPGRADE_FAILED, ADVERTISED_URL_MISMATCH,
    DOWNGRADE, PUBLIC_HOST_UNKNOWN, BUNDLE_MISSING, BUNDLE_CORRUPT, INSTALL_BUSY, RUN_UNKNOWN,
    RUN_START_FAILED, HARDEN_F2B_FAILED, HARDEN_SSH_NO_KEYS, HARDEN_SSH_NO_INCLUDE, HARDEN_SSH_INVALID,
    HARDEN_SSH_OVERRIDDEN, HARDEN_SSH_TIMER, HARDEN_SSH_ROLLED_BACK, INTERNAL,

    // the app
    SSH_UNREACHABLE, SSH_TIMEOUT, SSH_HOST_KEY_MISMATCH, SSH_AUTH_REJECTED, SSH_KEY_UNREADABLE,
    SSH_DISCONNECTED, SUDO_PASSWORD_WRONG, SUDO_NOT_ALLOWED, UPLOAD_FAILED, PROTOCOL_MISMATCH,
    RESULT_INVALID, REACH_TIMEOUT, REACH_TLS_MISMATCH, REACH_REFUSED, CANCELLED,
    ;

    companion object {
        fun of(code: String): IssueCode? = entries.firstOrNull { it.name == code }
    }
}

/** A problem the installer (or the engine) reported. */
data class Issue(val code: String, val severity: Severity, val detail: String = "", val step: String? = null) {
    val known: IssueCode? get() = IssueCode.of(code)

    companion object {
        fun of(code: IssueCode, severity: Severity = Severity.FATAL, detail: String = "") =
            Issue(code.name, severity, detail)
    }
}

/** The installer's steps, in order (docs/Deployment.md §8.2). */
enum class StepId {
    PREFLIGHT, APT, SWAP, JAVA, PACKAGES, PORTS, FIREWALL, FILES, TLS, CONFIG, SERVICE, HEALTH,
    FAIL2BAN, UPDATES, TIMESYNC, SSH, FINISH, UNINSTALL,
    ;

    companion object {
        fun of(id: String): StepId? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

enum class StepState { START, OK, SKIP, WARN, FAIL, WAIT }

/** One `##vm` line: `##vm v=1 seq=N ts=MS ev=EVENT key=value …`, values percent-decoded. */
data class Marker(val seq: Long, val ts: Long, val event: String, val fields: Map<String, String>) {
    operator fun get(key: String): String? = fields[key]
}

object MarkerParser {
    private const val PREFIX = "##vm "

    /** The marker on [line], or null for log text (and for a marker from another protocol version). */
    fun parse(line: String): Marker? {
        if (!line.startsWith(PREFIX)) return null
        val fields = line.removePrefix(PREFIX).trim().split(' ').mapNotNull(::field).toMap()
        val seq = fields["seq"]?.toLongOrNull()
        val event = fields["ev"]
        return if (fields["v"] != INSTALLER_PROTOCOL.toString() || seq == null || event == null) {
            null
        } else {
            Marker(seq, fields["ts"]?.toLongOrNull() ?: 0, event, fields - setOf("v", "seq", "ts", "ev"))
        }
    }

    fun issueOf(marker: Marker): Issue? {
        val code = marker["code"] ?: return null
        val severity = Severity.entries.firstOrNull { it.name.equals(marker["severity"], ignoreCase = true) }
            ?: Severity.INFO
        return Issue(code, severity, marker["detail"].orEmpty(), marker["step"])
    }

    private fun field(part: String): Pair<String, String>? {
        val at = part.indexOf('=')
        return if (at <= 0) null else part.substring(0, at) to decode(part.substring(at + 1))
    }

    // '+' is literal in the installer's encoding (only %XX is special), so it must not become ' '.
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
}

/**
 * Turns streamed bytes into whole lines, and knows the byte offset just past the last whole line —
 * where `--follow --from-byte` resumes after a dropped connection, so nothing is lost or repeated.
 */
class LogLineAssembler(startOffset: Long = 0) {
    private val pending = ByteArrayOutputStream()

    /** Bytes of the log consumed as whole lines. */
    var offset: Long = startOffset
        private set

    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (b in chunk) {
            if (b == NEWLINE) {
                val bytes = pending.toByteArray()
                lines += bytes.toString(Charsets.UTF_8)
                offset += bytes.size + 1
                pending.reset()
            } else {
                pending.write(b.toInt())
            }
        }
        return lines
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
