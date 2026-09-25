package ir.vmessenger.core.nodesetup

import ir.vmessenger.core.common.network.NodeUrl
import ir.vmessenger.core.common.network.SpkiPin
import ir.vmessenger.core.ssh.HostKey
import ir.vmessenger.core.ssh.SshAuth
import ir.vmessenger.core.ssh.SshTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** What the person asked for. [auth] is wiped when the engine is done with it, however it ends. */
class NodeSetupRequest(
    val target: SshTarget,
    val auth: SshAuth,
    val options: InstallOptions,
    /** The host key confirmed before (updating a node): a different one is a hard stop. */
    val knownHostKey: HostKey? = null,
)

/** One file of the installer bundle the app carries (`node-installer/` assets). */
class BundleFile(val path: String, val sha256: String, val size: Long, val open: () -> InputStream)

/** The installer bundle: its node version and files, SHA256SUMS and manifest.json included. */
interface InstallerBundle {
    val nodeVersion: String
    val protocol: Int
    val files: List<BundleFile>
}

/** Where the setup is; the wizard renders this. */
sealed class NodeSetupState {
    data object Connecting : NodeSetupState()
    data class ConfirmHostKey(val hostKey: HostKey) : NodeSetupState()
    data object CheckingServer : NodeSetupState()
    data object NeedsSudoPassword : NodeSetupState()
    data class NeedsDecision(val issues: List<Issue>) : NodeSetupState()
    data class Uploading(val doneBytes: Long, val totalBytes: Long) : NodeSetupState()
    data class Installing(val steps: Map<StepId, StepState>, val issues: List<Issue>, val logTail: List<String>) :
        NodeSetupState()
    data class Reconnecting(val attempt: Int) : NodeSetupState()
    data object Verifying : NodeSetupState()
    data class Done(val result: InstallResult, val reach: Reachability) : NodeSetupState()
    data class Failed(val issue: Issue, val logTail: List<String>, val resumable: Boolean) : NodeSetupState()
}

/** Whether the phone itself can reach the new node. */
sealed class Reachability {
    data object Ok : Reachability()
    data class Problem(val issue: Issue) : Reachability()
}

/** The phone-side check, on the app's real transport, with the pin (implemented in `:data`). */
fun interface NodeVerifier {
    suspend fun verify(result: InstallResult): Reachability
}

/** The installer's result.json (schema 1), checked. */
data class InstallResult(
    val runId: String,
    val nodeVersion: String,
    val nodeId: String?,
    val mode: String,
    val publicHost: String,
    val publicPort: Int,
    val domain: String?,
    val relayUrl: String,
    val bootstrapUrl: String,
    val pin: String?,
    val keyOnlySsh: String?,
    val secured: Boolean,
    val warnings: List<String>,
    val replacesUrls: List<String>,
) {
    companion object {
        /**
         * Parses and checks result.json: status ok, the bundle's version, URLs that are node URLs,
         * and — for a pinned node — a pin that is the certificate's key and that the URLs carry.
         * Null when any of it does not hold.
         */
        fun parse(json: String, expectedVersion: String): InstallResult? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            fun str(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
            val hardening = root["hardening"] as? JsonObject
            val result = InstallResult(
                runId = str("runId")!!,
                nodeVersion = str("nodeVersion")!!,
                nodeId = str("nodeId"),
                mode = str("mode")!!,
                publicHost = str("publicHost")!!,
                publicPort = root["publicPort"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PORT,
                domain = str("domain"),
                relayUrl = str("relayUrl")!!,
                bootstrapUrl = str("bootstrapUrl")!!,
                pin = str("pin"),
                keyOnlySsh = hardening?.get("keyOnlySsh")?.jsonPrimitive?.contentOrNull,
                secured = hardening?.get("fail2ban")?.jsonPrimitive?.contentOrNull == "on",
                warnings = strings(root, "warnings"),
                replacesUrls = strings(root, "replacesUrls"),
            )
            result.takeIf {
                str("status") == "ok" && it.nodeVersion == expectedVersion && it.urlsHold() &&
                    it.pinHolds(str("certPem"))
            }
        }.getOrNull()

        private const val DEFAULT_PORT = 443

        private fun InstallResult.urlsHold(): Boolean {
            val relay = NodeUrl.parse(relayUrl)
            val bootstrap = NodeUrl.parse(bootstrapUrl)
            return relay != null && bootstrap != null && relay.scheme == "wss" &&
                relay.isPinned == (pin != null) && bootstrap.isPinned == (pin != null)
        }

        private fun strings(root: JsonObject, key: String): List<String> =
            (root[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

        /** Unpinned only when a CA vouches (domain-ca); pinned, the pin is the served key and the URLs carry it. */
        private fun InstallResult.pinHolds(certPem: String?): Boolean {
            val served = certPem?.let {
                runCatching {
                    SpkiPin.of(
                        CertificateFactory.getInstance(
                            "X.509"
                        ).generateCertificate(it.byteInputStream()) as X509Certificate
                    )
                }.getOrNull()
            }
            return if (pin == null) {
                mode == "domain-ca"
            } else {
                served?.text == pin && NodeUrl.parse(relayUrl)?.pins == listOfNotNull(served)
            }
        }
    }
}
